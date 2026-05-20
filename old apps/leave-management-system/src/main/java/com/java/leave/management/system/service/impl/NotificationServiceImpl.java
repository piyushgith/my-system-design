package com.java.leave.management.system.service.impl;

import com.java.leave.management.system.dto.NotificationDto;
import com.java.leave.management.system.entity.Notification;
import com.java.leave.management.system.repository.NotificationRepository;
import com.java.leave.management.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    public Mono<NotificationDto> createNotification(Long recipientId, Long leaveRequestId, String notificationType, String title, String message) {
        Notification notification = Notification.builder()
            .recipientId(recipientId)
            .leaveRequestId(leaveRequestId)
            .notificationType(notificationType)
            .title(title)
            .message(message)
            .isRead(false)
            .createdAt(LocalDateTime.now())
            .build();
        
        return notificationRepository.save(notification)
            .map(this::mapToDto);
    }

    @Override
    public Flux<NotificationDto> getNotificationsForEmployee(Long employeeId, Boolean isRead, int page, int size) {
        Flux<Notification> notifications;
        if (isRead == null) {
            notifications = notificationRepository.findByRecipientId(employeeId);
        } else {
            notifications = notificationRepository.findByRecipientIdAndIsRead(employeeId, isRead);
        }
        
        return notifications
            .map(this::mapToDto);
    }

    @Override
    public Mono<NotificationDto> markNotificationAsRead(Long notificationId, Long employeeId) {
        return notificationRepository.findById(notificationId)
            .flatMap(notification -> {
                if (!notification.getRecipientId().equals(employeeId)) {
                    return Mono.error(new RuntimeException("Unauthorized: You can only mark your own notifications as read"));
                }
                
                notification.setIsRead(true);
                notification.setReadAt(LocalDateTime.now());
                
                return notificationRepository.save(notification)
                    .map(this::mapToDto);
            });
    }

    @Override
    public Mono<Long> getUnreadNotificationCount(Long employeeId) {
        return notificationRepository.findByRecipientIdAndIsRead(employeeId, false)
            .count();
    }

    private NotificationDto mapToDto(Notification notification) {
        NotificationDto dto = new NotificationDto();
        dto.setId(notification.getId());
        dto.setLeaveRequestId(notification.getLeaveRequestId());
        dto.setNotificationType(notification.getNotificationType());
        dto.setTitle(notification.getTitle());
        dto.setMessage(notification.getMessage());
        dto.setIsRead(notification.getIsRead());
        dto.setCreatedAt(notification.getCreatedAt());
        dto.setReadAt(notification.getReadAt());
        return dto;
    }
}