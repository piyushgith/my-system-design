package com.test.file.storage.service.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Tracks an in-progress multipart upload until it is completed or aborted. */
@Entity
@Table(name = "upload_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UploadSession {

    @Id
    private String id;

    @Column(nullable = false)
    private String fileName;

    private String mimeType;

    /** Final object storage key the assembled file will live at. */
    @Column(nullable = false)
    private String storageKey;

    /** Provider-specific multipart upload id returned by the backend. */
    @Column(nullable = false)
    private String providerUploadId;

    @Column(nullable = false)
    private String backend;

    private String ownerId;

    @Column(nullable = false)
    private long uploadedBytes;

    @Column(nullable = false)
    private int receivedParts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus status;

    /** Set once the session is completed, linking to the created file. */
    private String fileId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
