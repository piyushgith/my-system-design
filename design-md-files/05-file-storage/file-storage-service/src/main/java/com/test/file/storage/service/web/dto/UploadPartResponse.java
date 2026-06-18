package com.test.file.storage.service.web.dto;

import com.test.file.storage.service.catalog.UploadPart;

public record UploadPartResponse(
        int partNumber,
        String etag,
        long sizeBytes) {

    public static UploadPartResponse from(UploadPart part) {
        return new UploadPartResponse(part.getPartNumber(), part.getEtag(), part.getSizeBytes());
    }
}
