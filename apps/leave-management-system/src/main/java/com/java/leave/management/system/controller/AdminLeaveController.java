package com.java.leave.management.system.controller;

import com.java.leave.management.system.dto.*;
import com.java.leave.management.system.service.EmployeeService;
import com.java.leave.management.system.service.LeaveTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("${app.leave-management.base-path}/admin")
@RequiredArgsConstructor
public class AdminLeaveController {

    private final LeaveTypeService leaveTypeService;
    private final EmployeeService employeeService;

    @PostMapping("/leave-types")
    public Mono<ResponseEntity<LeaveTypeDto>> createLeaveType(@RequestBody @Valid CreateLeaveTypeDto requestDto) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .flatMap(authentication -> {
                    String username = authentication.getName();
                    return employeeService.getEmployeeByEmail(username)
                            .map(employeeDto -> employeeDto.getId())
                            .cast(Long.class)
                            .switchIfEmpty(Mono.error(new RuntimeException("Admin not found for user: " + username)));
                })
                .flatMap(adminId -> leaveTypeService.createLeaveType(requestDto))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/leave-types")
    public Mono<ResponseEntity<ApiResponse<List<LeaveTypeDto>>>> getAllLeaveTypes() {
        return leaveTypeService.getAllLeaveTypes()
                .collectList()
                .map(leaveTypes -> ApiResponse.<List<LeaveTypeDto>>success("Leave types retrieved successfully", leaveTypes))
                .map(response -> ResponseEntity.ok(response))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }
}