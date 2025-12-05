package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.EmployeeDto;
import reactor.core.publisher.Mono;

public interface EmployeeService {
    Mono<EmployeeDto> getEmployeeById(Long id);
    Mono<EmployeeDto> getEmployeeByEmpId(String empId);
    Mono<EmployeeDto> getEmployeeByEmail(String email);
    Mono<Boolean> existsById(Long id);
    Mono<Boolean> existsByEmpId(String empId);
    Mono<Boolean> existsByEmail(String email);
}