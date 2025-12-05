package com.java.leave.management.system.dto;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class LeaveRequestDto {
    private Long id;
    private String requestId;
    private Long employeeId;
    private String employeeName;
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer numberOfDays;
    private String reason;
    private String status;
    private String rejectionReason;
    private Long approvedBy;
    private String approvedByName;
    private LocalDateTime approvedOn;
    private LocalDateTime requestedOn;
    private LocalDateTime withdrawnOn;
    private LocalDateTime createdAt;
}