package com.java.leave.management.system.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class LeaveTypeDto {
    private Long id;
    private String name;
    private String description;
    private Integer annualAllocation;
    private Integer carryforwardLimit;
    private Boolean isActive;
    private LocalDateTime createdAt;
}