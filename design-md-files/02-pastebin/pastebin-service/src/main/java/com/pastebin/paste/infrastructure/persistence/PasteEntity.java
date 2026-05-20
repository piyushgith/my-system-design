package com.pastebin.paste.infrastructure.persistence;

import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ContentType;
import com.pastebin.shared.DeletionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pastes", schema = "paste")
@Getter
@Setter
public class PasteEntity {

    @Id
    private UUID id;

    @Column(name = "short_key", nullable = false)
    private String shortKey;

    private String title;

    @Column(nullable = false)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType;

    @Column(name = "content_inline", columnDefinition = "TEXT")
    private String contentInline;

    @Column(name = "content_s3_key")
    private String contentS3Key;

    @Column(name = "content_size", nullable = false)
    private long contentSize;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "deletion_reason")
    private DeletionReason deletionReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false)
    private AccessLevel accessLevel;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "is_abuse_flagged", nullable = false)
    private boolean abuseFlagged;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
