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
@Table("audit_logs")
public class AuditLog {

    @Id
    private Long id;
    
    @Column("leave_request_id")
    private Long leaveRequestId;
    
    private String action; // CREATED, APPROVED, REJECTED, WITHDRAWN
    
    @Column("performed_by")
    private Long performedBy;
    
    @Column("old_status")
    private String oldStatus;
    
    @Column("new_status")
    private String newStatus;
    
    private String remarks;
    
    @Column("created_at")
    private LocalDateTime createdAt;
}