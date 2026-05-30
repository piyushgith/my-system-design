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
@Table("leave_types")
public class LeaveType {

    @Id
    private Long id;
    
    private String name; // PERSONAL, SPECIAL, COMP_OFF
    
    private String description;
    
    @Column("annual_allocation")
    private Integer annualAllocation; // days per year
    
    @Column("carryforward_limit")
    private Integer carryforwardLimit; // max days that can be carried forward
    
    @Column("is_active")
    private Boolean isActive;
    
    @Column("created_at")
    private LocalDateTime createdAt;
}