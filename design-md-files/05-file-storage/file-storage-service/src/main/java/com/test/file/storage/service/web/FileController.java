package com.test.file.storage.service.web;

import com.test.file.storage.service.catalog.StoredFile;
import com.test.file.storage.service.service.FileService;
import com.test.file.storage.service.storage.StorageProperties;
import com.test.file.storage.service.web.dto.FileMetadataResponse;
import com.test.file.storage.service.web.dto.PageResponse;
import com.test.file.storage.service.web.dto.PresignedUrlResponse;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** REST surface for single-shot uploads, metadata, download, and delete. */
@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileService fileService;
    private final StorageProperties storageProperties;

    public FileController(FileService fileService, StorageProperties storageProperties) {
        this.fileService = fileService;
        this.storageProperties = storageProperties;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileMetadataResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Owner-Id", required = false) String ownerId) throws IOException {

        StoredFile stored = fileService.upload(
                file.getOriginalFilename(),
                file.getContentType(),
                file.getInputStream(),
                ownerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(FileMetadataResponse.from(stored));
    }

    @GetMapping("/{fileId}")
    public FileMetadataResponse get(@PathVariable String fileId) {
        return FileMetadataResponse.from(fileService.get(fileId));
    }

    @GetMapping
    public PageResponse<FileMetadataResponse> list(Pageable pageable) {
        Page<StoredFile> page = fileService.list(pageable);
        return PageResponse.from(page, FileMetadataResponse::from);
    }

    /**
     * Downloads a file. When the backend supports presigned URLs (e.g. MinIO) the client is
     * redirected (302) straight to storage, offloading bytes from the app server. Otherwise the
     * app streams the bytes itself (e.g. local filesystem backend).
     */
    @GetMapping("/{fileId}/download")
    public ResponseEntity<?> download(@PathVariable String fileId) {
        StoredFile file = fileService.get(fileId);

        Optional<String> presigned = fileService.presignedDownloadUrl(file, storageProperties.getPresignTtl());
        if (presigned.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(presigned.get()))
                    .build();
        }

        InputStreamResource resource = new InputStreamResource(fileService.openStream(file));
        // Build the header via ContentDisposition so the filename is RFC 5987-encoded — prevents
        // header injection from a client-supplied original filename containing quotes or CRLF.
        String name = file.getOriginalName() != null ? file.getOriginalName() : "download";
        ContentDisposition disposition = ContentDisposition.attachment().filename(name).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(
                        file.getMimeType() != null ? file.getMimeType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .contentLength(file.getSizeBytes())
                .body(resource);
    }

    @GetMapping("/{fileId}/presigned-url")
    public ResponseEntity<PresignedUrlResponse> presignedUrl(@PathVariable String fileId) {
        StoredFile file = fileService.get(fileId);
        return fileService.presignedDownloadUrl(file, storageProperties.getPresignTtl())
                .map(url -> ResponseEntity.ok(
                        new PresignedUrlResponse(url, storageProperties.getPresignTtl().toSeconds())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable String fileId) {
        fileService.delete(fileId);
        return ResponseEntity.noContent().build();
    }
}
