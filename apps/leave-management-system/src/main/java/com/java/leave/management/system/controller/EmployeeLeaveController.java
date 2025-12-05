package com.java.leave.management.system.controller;

import com.java.leave.management.system.dto.*;
import com.java.leave.management.system.service.EmployeeService;
import com.java.leave.management.system.service.LeaveRequestService;
import com.java.leave.management.system.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("${app.leave-management.base-path}")
@RequiredArgsConstructor
public class EmployeeLeaveController {

    private final LeaveRequestService leaveRequestService;
    private final NotificationService notificationService;
    private final EmployeeService employeeService;

    @PostMapping("/leaves/request")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> requestLeave(
            @RequestBody @Valid CreateLeaveRequestDto requestDto) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMap(employeeId -> leaveRequestService.requestLeave(employeeId, requestDto))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/my-requests")
    public Mono<ResponseEntity<PagedResponseDto<LeaveRequestDto>>> getMyLeaveRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long leaveTypeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMap(employeeId -> leaveRequestService.getMyLeaveRequests(employeeId, status, leaveTypeId, page, size))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/balance")
    public Mono<ResponseEntity<LeaveBalanceResponseDto>> getLeaveBalance(
            @RequestParam(required = false) Integer year) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMap(employeeId -> leaveRequestService.getLeaveBalance(employeeId, year))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/leaves/{requestId}/withdraw")
    public Mono<ResponseEntity<LeaveRequestResponseDto>> withdrawLeaveRequest(@PathVariable String requestId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMap(employeeId -> leaveRequestService.withdrawLeaveRequest(requestId, employeeId))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leaves/{requestId}")
    public Mono<ResponseEntity<LeaveRequestDto>> getLeaveRequestDetails(@PathVariable String requestId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMap(employeeId -> leaveRequestService.getLeaveRequestDetails(requestId, employeeId))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/notifications")
    public Mono<ResponseEntity<PagedResponseDto<NotificationDto>>> getMyNotifications(
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMapMany(employeeId -> notificationService.getNotificationsForEmployee(employeeId, isRead, page, size))
                .collectList()
                .map(notifications -> {
                    PagedResponseDto<NotificationDto> response = new PagedResponseDto<>();
                    response.setContent(notifications);
                    response.setPageNumber(page);
                    response.setPageSize(size);
                    response.setTotalElements((long) notifications.size());
                    response.setTotalPages(notifications.size() / size);
                    response.setLast(page >= response.getTotalPages() - 1);
                    return response;
                })
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PutMapping("/notifications/{notificationId}/read")
    public Mono<ResponseEntity<NotificationDto>> markNotificationAsRead(@PathVariable Long notificationId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Employee not found for user: " + username)));
                })
                .flatMap(employeeId -> notificationService.markNotificationAsRead(notificationId, employeeId))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }
}