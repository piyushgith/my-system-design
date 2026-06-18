package com.test.file.storage.service.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Logical file metadata. Decoupled from physical bytes: it points at a {@link ContentBlob} by
 * content hash, so many files can share one physical object (deduplication).
 */
@Entity
@Table(name = "stored_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoredFile {

    @Id
    private String id;

    @Column(nullable = false)
    private String originalName;

    private String mimeType;

    @Column(nullable = false)
    private long sizeBytes;

    /** SHA-256 of the content; links to {@link ContentBlob}. Null is never stored — see service. */
    @Column(nullable = false)
    private String contentHash;

    /** Object storage key of the underlying blob (denormalized from {@link ContentBlob}). */
    @Column(nullable = false)
    private String storageKey;

    /** Which backend physically holds the bytes ({@code local}/{@code minio}). */
    @Column(nullable = false)
    private String backend;

    private String ownerId;

    @Column(nullable = false)
    private Instant createdAt;
}
