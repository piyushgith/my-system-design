package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class ApproveLeaveRequestDto {
    @NotBlank(message = "Remarks are required for approval")
    private String remarks;
}