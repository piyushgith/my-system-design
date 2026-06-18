package com.test.file.storage.service.web;

import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.catalog.UploadPart;
import com.test.file.storage.service.catalog.UploadSession;
import com.test.file.storage.service.catalog.UploadStatus;
import com.test.file.storage.service.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UploadController.class)
class UploadControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UploadService uploadService;

    private static UploadSession session() {
        Instant now = Instant.now();
        return UploadSession.builder()
                .id("s1").fileName("big.bin").mimeType("application/octet-stream")
                .status(UploadStatus.IN_PROGRESS).receivedParts(0).uploadedBytes(0)
                .createdAt(now).expiresAt(now.plusSeconds(3600)).build();
    }

    @Test
    void initReturns201WithSession() throws Exception {
        when(uploadService.init(eq("big.bin"), any(), any())).thenReturn(session());

        mockMvc.perform(post("/api/v1/uploads/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"big.bin\",\"mimeType\":\"application/octet-stream\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadId").value("s1"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void initRejectsBlankFileNameWith400() throws Exception {
        mockMvc.perform(post("/api/v1/uploads/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"\",\"mimeType\":\"text/plain\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    void uploadPartReturnsPartMetadata() throws Exception {
        UploadPart part = UploadPart.builder()
                .id("p1").sessionId("s1").partNumber(1).etag("e1").sizeBytes(5).build();
        when(uploadService.uploadPart(eq("s1"), eq(1), any(), eq(5L))).thenReturn(part);

        mockMvc.perform(put("/api/v1/uploads/s1/parts/1")
                        .content("hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partNumber").value(1));
    }

    @Test
    void completeReturns201WithFile() throws Exception {
        StoredFile file = StoredFile.builder().id("f1").originalName("big.bin").build();
        when(uploadService.complete("s1")).thenReturn(file);

        mockMvc.perform(post("/api/v1/uploads/s1/complete"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value("f1"));
    }

    @Test
    void getReturnsSessionStatus() throws Exception {
        when(uploadService.getSession("s1")).thenReturn(session());

        mockMvc.perform(get("/api/v1/uploads/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").value("s1"));
    }

    @Test
    void abortReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/uploads/s1"))
                .andExpect(status().isNoContent());
        verify(uploadService).abort("s1");
    }
}
