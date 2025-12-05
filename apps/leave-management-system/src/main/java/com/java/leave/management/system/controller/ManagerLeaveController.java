package com.java.leave.management.system.controller;

import com.java.leave.management.system.dto.*;
import com.java.leave.management.system.service.EmployeeService;
import com.java.leave.management.system.service.LeaveRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;

@RestController
@RequestMapping("${app.leave-management.base-path}")
@RequiredArgsConstructor
public class ManagerLeaveController {

    private final LeaveRequestService leaveRequestService;
    private final EmployeeService employeeService;

    @GetMapping("/leaves/team-requests")
    public Mono<ResponseEntity<PagedResponseDto<LeaveRequestDto>>> getTeamLeaveRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Manager not found for user: " + username)));
                })
                .flatMap(managerId -> leaveRequestService.getTeamLeaveRequests(managerId, status, page, size))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/leaves/{requestId}/approve")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> approveLeaveRequest(
            @PathVariable String requestId,
            @RequestBody @Valid ApproveLeaveRequestDto requestDto) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Manager not found for user: " + username)));
                })
                .flatMap(managerId -> leaveRequestService.approveLeaveRequest(requestId, managerId, requestDto))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/leaves/{requestId}/reject")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> rejectLeaveRequest(
            @PathVariable String requestId,
            @RequestBody @Valid RejectLeaveRequestDto requestDto) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Manager not found for user: " + username)));
                })
                .flatMap(managerId -> leaveRequestService.rejectLeaveRequest(requestId, managerId, requestDto))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/team-balance")
    public Mono<ResponseEntity<PagedResponseDto<LeaveBalanceDto>>> getTeamLeaveBalance(
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Manager not found for user: " + username)));
                })
                .flatMap(managerId -> leaveRequestService.getTeamLeaveBalance(managerId, year, page, size))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }
}