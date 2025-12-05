package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@Data
public class CreateLeaveTypeDto {
    @NotBlank(message = "Leave type name is required")
    private String name;
    
    private String description;
    
    @NotNull(message = "Annual allocation is required")
    @Min(value = 0, message = "Annual allocation must be at least 0")
    private Integer annualAllocation;
    
    @Min(value = 0, message = "Carryforward limit must be at least 0")
    private Integer carryforwardLimit;
}