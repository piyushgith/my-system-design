package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@Data
public class LeaveAllocationDto {
    @NotNull(message = "Employee ID is required")
    private Long employeeId;
    
    @NotNull(message = "Leave type ID is required")
    private Long leaveTypeId;
    
    @NotNull(message = "Days allocation is required")
    @Min(value = 0, message = "Days must be at least 0")
    private Integer days;
}