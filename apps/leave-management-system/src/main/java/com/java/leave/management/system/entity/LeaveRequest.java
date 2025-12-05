package com.java.leave.management.system.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("leave_requests")
public class LeaveRequest {

    @Id
    private Long id;
    
    @Column("request_id")
    private String requestId;
    
    @Column("employee_id")
    private Long employeeId;
    
    @Column("leave_type_id")
    private Long leaveTypeId;
    
    @Column("start_date")
    private LocalDate startDate;
    
    @Column("end_date")
    private LocalDate endDate;
    
    @Column("number_of_days")
    private Integer numberOfDays;
    
    private String reason;
    
    private String status; // PENDING, APPROVED, REJECTED, WITHDRAWN
    
    @Column("approved_by")
    private Long approvedBy;
    
    @Column("rejection_reason")
    private String rejectionReason;
    
    @Column("withdrawn_on")
    private LocalDateTime withdrawnOn;
    
    @Column("requested_on")
    private LocalDateTime requestedOn;
    
    @Column("approved_on")
    private LocalDateTime approvedOn;
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
}