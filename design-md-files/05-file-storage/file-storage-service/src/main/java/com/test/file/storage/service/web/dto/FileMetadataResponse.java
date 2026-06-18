package com.test.file.storage.service.web.dto;

import com.test.file.storage.service.catalog.StoredFile;

import java.time.Instant;

public record FileMetadataResponse(
        String fileId,
        String name,
        String mimeType,
        long sizeBytes,
        String contentHash,
        String backend,
        String ownerId,
        Instant createdAt,
        String downloadUrl) {

    public static FileMetadataResponse from(StoredFile file) {
        return new FileMetadataResponse(
                file.getId(),
                file.getOriginalName(),
                file.getMimeType(),
                file.getSizeBytes(),
                file.getContentHash(),
                file.getBackend(),
                file.getOwnerId(),
                file.getCreatedAt(),
                "/api/v1/files/" + file.getId() + "/download");
    }
}
