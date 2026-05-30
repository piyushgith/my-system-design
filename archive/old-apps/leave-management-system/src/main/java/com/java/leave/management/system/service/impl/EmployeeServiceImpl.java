package com.java.leave.management.system.service.impl;

import com.java.leave.management.system.dto.EmployeeDto;
import com.java.leave.management.system.entity.Employee;
import com.java.leave.management.system.repository.EmployeeRepository;
import com.java.leave.management.system.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;

    @Override
    public Mono<EmployeeDto> getEmployeeById(Long id) {
        return employeeRepository.findById(id)
            .map(this::mapToDto);
    }

    @Override
    public Mono<EmployeeDto> getEmployeeByEmpId(String empId) {
        return employeeRepository.findByEmpId(empId)
            .map(this::mapToDto);
    }

    @Override
    public Mono<EmployeeDto> getEmployeeByEmail(String email) {
        return employeeRepository.findByEmail(email)
            .map(this::mapToDto);
    }

    @Override
    public Mono<Boolean> existsById(Long id) {
        return employeeRepository.existsById(id);
    }

    @Override
    public Mono<Boolean> existsByEmpId(String empId) {
        return employeeRepository.findByEmpId(empId)
            .hasElement()
            .map(exists -> exists);
    }

    @Override
    public Mono<Boolean> existsByEmail(String email) {
        return employeeRepository.findByEmail(email)
            .hasElement()
            .map(exists -> exists);
    }

    private EmployeeDto mapToDto(Employee employee) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(employee.getId());
        dto.setEmpId(employee.getEmpId());
        dto.setName(employee.getName());
        dto.setEmail(employee.getEmail());
        dto.setDepartment(employee.getDepartment());
        dto.setManagerId(employee.getManagerId());
        dto.setRoleName(employee.getRole());
        dto.setCreatedAt(employee.getCreatedAt());
        dto.setUpdatedAt(employee.getUpdatedAt());
        dto.setIsActive(employee.getIsActive());
        return dto;
    }
}