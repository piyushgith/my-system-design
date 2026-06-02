package com.pastebin.cleanup.application;

import com.pastebin.paste.application.event.PasteDeletedEvent;
import com.pastebin.paste.infrastructure.storage.ContentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ContentStorageCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(ContentStorageCleanupListener.class);

    private final ContentStorage contentStorage;

    ContentStorageCleanupListener(ContentStorage contentStorage) {
        this.contentStorage = contentStorage;
    }

    @EventListener
    public void onPasteDeleted(PasteDeletedEvent event) {
        if (event.contentS3Key() == null || event.contentS3Key().isBlank()) {
            return;
        }
        try {
            contentStorage.delete(event.contentS3Key());
        } catch (Exception e) {
            log.error("Failed to delete content object {}", event.contentS3Key(), e);
        }
    }
}
