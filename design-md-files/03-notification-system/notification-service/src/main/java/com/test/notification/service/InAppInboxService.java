package com.test.notification.service;

import com.test.notification.domain.model.InAppInbox;
import com.test.notification.domain.repository.InAppInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InAppInboxService {

    private final InAppInboxRepository inboxRepository;

    @Transactional(readOnly = true)
    public Page<InAppInbox> getInbox(UUID userId, boolean unreadOnly, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        return unreadOnly
                ? inboxRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId, pageable)
                : inboxRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return inboxRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional
    public Optional<InAppInbox> markAsRead(UUID userId, UUID inboxItemId) {
        return inboxRepository.findById(inboxItemId)
                .filter(item -> item.getUserId().equals(userId))
                .map(item -> {
                    item.setRead(true);
                    item.setReadAt(Instant.now());
                    return inboxRepository.save(item);
                });
    }

    @Transactional
    public int markAllAsRead(UUID userId) {
        return inboxRepository.markAllReadForUser(userId, Instant.now());
    }

    @Transactional
    public InAppInbox saveInboxItem(InAppInbox item) {
        return inboxRepository.save(item);
    }

    @Scheduled(cron = "0 0 2 * * *") // nightly 2am
    @Transactional
    public void purgeExpired() {
        int deleted = inboxRepository.deleteExpired(Instant.now());
        log.info("Purged {} expired in-app inbox items", deleted);
    }
}
