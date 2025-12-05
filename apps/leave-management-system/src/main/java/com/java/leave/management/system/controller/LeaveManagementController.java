package com.java.leave.management.system.controller;

import com.java.leave.management.system.dto.*;
import com.java.leave.management.system.service.LeaveRequestService;
import com.java.leave.management.system.service.LeaveTypeService;
import com.java.leave.management.system.service.NotificationService;
import com.java.leave.management.system.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("${app.leave-management.base-path}")
@RequiredArgsConstructor
public class LeaveManagementController {

    private final LeaveRequestService leaveRequestService;
    private final LeaveTypeService leaveTypeService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    // Employee Endpoints
    @PostMapping("/leaves/request")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> requestLeave(
            @RequestBody @Valid CreateLeaveRequestDto requestDto) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.requestLeave(employeeId, requestDto)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/my-requests")
    public Mono<ResponseEntity<PagedResponseDto<LeaveRequestDto>>> getMyLeaveRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long leaveTypeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.getMyLeaveRequests(employeeId, status, leaveTypeId, page, size)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/balance")
    public Mono<ResponseEntity<LeaveBalanceResponseDto>> getLeaveBalance(
            @RequestParam(required = false) Integer year) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.getLeaveBalance(employeeId, year)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/leaves/{requestId}/withdraw")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> withdrawLeaveRequest(@PathVariable String requestId) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.withdrawLeaveRequest(requestId, employeeId)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/{requestId}")
    public Mono<ResponseEntity<LeaveRequestDto>> getLeaveRequestDetails(@PathVariable String requestId) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.getLeaveRequestDetails(requestId, employeeId)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/notifications")
    public Mono<ResponseEntity<PagedResponseDto<NotificationDto>>> getMyNotifications(
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return notificationService.getNotificationsForEmployee(employeeId, isRead, page, size)
                .collectList() // Collect Flux to List to create PagedResponseDto
                .map(notifications -> {
                    PagedResponseDto<NotificationDto> response = new PagedResponseDto<>();
                    response.setContent(notifications);
                    response.setPageNumber(page);
                    response.setPageSize(size);
                    response.setTotalElements((long) notifications.size());
                    response.setTotalPages((int) Math.ceil((double) notifications.size() / size));
                    response.setLast(page == response.getTotalPages() - 1);
                    return response;
                })
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/notifications/{notificationId}/read")
    public Mono<ResponseEntity<NotificationDto>> markNotificationAsRead(@PathVariable Long notificationId) {
        // In a real application, you would extract employeeId from the JWT token
        Long employeeId = 1L; // Placeholder - should come from JWT token
        return notificationService.markNotificationAsRead(notificationId, employeeId)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    // Manager Endpoints
    @GetMapping("/leaves/team-requests")
    public Mono<ResponseEntity<PagedResponseDto<LeaveRequestDto>>> getTeamLeaveRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // In a real application, you would extract managerId from the JWT token
        Long managerId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.getTeamLeaveRequests(managerId, status, page, size)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/leaves/{requestId}/approve")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> approveLeaveRequest(
            @PathVariable String requestId,
            @RequestBody @Valid ApproveLeaveRequestDto requestDto) {
        // In a real application, you would extract managerId from the JWT token
        Long managerId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.approveLeaveRequest(requestId, managerId, requestDto)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/leaves/{requestId}/reject")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> rejectLeaveRequest(
            @PathVariable String requestId,
            @RequestBody @Valid RejectLeaveRequestDto requestDto) {
        // In a real application, you would extract managerId from the JWT token
        Long managerId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.rejectLeaveRequest(requestId, managerId, requestDto)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/team-balance")
    public Mono<ResponseEntity<PagedResponseDto<LeaveBalanceDto>>> getTeamLeaveBalance(
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // In a real application, you would extract managerId from the JWT token
        Long managerId = 1L; // Placeholder - should come from JWT token
        return leaveRequestService.getTeamLeaveBalance(managerId, year, page, size)
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    // Admin Endpoints
    @PostMapping("/admin/leave-types")
    public Mono<ResponseEntity<LeaveTypeDto>> createLeaveType(@RequestBody @Valid CreateLeaveTypeDto requestDto) {
        // In a real application, you would extract adminId from the JWT token
        return leaveTypeService.createLeaveType(requestDto)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/admin/leave-types")
    public Mono<ResponseEntity<ApiResponse<List<LeaveTypeDto>>>> getAllLeaveTypes() {
        return leaveTypeService.getAllLeaveTypes()
                .collectList()
                .map(leaveTypes -> ApiResponse.<List<LeaveTypeDto>>success("Leave types retrieved successfully", leaveTypes))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }
}