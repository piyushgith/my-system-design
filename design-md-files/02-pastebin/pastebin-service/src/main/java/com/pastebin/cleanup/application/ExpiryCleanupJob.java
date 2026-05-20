package com.pastebin.cleanup.application;

import com.pastebin.paste.application.PasteService;
import com.pastebin.paste.application.event.PasteCreatedEvent;
import com.pastebin.paste.application.event.PasteDeletedEvent;
import com.pastebin.paste.infrastructure.persistence.ExpiryScheduleEntity;
import com.pastebin.paste.infrastructure.persistence.ExpiryScheduleJpaRepository;
import com.pastebin.paste.infrastructure.storage.S3ContentStorage;
import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.PasteId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class ExpiryCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupJob.class);

    private final ExpiryScheduleJpaRepository expiryScheduleRepository;
    private final PasteService pasteService;
    private final S3ContentStorage s3ContentStorage;
    private final Counter processedCounter;

    public ExpiryCleanupJob(ExpiryScheduleJpaRepository expiryScheduleRepository,
                            PasteService pasteService,
                            S3ContentStorage s3ContentStorage,
                            MeterRegistry meterRegistry) {
        this.expiryScheduleRepository = expiryScheduleRepository;
        this.pasteService = pasteService;
        this.s3ContentStorage = s3ContentStorage;
        this.processedCounter = Counter.builder("cleanup.processed.count").register(meterRegistry);
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void pollExpiredPastes() {
        Instant now = Instant.now();
        List<ExpiryScheduleEntity> pending = expiryScheduleRepository.findPendingExpirations(now);
        for (ExpiryScheduleEntity schedule : pending) {
            try {
                pasteService.markExpired(new PasteId(schedule.getPasteId()), DeletionReason.EXPIRED);
                schedule.setProcessed(true);
                schedule.setProcessedAt(now);
                expiryScheduleRepository.save(schedule);
                processedCounter.increment();
            } catch (Exception e) {
                log.error("Failed to expire paste {}", schedule.getPasteId(), e);
            }
        }
    }

    @EventListener
    public void onPasteDeleted(PasteDeletedEvent event) {
        if (event.contentS3Key() != null && !event.contentS3Key().isBlank()) {
            try {
                s3ContentStorage.delete(event.contentS3Key());
            } catch (Exception e) {
                log.error("Failed to delete S3 object {}", event.contentS3Key(), e);
            }
        }
    }

    @EventListener
    public void onPasteCreated(PasteCreatedEvent event) {
        log.debug("Paste created with expiry at {}", event.expiresAt());
    }
}
