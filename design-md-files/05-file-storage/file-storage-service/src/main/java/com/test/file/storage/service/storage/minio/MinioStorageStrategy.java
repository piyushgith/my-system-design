package com.test.file.storage.service.storage.minio;

import com.test.file.storage.service.storage.PartETag;
import com.test.file.storage.service.storage.StorageException;
import com.test.file.storage.service.storage.StorageProperties;
import com.test.file.storage.service.storage.StorageStrategy;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * MinIO/S3-compatible object storage.
 *
 * <p>Multipart is implemented with the public SDK's {@code composeObject}: each part is uploaded as
 * its own object under a temporary prefix, then server-side composed into the final object and the
 * temp parts removed. Note S3/MinIO require every composed source except the last to be >= 5 MiB.
 */
@Component
public class MinioStorageStrategy implements StorageStrategy {

    /** S3/MinIO compose requires every source object except the last to be at least 5 MiB. */
    private static final long MIN_COMPOSE_PART_SIZE = 5L * 1024 * 1024;

    private final MinioClient client;
    private final String bucket;

    public MinioStorageStrategy(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.bucket = properties.getMinio().getBucket();
    }

    @Override
    public String name() {
        return "minio";
    }

    @Override
    public boolean supportsPresignedUrls() {
        return true;
    }

    @Override
    public void store(String key, InputStream data, long sizeBytes, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(data, sizeBytes, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to store object " + key, e);
        }
    }

    @Override
    public InputStream retrieve(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Failed to read object " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw new StorageException("Failed to stat object " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new StorageException("Failed to delete object " + key, e);
        }
    }

    @Override
    public String presignedGetUrl(String key, Duration ttl) {
        return presignedUrl(Method.GET, key, ttl);
    }

    @Override
    public String presignedPutUrl(String key, Duration ttl) {
        return presignedUrl(Method.PUT, key, ttl);
    }

    private String presignedUrl(Method method, String key, Duration ttl) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(bucket)
                    .object(key)
                    .expiry((int) ttl.toSeconds())
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to generate presigned URL for " + key, e);
        }
    }

    @Override
    public String initiateMultipart(String key, String contentType) {
        // No native multipart handle is needed for the compose approach; mint our own id to scope parts.
        return UUID.randomUUID().toString();
    }

    @Override
    public PartETag uploadPart(String key, String uploadId, int partNumber, InputStream data, long sizeBytes) {
        String partKey = partKey(uploadId, partNumber);
        try {
            ObjectWriteResponse resp = client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(partKey)
                    .stream(data, sizeBytes, -1)
                    .build());
            return new PartETag(partNumber, resp.etag(), sizeBytes);
        } catch (Exception e) {
            throw new StorageException("Failed to upload part " + partNumber + " for " + key, e);
        }
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<PartETag> parts) {
        List<PartETag> ordered = parts.stream().sorted(Comparator.comparingInt(PartETag::partNumber)).toList();
        // Validate the S3/MinIO compose constraint up front (every part but the last >= 5 MiB) so the
        // failure is a clear 4xx-style error rather than an opaque provider exception mid-compose.
        for (int i = 0; i < ordered.size() - 1; i++) {
            PartETag part = ordered.get(i);
            if (part.sizeBytes() < MIN_COMPOSE_PART_SIZE) {
                throw new StorageException("Part " + part.partNumber() + " is " + part.sizeBytes()
                        + " bytes; all parts except the last must be at least " + MIN_COMPOSE_PART_SIZE
                        + " bytes (5 MiB) for MinIO/S3 multipart compose");
            }
        }
        List<ComposeSource> sources = new ArrayList<>();
        for (PartETag part : ordered) {
            sources.add(ComposeSource.builder()
                    .bucket(bucket)
                    .object(partKey(uploadId, part.partNumber()))
                    .build());
        }
        try {
            client.composeObject(ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .sources(sources)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Failed to compose multipart object " + key, e);
        }
        removeParts(uploadId, parts);
    }

    @Override
    public void abortMultipart(String key, String uploadId, List<PartETag> parts) {
        // The compose approach has no native upload handle, so we explicitly delete the temp part
        // objects the caller recorded. Prevents orphaned part objects (storage cost) on abort.
        removeParts(uploadId, parts);
    }

    private void removeParts(String uploadId, List<PartETag> parts) {
        for (PartETag part : parts) {
            try {
                client.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(partKey(uploadId, part.partNumber()))
                        .build());
            } catch (Exception ignored) {
                // best-effort cleanup of temp parts
            }
        }
    }

    private String partKey(String uploadId, int partNumber) {
        return "multipart/" + uploadId + "/part-" + partNumber;
    }
}
