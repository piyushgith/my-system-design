package com.java.leave.management.system.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class NotificationDto {
    private Long id;
    private Long leaveRequestId;
    private String notificationType;
    private String title;
    private String message;
    private Boolean isRead;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}