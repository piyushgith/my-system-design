package com.java.leave.management.system.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("notifications")
public class Notification {

    @Id
    private Long id;
    
    @Column("recipient_id")
    private Long recipientId;
    
    @Column("leave_request_id")
    private Long leaveRequestId;
    
    @Column("notification_type")
    private String notificationType; // REQUEST_CREATED, APPROVED, REJECTED, WITHDRAWN
    
    private String title;
    
    private String message;
    
    @Column("is_read")
    private Boolean isRead;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("read_at")
    private LocalDateTime readAt;
}