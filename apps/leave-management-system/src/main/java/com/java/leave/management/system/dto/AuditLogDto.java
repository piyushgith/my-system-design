package com.java.leave.management.system.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AuditLogDto {
    private Long id;
    private Long leaveRequestId;
    private String action;
    private Long performedBy;
    private String performedByName;
    private String oldStatus;
    private String newStatus;
    private String remarks;
    private LocalDateTime createdAt;
}