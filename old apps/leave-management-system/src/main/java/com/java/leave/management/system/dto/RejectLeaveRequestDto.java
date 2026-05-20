package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class RejectLeaveRequestDto {
    @NotBlank(message = "Rejection reason is required")
    private String rejectionReason;
}