package com.test.file.storage.service.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UploadInitRequest(
        @NotBlank String fileName,
        String mimeType) {
}
