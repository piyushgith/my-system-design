package com.java.leave.management.system.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EmployeeDto {
    private Long id;
    private String empId;
    private String name;
    private String email;
    private String department;
    private Long managerId;
    private String roleName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean isActive;
}