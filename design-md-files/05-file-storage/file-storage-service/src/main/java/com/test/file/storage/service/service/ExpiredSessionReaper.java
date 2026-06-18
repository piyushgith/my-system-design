package com.test.file.storage.service.service;

import com.test.file.storage.service.catalog.UploadSession;
import com.test.file.storage.service.catalog.UploadSessionRepository;
import com.test.file.storage.service.catalog.UploadStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodically aborts upload sessions whose TTL has passed. Access-time checks in
 * {@link UploadService} reject expired sessions, but a never-resumed session would otherwise leave
 * its temp part objects (storage cost) and {@code IN_PROGRESS} rows around forever. This reaper does
 * the physical cleanup that a thrown exception cannot (it can't also commit a status change).
 */
@Component
public class ExpiredSessionReaper {

    private static final Logger log = LoggerFactory.getLogger(ExpiredSessionReaper.class);

    private final UploadSessionRepository sessionRepository;
    private final UploadService uploadService;

    public ExpiredSessionReaper(UploadSessionRepository sessionRepository, UploadService uploadService) {
        this.sessionRepository = sessionRepository;
        this.uploadService = uploadService;
    }

    /** Runs hourly (after an initial delay) to abort expired, still-in-progress sessions. */
    @Scheduled(fixedDelayString = "${app.storage.reaper.interval:PT1H}", initialDelayString = "PT1M")
    public void reapExpiredSessions() {
        Instant now = Instant.now();

        List<UploadSession> expiredInProgress = sessionRepository
                .findByStatusAndExpiresAtBefore(UploadStatus.IN_PROGRESS, now);
        if (!expiredInProgress.isEmpty()) {
            log.info("Reaping {} expired IN_PROGRESS session(s)", expiredInProgress.size());
            for (UploadSession session : expiredInProgress) {
                try {
                    uploadService.abort(session.getId());
                } catch (RuntimeException e) {
                    log.warn("Failed to reap upload session {}: {}", session.getId(), e.getMessage());
                }
            }
        }

        // Sessions stuck in PROCESSING (async finalizer crashed or never fired) are also cleaned up.
        List<UploadSession> stuckProcessing = sessionRepository
                .findByStatusAndExpiresAtBefore(UploadStatus.PROCESSING, now);
        if (!stuckProcessing.isEmpty()) {
            log.info("Reaping {} stuck PROCESSING session(s)", stuckProcessing.size());
            for (UploadSession session : stuckProcessing) {
                try {
                    uploadService.markAbortedProcessing(session.getId());
                } catch (RuntimeException e) {
                    log.warn("Failed to reap PROCESSING session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }
}
