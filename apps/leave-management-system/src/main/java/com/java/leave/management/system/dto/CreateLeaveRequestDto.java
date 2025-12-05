package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

@Data
public class CreateLeaveRequestDto {
    @NotNull(message = "Leave type ID is required")
    private Long leaveTypeId;
    
    @NotNull(message = "Start date is required")
    private LocalDate startDate;
    
    @NotNull(message = "End date is required")
    private LocalDate endDate;
    
    @NotBlank(message = "Reason is required")
    private String reason;
}