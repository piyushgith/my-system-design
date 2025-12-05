package com.java.leave.management.system.repository;

import com.java.leave.management.system.entity.Notification;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface NotificationRepository extends ReactiveCrudRepository<Notification, Long> {
    Flux<Notification> findByRecipientId(Long recipientId);
    Flux<Notification> findByRecipientIdAndIsRead(Long recipientId, Boolean isRead);
    Flux<Notification> findByLeaveRequestId(Long leaveRequestId);
}