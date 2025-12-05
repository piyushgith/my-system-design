package com.java.leave.management.system.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;

@Data
public class CarryforwardBalanceDto {
    @NotNull(message = "From year is required")
    @Min(value = 2000, message = "From year must be a valid year")
    private Integer fromYear;
    
    @NotNull(message = "To year is required")
    @Min(value = 2000, message = "To year must be a valid year")
    private Integer toYear;
}