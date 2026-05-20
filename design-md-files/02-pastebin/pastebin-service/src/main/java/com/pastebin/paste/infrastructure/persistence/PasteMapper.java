package com.pastebin.paste.infrastructure.persistence;

import com.pastebin.paste.domain.Paste;
import com.pastebin.shared.PasteId;
import com.pastebin.shared.ShortKey;
import com.pastebin.shared.UserId;
import org.springframework.stereotype.Component;

@Component
public class PasteMapper {

    public PasteEntity toEntity(Paste paste) {
        PasteEntity entity = new PasteEntity();
        entity.setId(paste.getId().value());
        entity.setShortKey(paste.getShortKey().value());
        entity.setTitle(paste.getTitle());
        entity.setLanguage(paste.getLanguage());
        entity.setContentType(paste.getContentType());
        entity.setContentInline(paste.getContentInline());
        entity.setContentS3Key(paste.getContentS3Key());
        entity.setContentSize(paste.getContentSize());
        entity.setContentHash(paste.getContentHash());
        entity.setExpiresAt(paste.getExpiresAt());
        entity.setDeleted(paste.isDeleted());
        entity.setDeletedAt(paste.getDeletedAt());
        entity.setDeletionReason(paste.getDeletionReason());
        entity.setAccessLevel(paste.getAccessLevel());
        entity.setPasswordHash(paste.getPasswordHash());
        entity.setOwnerId(paste.getOwnerId() != null ? paste.getOwnerId().value() : null);
        entity.setAbuseFlagged(paste.isAbuseFlagged());
        entity.setViewCount(paste.getViewCount());
        entity.setCreatedAt(paste.getCreatedAt());
        entity.setUpdatedAt(paste.getUpdatedAt());
        return entity;
    }

    public Paste toDomain(PasteEntity entity) {
        return Paste.restore(
                new PasteId(entity.getId()),
                new ShortKey(entity.getShortKey()),
                entity.getTitle(),
                entity.getLanguage(),
                entity.getContentType(),
                entity.getContentInline(),
                entity.getContentS3Key(),
                entity.getContentSize(),
                entity.getContentHash(),
                entity.getExpiresAt(),
                entity.isDeleted(),
                entity.getDeletedAt(),
                entity.getDeletionReason(),
                entity.getAccessLevel(),
                entity.getPasswordHash(),
                entity.getOwnerId() != null ? new UserId(entity.getOwnerId()) : null,
                entity.isAbuseFlagged(),
                entity.getViewCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
