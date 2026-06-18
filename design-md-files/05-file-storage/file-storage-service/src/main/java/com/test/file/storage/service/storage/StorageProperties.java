package com.test.file.storage.service.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds {@code app.storage.*} configuration. Drives which {@link StorageStrategy} is active and
 * supplies backend-specific connection details.
 */
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /** Active backend: {@code local} or {@code minio}. Must match a {@link StorageStrategy#name()}. */
    private String backend = "local";

    /** How long presigned URLs remain valid. */
    private Duration presignTtl = Duration.ofMinutes(15);

    private final Local local = new Local();
    private final Minio minio = new Minio();

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public Duration getPresignTtl() {
        return presignTtl;
    }

    public void setPresignTtl(Duration presignTtl) {
        this.presignTtl = presignTtl;
    }

    public Local getLocal() {
        return local;
    }

    public Minio getMinio() {
        return minio;
    }

    public static class Local {
        /** Root directory under which objects are written. */
        private String basePath = "./data/storage";

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "files";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }
    }
}
