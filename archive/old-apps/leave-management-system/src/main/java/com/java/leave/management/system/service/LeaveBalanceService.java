package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.LeaveBalanceDto;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface LeaveBalanceService {
    Mono<LeaveBalanceDto> getLeaveBalance(Long employeeId, Long leaveTypeId, Integer year);
    Flux<LeaveBalanceDto> getLeaveBalances(Long employeeId, Integer year);
    Mono<LeaveBalanceDto> updateLeaveBalance(Long employeeId, Long leaveTypeId, Integer year, Integer usedDays);
    Mono<LeaveBalanceDto> allocateLeaveBalance(Long employeeId, Long leaveTypeId, Integer year, Integer allocatedDays);
    Mono<LeaveBalanceDto> carryForwardBalance(Long employeeId, Long leaveTypeId, Integer fromYear, Integer toYear, Integer carryForwardDays);
}