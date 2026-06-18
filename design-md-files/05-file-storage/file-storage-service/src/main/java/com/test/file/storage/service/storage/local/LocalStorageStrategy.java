package com.test.file.storage.service.storage.local;

import com.test.file.storage.service.storage.PartETag;
import com.test.file.storage.service.storage.StorageException;
import com.test.file.storage.service.storage.StorageProperties;
import com.test.file.storage.service.storage.StorageStrategy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Filesystem-backed storage for local development and tests.
 *
 * <p>Objects map to files under {@code app.storage.local.base-path}. Multipart uploads are emulated:
 * each part is a temp file under {@code <uploadId>.parts/}; completion concatenates them in order.
 * Presigned URLs are unsupported — the app must stream bytes itself for this backend.
 */
@Component
public class LocalStorageStrategy implements StorageStrategy {

    private final Path root;

    public LocalStorageStrategy(StorageProperties properties) {
        this.root = Path.of(properties.getLocal().getBasePath()).toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public boolean supportsPresignedUrls() {
        return false;
    }

    @Override
    public void store(String key, InputStream data, long sizeBytes, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("Failed to store object " + key, e);
        }
    }

    @Override
    public InputStream retrieve(String key) {
        Path source = resolve(key);
        if (!Files.exists(source)) {
            throw new StorageException("Object not found: " + key);
        }
        try {
            return Files.newInputStream(source);
        } catch (IOException e) {
            throw new StorageException("Failed to read object " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("Failed to delete object " + key, e);
        }
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl) {
        throw new UnsupportedOperationException("Local storage does not support presigned URLs");
    }

    @Override
    public String presignedPutUrl(String key, Duration ttl) {
        throw new UnsupportedOperationException("Local storage does not support presigned URLs");
    }

    @Override
    public String initiateMultipart(String key, String contentType) {
        // No provider state needed; the key itself anchors the parts directory. Return the key as the id.
        try {
            Files.createDirectories(partsDir(key));
        } catch (IOException e) {
            throw new StorageException("Failed to start multipart upload for " + key, e);
        }
        return key;
    }

    @Override
    public PartETag uploadPart(String key, String uploadId, int partNumber, InputStream data, long sizeBytes) {
        Path partFile = partsDir(key).resolve("part-" + partNumber);
        try {
            Files.createDirectories(partFile.getParent());
            long written = Files.copy(data, partFile, StandardCopyOption.REPLACE_EXISTING);
            return new PartETag(partNumber, "local-" + partNumber, written);
        } catch (IOException e) {
            throw new StorageException("Failed to upload part " + partNumber + " for " + key, e);
        }
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<PartETag> parts) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                for (PartETag part : parts.stream().sorted(Comparator.comparingInt(PartETag::partNumber)).toList()) {
                    Path partFile = partsDir(key).resolve("part-" + part.partNumber());
                    if (!Files.exists(partFile)) {
                        throw new StorageException("Missing part " + part.partNumber() + " for " + key);
                    }
                    Files.copy(partFile, out);
                }
            }
            cleanupParts(key);
        } catch (IOException e) {
            throw new StorageException("Failed to complete multipart upload for " + key, e);
        }
    }

    @Override
    public void abortMultipart(String key, String uploadId, List<PartETag> parts) {
        // Parts live under <key>.parts/, so the key alone is enough to clean them up.
        cleanupParts(key);
    }

    private Path partsDir(String key) {
        return resolve(key + ".parts");
    }

    private void cleanupParts(String key) {
        Path dir = partsDir(key);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException e) {
            throw new StorageException("Failed to clean up parts for " + key, e);
        }
    }

    /** Resolve a key under the root, guarding against path traversal. */
    private Path resolve(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Illegal storage key (path traversal): " + key);
        }
        return resolved;
    }
}
