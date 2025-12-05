package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.NotificationDto;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface NotificationService {
    Mono<NotificationDto> createNotification(Long recipientId, Long leaveRequestId, String notificationType, String title, String message);
    Flux<NotificationDto> getNotificationsForEmployee(Long employeeId, Boolean isRead, int page, int size);
    Mono<NotificationDto> markNotificationAsRead(Long notificationId, Long employeeId);
    Mono<Long> getUnreadNotificationCount(Long employeeId);
}