package com.test.file.storage.service.web;

import com.test.file.storage.service.service.UploadService;
import com.test.file.storage.service.web.dto.FileMetadataResponse;
import com.test.file.storage.service.web.dto.UploadInitRequest;
import com.test.file.storage.service.web.dto.UploadPartResponse;
import com.test.file.storage.service.web.dto.UploadSessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * REST surface for multipart / large-file uploads:
 * init → upload parts → complete (or abort).
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/init")
    public ResponseEntity<UploadSessionResponse> init(
            @Valid @RequestBody UploadInitRequest request,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId) {

        UploadSessionResponse body = UploadSessionResponse.from(
                uploadService.init(request.fileName(), request.mimeType(), ownerId));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /** Uploads one part. Body is the raw part bytes; size is taken from {@code Content-Length}. */
    @PutMapping("/{sessionId}/parts/{partNumber}")
    public UploadPartResponse uploadPart(
            @PathVariable String sessionId,
            @PathVariable int partNumber,
            HttpServletRequest request) throws IOException {

        long size = request.getContentLengthLong();
        return UploadPartResponse.from(
                uploadService.uploadPart(sessionId, partNumber, request.getInputStream(), size));
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<FileMetadataResponse> complete(@PathVariable String sessionId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FileMetadataResponse.from(uploadService.complete(sessionId)));
    }

    @GetMapping("/{sessionId}")
    public UploadSessionResponse status(@PathVariable String sessionId) {
        return UploadSessionResponse.from(uploadService.getSession(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> abort(@PathVariable String sessionId) {
        uploadService.abort(sessionId);
        return ResponseEntity.noContent().build();
    }
}
