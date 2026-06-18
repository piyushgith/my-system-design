package com.test.file.storage.service.storage.minio;

import com.test.file.storage.service.storage.StorageException;
import com.test.file.storage.service.storage.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MinIO client.
 *
 * <p>The {@link MinioClient} bean is always created — its construction opens no network connection,
 * so it is cheap even when MinIO isn't the active backend. Registering it unconditionally lets the
 * {@code StorageStrategyResolver} resolve a {@code minio}-backed object even while the active backend
 * is {@code local} (e.g. files written before a backend switch). The actual MinIO server is only
 * contacted on a real call, and the bucket is ensured at startup <em>only</em> when MinIO is active.
 */
@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        StorageProperties.Minio cfg = properties.getMinio();
        return MinioClient.builder()
                .endpoint(cfg.getEndpoint())
                .credentials(cfg.getAccessKey(), cfg.getSecretKey())
                .build();
    }

    /** Create the bucket on startup, but only when MinIO is the active backend. */
    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient client, StorageProperties properties) {
        return args -> {
            if ("minio".equals(properties.getBackend())) {
                ensureBucket(client, properties.getMinio().getBucket());
            }
        };
    }

    private void ensureBucket(MinioClient client, String bucket) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new StorageException("Failed to ensure MinIO bucket '" + bucket + "' exists", e);
        }
    }
}
