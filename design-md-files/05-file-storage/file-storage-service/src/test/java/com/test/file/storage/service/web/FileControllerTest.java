package com.test.file.storage.service.web;

import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.service.FileService;
import com.test.file.storage.service.storage.StorageProperties;
import com.test.file.storage.service.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
class FileControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean FileService fileService;
    @MockitoBean StorageProperties storageProperties;

    private static StoredFile sampleFile() {
        return StoredFile.builder()
                .id("f1").originalName("a.txt").mimeType("text/plain").sizeBytes(5)
                .contentHash("h1").storageKey("blobs/h1").backend("local").ownerId("o")
                .createdAt(Instant.now()).build();
    }

    @Test
    void uploadReturns201WithMetadata() throws Exception {
        when(fileService.upload(any(), any(), any(), any())).thenReturn(sampleFile());
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/files").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value("f1"))
                .andExpect(jsonPath("$.downloadUrl").value("/api/v1/files/f1/download"));
    }

    @Test
    void getReturnsMetadata() throws Exception {
        when(fileService.get("f1")).thenReturn(sampleFile());

        mockMvc.perform(get("/api/v1/files/f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("a.txt"));
    }

    @Test
    void getMissingReturns404ProblemDetail() throws Exception {
        when(fileService.get("missing")).thenThrow(new ResourceNotFoundException("File not found: missing"));

        mockMvc.perform(get("/api/v1/files/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/files/f1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void presignedUrlReturns501WhenBackendUnsupported() throws Exception {
        when(fileService.get("f1")).thenReturn(sampleFile());
        when(storageProperties.getPresignTtl()).thenReturn(Duration.ofMinutes(15));
        when(fileService.presignedDownloadUrl(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/files/f1/presigned-url"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void presignedUrlReturnsUrlWhenSupported() throws Exception {
        when(fileService.get("f1")).thenReturn(sampleFile());
        when(storageProperties.getPresignTtl()).thenReturn(Duration.ofMinutes(15));
        when(fileService.presignedDownloadUrl(any(), any()))
                .thenReturn(Optional.of("https://minio/presigned"));

        mockMvc.perform(get("/api/v1/files/f1/presigned-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://minio/presigned"));
    }

    @Test
    void downloadStreamsWhenNoPresignSupport() throws Exception {
        when(fileService.get("f1")).thenReturn(sampleFile());
        when(storageProperties.getPresignTtl()).thenReturn(Duration.ofMinutes(15));
        when(fileService.presignedDownloadUrl(any(), any())).thenReturn(Optional.empty());
        when(fileService.openStream(any()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/v1/files/f1/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("a.txt")));
    }

    @Test
    void downloadRedirectsWhenPresignSupported() throws Exception {
        when(fileService.get("f1")).thenReturn(sampleFile());
        when(storageProperties.getPresignTtl()).thenReturn(Duration.ofMinutes(15));
        when(fileService.presignedDownloadUrl(any(), any()))
                .thenReturn(Optional.of("https://minio/presigned"));

        mockMvc.perform(get("/api/v1/files/f1/download"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://minio/presigned"));
    }
}
