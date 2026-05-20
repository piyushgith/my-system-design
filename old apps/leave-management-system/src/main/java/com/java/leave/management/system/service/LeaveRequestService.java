package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface LeaveRequestService {
    Mono<LeaveRequestResponseDto> requestLeave(Long employeeId, CreateLeaveRequestDto requestDto);
    Mono<PagedResponseDto<LeaveRequestDto>> getMyLeaveRequests(Long employeeId, String status, Long leaveTypeId, int page, int size);
    Mono<LeaveBalanceResponseDto> getLeaveBalance(Long employeeId, Integer year);
    Mono<LeaveRequestResponseDto> withdrawLeaveRequest(String requestId, Long employeeId);
    Mono<LeaveRequestDto> getLeaveRequestDetails(String requestId, Long employeeId);
    Mono<LeaveRequestResponseDto> approveLeaveRequest(String requestId, Long managerId, ApproveLeaveRequestDto requestDto);
    Mono<LeaveRequestResponseDto> rejectLeaveRequest(String requestId, Long managerId, RejectLeaveRequestDto requestDto);
    Mono<PagedResponseDto<LeaveRequestDto>> getTeamLeaveRequests(Long managerId, String status, int page, int size);
    Mono<PagedResponseDto<LeaveBalanceDto>> getTeamLeaveBalance(Long managerId, Integer year, int page, int size);
}