package com.test.file.storage.service.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One physical object in storage, keyed by content hash. {@code refCount} tracks how many
 * {@link StoredFile}s reference it; the bytes are deleted only when it reaches zero. The
 * {@code @Version} column guards concurrent refCount updates with optimistic locking.
 */
@Entity
@Table(name = "content_blob")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentBlob {

    /** SHA-256 of the content (or a synthetic id for multipart blobs whose hash isn't computed). */
    @Id
    private String contentHash;

    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String backend;

    @Column(nullable = false)
    private int refCount;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;
}
