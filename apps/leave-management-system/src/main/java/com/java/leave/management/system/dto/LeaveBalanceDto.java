package com.java.leave.management.system.dto;

import lombok.Data;

@Data
public class LeaveBalanceDto {
    private Long leaveTypeId;
    private String leaveType;
    private Integer allocatedDays;
    private Integer usedDays;
    private Integer carriedForwardDays;
    private Integer balanceDays;
}