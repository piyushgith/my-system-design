package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.ContentBlob;
import com.test.file.storage.service.catalog.ContentBlobRepository;
import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.catalog.StoredFileRepository;
import com.test.file.storage.service.catalog.UploadPart;
import com.test.file.storage.service.catalog.UploadPartRepository;
import com.test.file.storage.service.catalog.UploadSession;
import com.test.file.storage.service.catalog.UploadSessionRepository;
import com.test.file.storage.service.catalog.UploadStatus;
import com.test.file.storage.service.storage.PartETag;
import com.test.file.storage.service.storage.StorageStrategy;
import com.test.file.storage.service.storage.StorageStrategyResolver;
import com.test.file.storage.service.web.error.InvalidUploadStateException;
import com.test.file.storage.service.web.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Multipart / large-file upload flow: init a session, upload parts, then complete (assemble) or abort.
 *
 * <p>Deduplication is intentionally not applied here — the content hash isn't known while parts stream
 * in. Large files are the least likely to be exact duplicates, so this is a deliberate scope tradeoff.
 */
@Service
public class UploadService {

    private static final Duration SESSION_TTL = Duration.ofHours(24);

    private final UploadSessionRepository sessionRepository;
    private final UploadPartRepository partRepository;
    private final StoredFileRepository fileRepository;
    private final ContentBlobRepository blobRepository;
    private final StorageStrategyResolver resolver;

    public UploadService(UploadSessionRepository sessionRepository,
                         UploadPartRepository partRepository,
                         StoredFileRepository fileRepository,
                         ContentBlobRepository blobRepository,
                         StorageStrategyResolver resolver) {
        this.sessionRepository = sessionRepository;
        this.partRepository = partRepository;
        this.fileRepository = fileRepository;
        this.blobRepository = blobRepository;
        this.resolver = resolver;
    }

    @Transactional
    public UploadSession init(String fileName, String mimeType, String ownerId) {
        StorageStrategy storage = resolver.active();
        String sessionId = UUID.randomUUID().toString();
        String storageKey = "files/" + sessionId + "/" + fileName;
        String providerUploadId = storage.initiateMultipart(storageKey, mimeType);

        Instant now = Instant.now();
        UploadSession session = UploadSession.builder()
                .id(sessionId)
                .fileName(fileName)
                .mimeType(mimeType)
                .storageKey(storageKey)
                .providerUploadId(providerUploadId)
                .backend(storage.name())
                .ownerId(ownerId)
                .uploadedBytes(0)
                .receivedParts(0)
                .status(UploadStatus.IN_PROGRESS)
                .createdAt(now)
                .expiresAt(now.plus(SESSION_TTL))
                .build();
        return sessionRepository.save(session);
    }

    @Transactional
    public UploadPart uploadPart(String sessionId, int partNumber, InputStream data, long sizeBytes) {
        if (partNumber < 1) {
            throw new InvalidUploadStateException("Part number must be >= 1, got: " + partNumber);
        }
        UploadSession session = requireInProgress(sessionId);
        StorageStrategy storage = resolver.byName(session.getBackend());

        PartETag etag = storage.uploadPart(session.getStorageKey(), session.getProviderUploadId(),
                partNumber, data, sizeBytes);

        // Idempotent re-upload: the storage layer overwrote the part, so reconcile the existing row
        // and adjust the byte counter by the delta rather than inserting a duplicate (which would
        // violate the (sessionId, partNumber) unique constraint and double-count uploadedBytes).
        UploadPart part = partRepository.findBySessionIdAndPartNumber(sessionId, partNumber)
                .orElse(null);
        if (part != null) {
            long delta = etag.sizeBytes() - part.getSizeBytes();
            part.setEtag(etag.etag());
            part.setSizeBytes(etag.sizeBytes());
            session.setUploadedBytes(session.getUploadedBytes() + delta);
        } else {
            part = UploadPart.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionId(sessionId)
                    .partNumber(partNumber)
                    .etag(etag.etag())
                    .sizeBytes(etag.sizeBytes())
                    .build();
            session.setUploadedBytes(session.getUploadedBytes() + etag.sizeBytes());
            session.setReceivedParts(session.getReceivedParts() + 1);
        }
        UploadPart saved = partRepository.save(part);
        sessionRepository.save(session);
        return saved;
    }

    @Transactional
    public StoredFile complete(String sessionId) {
        UploadSession session = getSession(sessionId);

        // Idempotency: completing an already-completed session returns the same file.
        if (session.getStatus() == UploadStatus.COMPLETED) {
            return fileRepository.findById(session.getFileId())
                    .orElseThrow(() -> new ResourceNotFoundException("File not found: " + session.getFileId()));
        }
        if (session.getStatus() != UploadStatus.IN_PROGRESS) {
            throw new InvalidUploadStateException("Upload session is " + session.getStatus() + ": " + sessionId);
        }
        if (isExpired(session)) {
            throw new InvalidUploadStateException("Upload session has expired: " + sessionId);
        }

        List<UploadPart> parts = partRepository.findBySessionIdOrderByPartNumberAsc(sessionId);
        if (parts.isEmpty()) {
            throw new InvalidUploadStateException("No parts uploaded for session: " + sessionId);
        }

        StorageStrategy storage = resolver.byName(session.getBackend());
        List<PartETag> partETags = parts.stream()
                .map(p -> new PartETag(p.getPartNumber(), p.getEtag(), p.getSizeBytes()))
                .toList();
        storage.completeMultipart(session.getStorageKey(), session.getProviderUploadId(), partETags);

        // Synthetic hash: multipart content isn't hashed, so it never dedupes against single-shot blobs.
        String syntheticHash = "multipart:" + sessionId;
        ContentBlob blob = blobRepository.save(ContentBlob.builder()
                .contentHash(syntheticHash)
                .storageKey(session.getStorageKey())
                .sizeBytes(session.getUploadedBytes())
                .backend(session.getBackend())
                .refCount(1)
                .createdAt(Instant.now())
                .build());

        StoredFile file = fileRepository.save(StoredFile.builder()
                .id(UUID.randomUUID().toString())
                .originalName(session.getFileName())
                .mimeType(session.getMimeType())
                .sizeBytes(blob.getSizeBytes())
                .contentHash(syntheticHash)
                .storageKey(blob.getStorageKey())
                .backend(blob.getBackend())
                .ownerId(session.getOwnerId())
                .createdAt(Instant.now())
                .build());

        session.setStatus(UploadStatus.COMPLETED);
        session.setFileId(file.getId());
        sessionRepository.save(session);
        partRepository.deleteBySessionId(sessionId);
        return file;
    }

    @Transactional
    public void abort(String sessionId) {
        UploadSession session = getSession(sessionId);
        if (session.getStatus() != UploadStatus.IN_PROGRESS) {
            return;
        }
        // Pass the known parts so the storage backend can delete temp part objects (no orphans).
        List<PartETag> parts = partRepository.findBySessionIdOrderByPartNumberAsc(sessionId).stream()
                .map(p -> new PartETag(p.getPartNumber(), p.getEtag(), p.getSizeBytes()))
                .toList();
        resolver.byName(session.getBackend())
                .abortMultipart(session.getStorageKey(), session.getProviderUploadId(), parts);
        session.setStatus(UploadStatus.ABORTED);
        sessionRepository.save(session);
        partRepository.deleteBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public UploadSession getSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Upload session not found: " + sessionId));
    }

    private UploadSession requireInProgress(String sessionId) {
        UploadSession session = getSession(sessionId);
        if (session.getStatus() != UploadStatus.IN_PROGRESS) {
            throw new InvalidUploadStateException("Upload session is " + session.getStatus() + ": " + sessionId);
        }
        if (isExpired(session)) {
            // TTL passed — reject use. Physical cleanup of expired sessions is left to a reaper
            // job (see expiresAt); throwing here can't also persist a status change in the same tx.
            throw new InvalidUploadStateException("Upload session has expired: " + sessionId);
        }
        return session;
    }

    private boolean isExpired(UploadSession session) {
        return session.getExpiresAt() != null && Instant.now().isAfter(session.getExpiresAt());
    }
}
