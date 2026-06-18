package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.ContentBlob;
import com.test.file.storage.service.catalog.ContentBlobRepository;
import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.catalog.StoredFileRepository;
import com.test.file.storage.service.catalog.UploadSessionRepository;
import com.test.file.storage.service.catalog.UploadStatus;
import com.test.file.storage.service.storage.StorageStrategy;
import com.test.file.storage.service.storage.StorageStrategyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Runs the post-upload DB finalization on a virtual thread after the client confirms the presigned
 * PUT completed. Kept separate from {@link UploadService} so the {@code @Async} proxy is applied
 * correctly — Spring can't apply both async and transactional proxies to the same method invocation
 * from within the same bean. {@link TransactionTemplate} replaces {@code @Transactional} here.
 */
@Component
public class PresignedUploadFinalizer {

    private static final Logger log = LoggerFactory.getLogger(PresignedUploadFinalizer.class);

    private final UploadSessionRepository sessionRepository;
    private final StoredFileRepository fileRepository;
    private final ContentBlobRepository blobRepository;
    private final StorageStrategyResolver resolver;
    private final TransactionTemplate transactionTemplate;

    public PresignedUploadFinalizer(UploadSessionRepository sessionRepository,
                                    StoredFileRepository fileRepository,
                                    ContentBlobRepository blobRepository,
                                    StorageStrategyResolver resolver,
                                    TransactionTemplate transactionTemplate) {
        this.sessionRepository = sessionRepository;
        this.fileRepository = fileRepository;
        this.blobRepository = blobRepository;
        this.resolver = resolver;
        this.transactionTemplate = transactionTemplate;
    }

    @Async
    public void finalize(String sessionId, String contentHash, long sizeBytes) {
        try {
            transactionTemplate.execute(status -> {
                var session = sessionRepository.findById(sessionId).orElseThrow(
                        () -> new IllegalStateException("Session disappeared: " + sessionId));
                StorageStrategy storage = resolver.byName(session.getBackend());

                if (!storage.exists(session.getStorageKey())) {
                    log.warn("Presigned object missing for session {}, aborting", sessionId);
                    session.setStatus(UploadStatus.ABORTED);
                    sessionRepository.save(session);
                    return null;
                }

                ContentBlob blob = blobRepository.findByContentHash(contentHash)
                        .map(existing -> {
                            existing.setRefCount(existing.getRefCount() + 1);
                            // Client uploaded a duplicate; the existing blob already holds the bytes.
                            // Delete the orphaned object best-effort to avoid storage waste.
                            try {
                                storage.delete(session.getStorageKey());
                            } catch (Exception e) {
                                log.warn("Could not delete orphaned object {}: {}",
                                        session.getStorageKey(), e.getMessage());
                            }
                            return blobRepository.save(existing);
                        })
                        .orElseGet(() -> blobRepository.save(ContentBlob.builder()
                                .contentHash(contentHash)
                                .storageKey(session.getStorageKey())
                                .sizeBytes(sizeBytes)
                                .backend(session.getBackend())
                                .refCount(1)
                                .createdAt(Instant.now())
                                .build()));

                StoredFile file = fileRepository.save(StoredFile.builder()
                        .id(UUID.randomUUID().toString())
                        .originalName(session.getFileName())
                        .mimeType(session.getMimeType())
                        .sizeBytes(blob.getSizeBytes())
                        .contentHash(contentHash)
                        .storageKey(blob.getStorageKey())
                        .backend(blob.getBackend())
                        .ownerId(session.getOwnerId())
                        .createdAt(Instant.now())
                        .build());

                session.setStatus(UploadStatus.COMPLETED);
                session.setFileId(file.getId());
                sessionRepository.save(session);
                return null;
            });
        } catch (Exception e) {
            log.error("Presigned upload finalization failed for session {}: {}", sessionId, e.getMessage(), e);
            markAborted(sessionId);
        }
    }

    private void markAborted(String sessionId) {
        try {
            transactionTemplate.execute(status -> {
                sessionRepository.findById(sessionId).ifPresent(s -> {
                    s.setStatus(UploadStatus.ABORTED);
                    sessionRepository.save(s);
                });
                return null;
            });
        } catch (Exception ex) {
            log.error("Could not mark session {} as ABORTED after finalization failure", sessionId, ex);
        }
    }
}
