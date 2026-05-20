package com.pastebin.paste.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pastebin.s3")
public record S3Properties(
        String bucket,
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess
) {
}
