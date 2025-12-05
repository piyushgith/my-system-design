package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.util.List;

@Data
public class BulkLeaveAllocationDto {
    @NotNull(message = "Year is required")
    @Min(value = 2000, message = "Year must be a valid year")
    private Integer year;
    
    private List<LeaveAllocationDto> leaveAllocations;
}