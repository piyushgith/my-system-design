package com.pastebin.paste.domain;

import com.pastebin.shared.AccessLevel;
import com.pastebin.shared.ContentType;
import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.ExpiryPolicy;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;
import com.pastebin.shared.UserId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

public class Paste {

    private final PasteId id;
    private final ShortKey shortKey;
    private String title;
    private final String language;
    private ContentType contentType;
    private String contentInline;
    private String contentS3Key;
    private final long contentSize;
    private final String contentHash;
    private Instant expiresAt;
    private boolean deleted;
    private Instant deletedAt;
    private DeletionReason deletionReason;
    private AccessLevel accessLevel;
    private String passwordHash;
    private UserId ownerId;
    private boolean abuseFlagged;
    private long viewCount;
    private final Instant createdAt;
    private Instant updatedAt;

    private Paste(PasteId id,
                  ShortKey shortKey,
                  String title,
                  String language,
                  ContentType contentType,
                  String contentInline,
                  String contentS3Key,
                  long contentSize,
                  String contentHash,
                  Instant expiresAt,
                  AccessLevel accessLevel,
                  String passwordHash,
                  UserId ownerId,
                  Instant createdAt) {
        this.id = id;
        this.shortKey = shortKey;
        this.title = title;
        this.language = language;
        this.contentType = contentType;
        this.contentInline = contentInline;
        this.contentS3Key = contentS3Key;
        this.contentSize = contentSize;
        this.contentHash = contentHash;
        this.expiresAt = expiresAt;
        this.accessLevel = accessLevel;
        this.passwordHash = passwordHash;
        this.ownerId = ownerId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public static Paste create(PasteId id,
                               ShortKey shortKey,
                               String title,
                               String language,
                               ContentType contentType,
                               String contentInline,
                               String contentS3Key,
                               long contentSize,
                               String contentHash,
                               ExpiryPolicy expiryPolicy,
                               AccessLevel accessLevel,
                               String passwordHash,
                               UserId ownerId,
                               Instant now,
                               long maxSizeBytes) {
        if (contentSize <= 0) {
            throw new DomainException("Paste content cannot be empty");
        }
        if (contentSize > maxSizeBytes) {
            throw new DomainException("Paste content exceeds maximum size of " + maxSizeBytes + " bytes");
        }
        if (accessLevel == AccessLevel.PRIVATE && ownerId == null) {
            throw new DomainException("Private pastes require an authenticated owner");
        }
        validateContentRouting(contentType, contentInline, contentS3Key, contentSize);

        Instant expiresAt = expiryPolicy == ExpiryPolicy.NEVER ? null : expiryPolicyToInstant(expiryPolicy, now);
        return new Paste(id, shortKey, title, language, contentType, contentInline, contentS3Key,
                contentSize, contentHash, expiresAt, accessLevel, passwordHash, ownerId, now);
    }

    public static Paste restore(PasteId id,
                                ShortKey shortKey,
                                String title,
                                String language,
                                ContentType contentType,
                                String contentInline,
                                String contentS3Key,
                                long contentSize,
                                String contentHash,
                                Instant expiresAt,
                                boolean deleted,
                                Instant deletedAt,
                                DeletionReason deletionReason,
                                AccessLevel accessLevel,
                                String passwordHash,
                                UserId ownerId,
                                boolean abuseFlagged,
                                long viewCount,
                                Instant createdAt,
                                Instant updatedAt) {
        Paste paste = new Paste(id, shortKey, title, language, contentType, contentInline, contentS3Key,
                contentSize, contentHash, expiresAt, accessLevel, passwordHash, ownerId, createdAt);
        paste.deleted = deleted;
        paste.deletedAt = deletedAt;
        paste.deletionReason = deletionReason;
        paste.abuseFlagged = abuseFlagged;
        paste.viewCount = viewCount;
        paste.updatedAt = updatedAt;
        return paste;
    }

    public void assertReadable(Optional<UserId> requesterId, boolean passwordVerified) {
        if (deleted) {
            throw new PasteGoneException("Paste has been deleted");
        }
        if (isExpired(Instant.now())) {
            throw new PasteGoneException("Paste has expired");
        }
        if (abuseFlagged) {
            throw new PasteNotAccessibleException("Paste is not accessible");
        }
        if (accessLevel == AccessLevel.PRIVATE) {
            if (requesterId.isEmpty() || ownerId == null || !ownerId.equals(requesterId.get())) {
                throw new PasteNotAccessibleException("Private paste requires owner authentication");
            }
        }
        if (passwordHash != null && !passwordVerified) {
            throw new PastePasswordRequiredException("Password required to view this paste");
        }
    }

    public void softDelete(DeletionReason reason, Instant now) {
        this.deleted = true;
        this.deletedAt = now;
        this.deletionReason = reason;
        this.updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean requiresPassword() {
        return passwordHash != null;
    }

    public boolean isPublic() {
        return accessLevel == AccessLevel.PUBLIC && !deleted;
    }

    private static void validateContentRouting(ContentType contentType,
                                               String contentInline,
                                               String contentS3Key,
                                               long contentSize) {
        if (contentType == ContentType.INLINE && (contentInline == null || contentInline.isEmpty())) {
            throw new DomainException("Inline content type requires inline content");
        }
        if (contentType == ContentType.S3 && (contentS3Key == null || contentS3Key.isBlank())) {
            throw new DomainException("S3 content type requires S3 key");
        }
        if (contentType == ContentType.INLINE && contentInline != null && contentInline.getBytes(StandardCharsets.UTF_8).length != contentSize) {
            throw new DomainException("Inline content size mismatch");
        }
    }

    private static Instant expiryPolicyToInstant(ExpiryPolicy policy, Instant now) {
        return switch (policy) {
            case ONE_HOUR -> now.plusSeconds(3600);
            case ONE_DAY -> now.plusSeconds(86400);
            case ONE_WEEK -> now.plusSeconds(604800);
            case ONE_MONTH -> now.plusSeconds(2592000);
            case NEVER -> null;
        };
    }

    public PasteId getId() { return id; }
    public ShortKey getShortKey() { return shortKey; }
    public String getTitle() { return title; }
    public String getLanguage() { return language; }
    public ContentType getContentType() { return contentType; }
    public String getContentInline() { return contentInline; }
    public String getContentS3Key() { return contentS3Key; }
    public long getContentSize() { return contentSize; }
    public String getContentHash() { return contentHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isDeleted() { return deleted; }
    public Instant getDeletedAt() { return deletedAt; }
    public DeletionReason getDeletionReason() { return deletionReason; }
    public AccessLevel getAccessLevel() { return accessLevel; }
    public String getPasswordHash() { return passwordHash; }
    public UserId getOwnerId() { return ownerId; }
    public boolean isAbuseFlagged() { return abuseFlagged; }
    public long getViewCount() { return viewCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
