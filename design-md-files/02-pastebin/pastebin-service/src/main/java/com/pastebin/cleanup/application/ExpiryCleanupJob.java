package com.pastebin.cleanup.application;

import com.pastebin.paste.application.PasteService;
import com.pastebin.paste.infrastructure.persistence.ExpiryScheduleEntity;
import com.pastebin.paste.infrastructure.persistence.ExpiryScheduleJpaRepository;
import com.pastebin.shared.DeletionReason;
import com.pastebin.shared.PasteId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Counter processedCounter;

    public ExpiryCleanupJob(ExpiryScheduleJpaRepository expiryScheduleRepository,
                            PasteService pasteService,
                            MeterRegistry meterRegistry) {
        this.expiryScheduleRepository = expiryScheduleRepository;
        this.pasteService = pasteService;
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
}
