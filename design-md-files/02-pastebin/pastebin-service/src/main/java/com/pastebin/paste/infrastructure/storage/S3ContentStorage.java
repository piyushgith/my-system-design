package com.pastebin.paste.infrastructure.storage;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;

@Component
public class S3ContentStorage {

    private final S3Client s3Client;
    private final S3Properties properties;

    public S3ContentStorage(S3Client s3Client, S3Properties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    public void upload(String key, String content) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType("text/plain; charset=utf-8")
                        .build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8)
        );
    }

    public String download(String key) {
        try {
            return s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .build()
            ).asString(StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            throw new ContentStorageException("Content not found in object storage: " + key, e);
        }
    }

    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }

    public String buildKey(String pasteId) {
        return "pastes/" + pasteId + ".txt";
    }
}
