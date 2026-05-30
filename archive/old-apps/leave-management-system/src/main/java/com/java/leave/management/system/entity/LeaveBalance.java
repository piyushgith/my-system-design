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
@Table("leave_balances")
public class LeaveBalance {

    @Id
    private Long id;
    
    @Column("employee_id")
    private Long employeeId;
    
    @Column("leave_type_id")
    private Long leaveTypeId;
    
    private Integer year;
    
    @Column("allocated_days")
    private Integer allocatedDays;
    
    @Column("used_days")
    private Integer usedDays;
    
    @Column("carryforward_days")
    private Integer carryforwardDays;
    
    @Column("balance_days")
    private Integer balanceDays; // Generated: allocated_days + carryforward_days - used_days
    
    @Column("created_at")
    private LocalDateTime createdAt;
    
    @Column("updated_at")
    private LocalDateTime updatedAt;
}