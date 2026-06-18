package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.ContentBlob;
import com.test.file.storage.service.catalog.ContentBlobRepository;
import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.catalog.StoredFileRepository;
import com.test.file.storage.service.storage.StorageException;
import com.test.file.storage.service.storage.StorageStrategy;
import com.test.file.storage.service.storage.StorageStrategyResolver;
import com.test.file.storage.service.web.error.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-shot file operations: upload (with whole-file deduplication), metadata reads, listing,
 * download access, and delete (with reference-counted blob garbage collection).
 */
@Service
public class FileService {

    private final StoredFileRepository fileRepository;
    private final ContentBlobRepository blobRepository;
    private final StorageStrategyResolver resolver;

    public FileService(StoredFileRepository fileRepository,
                       ContentBlobRepository blobRepository,
                       StorageStrategyResolver resolver) {
        this.fileRepository = fileRepository;
        this.blobRepository = blobRepository;
        this.resolver = resolver;
    }

    /**
     * Stores an uploaded stream. The content is hashed (SHA-256); if an identical blob already
     * exists its ref count is incremented and no bytes are re-written (deduplication).
     */
    @Transactional
    public StoredFile upload(String originalName, String mimeType, InputStream content, String ownerId) {
        StorageStrategy storage = resolver.active();
        Path temp = stageToTemp(content);
        // Tracks bytes we physically wrote for a brand-new blob, so we can delete them if the
        // surrounding transaction fails afterwards (storage write + DB are not one atomic unit).
        String newlyStoredKey = null;
        try {
            long size = fileSize(temp);
            String hash = HashUtil.sha256(temp);

            Optional<ContentBlob> existing = blobRepository.findByContentHash(hash);
            ContentBlob blob;
            if (existing.isPresent()) {
                blob = existing.get();
                blob.setRefCount(blob.getRefCount() + 1);
                blob = blobRepository.save(blob);
            } else {
                String storageKey = "blobs/" + hash;
                try (InputStream in = Files.newInputStream(temp)) {
                    storage.store(storageKey, in, size, mimeType);
                } catch (IOException e) {
                    throw new StorageException("Failed to read staged upload", e);
                }
                newlyStoredKey = storageKey;
                blob = blobRepository.save(ContentBlob.builder()
                        .contentHash(hash)
                        .storageKey(storageKey)
                        .sizeBytes(size)
                        .backend(storage.name())
                        .refCount(1)
                        .createdAt(Instant.now())
                        .build());
            }

            StoredFile file = StoredFile.builder()
                    .id(UUID.randomUUID().toString())
                    .originalName(originalName)
                    .mimeType(mimeType)
                    .sizeBytes(size)
                    .contentHash(hash)
                    .storageKey(blob.getStorageKey())
                    .backend(blob.getBackend())
                    .ownerId(ownerId)
                    .createdAt(Instant.now())
                    .build();
            return fileRepository.save(file);
        } catch (RuntimeException e) {
            // Compensate: drop the orphaned object so a failed DB commit doesn't leak bytes.
            if (newlyStoredKey != null) {
                try {
                    storage.delete(newlyStoredKey);
                } catch (RuntimeException ignored) {
                    // best-effort compensation
                }
            }
            throw e;
        } finally {
            deleteQuietly(temp);
        }
    }

    @Transactional(readOnly = true)
    public StoredFile get(String fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found: " + fileId));
    }

    @Transactional(readOnly = true)
    public Page<StoredFile> list(Pageable pageable) {
        return fileRepository.findAll(pageable);
    }

    /** Open the bytes for streaming download. Caller is responsible for closing the stream. */
    @Transactional(readOnly = true)
    public InputStream openStream(StoredFile file) {
        return resolver.byName(file.getBackend()).retrieve(file.getStorageKey());
    }

    /** Presigned download URL, when the backend supports it; otherwise {@link Optional#empty()}. */
    @Transactional(readOnly = true)
    public Optional<String> presignedDownloadUrl(StoredFile file, java.time.Duration ttl) {
        StorageStrategy storage = resolver.byName(file.getBackend());
        if (!storage.supportsPresignedUrls()) {
            return Optional.empty();
        }
        return Optional.of(storage.presignedGetUrl(file.getStorageKey(), ttl));
    }

    /**
     * Deletes a file. The underlying blob's ref count is decremented; the physical object is removed
     * only when no other file references it.
     */
    @Transactional
    public void delete(String fileId) {
        StoredFile file = get(fileId);
        blobRepository.findByContentHash(file.getContentHash()).ifPresent(blob -> {
            int remaining = blob.getRefCount() - 1;
            if (remaining <= 0) {
                resolver.byName(blob.getBackend()).delete(blob.getStorageKey());
                blobRepository.delete(blob);
            } else {
                blob.setRefCount(remaining);
                blobRepository.save(blob);
            }
        });
        fileRepository.delete(file);
    }

    private Path stageToTemp(InputStream content) {
        try {
            Path temp = Files.createTempFile("upload-", ".tmp");
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException e) {
            throw new StorageException("Failed to stage upload to temp file", e);
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new StorageException("Failed to read staged file size", e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}
