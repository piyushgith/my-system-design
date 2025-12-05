package com.java.leave.management.system.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table("employees")
public class Employee {

    @Id
    private Long id;
    
    @Column("emp_id")
    private String empId;
    
    private String name;
    
    private String email;
    
    private String department;
    
    @Column("manager_id")
    private Long managerId;
    
    private String role; // EMPLOYEE, MANAGER, ADMIN
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
    
    @Column("is_active")
    private Boolean isActive;
}