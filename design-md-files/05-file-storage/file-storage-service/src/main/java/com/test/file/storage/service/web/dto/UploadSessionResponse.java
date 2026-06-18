package com.test.file.storage.service.web.dto;

import com.test.file.storage.service.catalog.UploadSession;

import java.time.Instant;

public record UploadSessionResponse(
        String uploadId,
        String fileName,
        String status,
        int receivedParts,
        long uploadedBytes,
        String fileId,
        Instant expiresAt) {

    public static UploadSessionResponse from(UploadSession session) {
        return new UploadSessionResponse(
                session.getId(),
                session.getFileName(),
                session.getStatus().name(),
                session.getReceivedParts(),
                session.getUploadedBytes(),
                session.getFileId(),
                session.getExpiresAt());
    }
}
