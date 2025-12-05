package com.java.leave.management.system.dto;

import lombok.Data;
import java.util.List;

@Data
public class LeaveBalanceResponseDto {
    private Long employeeId;
    private Integer year;
    private List<LeaveBalanceDto> leaveBalances;
}