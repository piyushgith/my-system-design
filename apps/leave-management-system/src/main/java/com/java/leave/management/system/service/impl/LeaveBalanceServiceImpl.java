package com.java.leave.management.system.service.impl;

import com.java.leave.management.system.dto.LeaveBalanceDto;
import com.java.leave.management.system.entity.LeaveBalance;
import com.java.leave.management.system.repository.LeaveBalanceRepository;
import com.java.leave.management.system.service.LeaveBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LeaveBalanceServiceImpl implements LeaveBalanceService {

    private final LeaveBalanceRepository leaveBalanceRepository;

    @Override
    public Mono<LeaveBalanceDto> getLeaveBalance(Long employeeId, Long leaveTypeId, Integer year) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
            .map(this::mapToDto);
    }

    @Override
    public Flux<LeaveBalanceDto> getLeaveBalances(Long employeeId, Integer year) {
        return leaveBalanceRepository.findByEmployeeIdAndYear(employeeId, year)
            .map(this::mapToDto);
    }

    @Override
    public Mono<LeaveBalanceDto> updateLeaveBalance(Long employeeId, Long leaveTypeId, Integer year, Integer usedDays) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
            .flatMap(leaveBalance -> {
                leaveBalance.setUsedDays(leaveBalance.getUsedDays() + usedDays);
                leaveBalance.setBalanceDays(leaveBalance.getAllocatedDays() + leaveBalance.getCarryforwardDays() - leaveBalance.getUsedDays());
                leaveBalance.setUpdatedAt(LocalDateTime.now());
                
                return leaveBalanceRepository.save(leaveBalance)
                    .map(this::mapToDto);
            });
    }

    @Override
    public Mono<LeaveBalanceDto> allocateLeaveBalance(Long employeeId, Long leaveTypeId, Integer year, Integer allocatedDays) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, year)
            .flatMap(existingBalance -> {
                // Update existing balance
                existingBalance.setAllocatedDays(allocatedDays);
                existingBalance.setBalanceDays(allocatedDays + existingBalance.getCarryforwardDays() - existingBalance.getUsedDays());
                existingBalance.setUpdatedAt(LocalDateTime.now());
                
                return leaveBalanceRepository.save(existingBalance)
                    .map(this::mapToDto);
            })
            .switchIfEmpty(createNewLeaveBalance(employeeId, leaveTypeId, year, allocatedDays));
    }

    @Override
    public Mono<LeaveBalanceDto> carryForwardBalance(Long employeeId, Long leaveTypeId, Integer fromYear, Integer toYear, Integer carryForwardDays) {
        return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, fromYear)
            .flatMap(fromBalance -> {
                // Get or create the balance for the to year
                return leaveBalanceRepository.findByEmployeeIdAndLeaveTypeIdAndYear(employeeId, leaveTypeId, toYear)
                    .flatMap(toBalance -> {
                        // Add carryforward days to existing carryforward
                        toBalance.setCarryforwardDays(toBalance.getCarryforwardDays() + carryForwardDays);
                        toBalance.setBalanceDays(toBalance.getAllocatedDays() + toBalance.getCarryforwardDays() - toBalance.getUsedDays());
                        toBalance.setUpdatedAt(LocalDateTime.now());
                        
                        return leaveBalanceRepository.save(toBalance)
                            .map(this::mapToDto);
                    })
                    .switchIfEmpty(createNewLeaveBalance(employeeId, leaveTypeId, toYear, 0, carryForwardDays));
            });
    }

    private Mono<LeaveBalanceDto> createNewLeaveBalance(Long employeeId, Long leaveTypeId, Integer year, Integer allocatedDays) {
        return createNewLeaveBalance(employeeId, leaveTypeId, year, allocatedDays, 0);
    }

    private Mono<LeaveBalanceDto> createNewLeaveBalance(Long employeeId, Long leaveTypeId, Integer year, Integer allocatedDays, Integer carryForwardDays) {
        LeaveBalance leaveBalance = LeaveBalance.builder()
            .employeeId(employeeId)
            .leaveTypeId(leaveTypeId)
            .year(year)
            .allocatedDays(allocatedDays)
            .usedDays(0)
            .carryforwardDays(carryForwardDays)
            .balanceDays(allocatedDays + carryForwardDays)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        
        return leaveBalanceRepository.save(leaveBalance)
            .map(this::mapToDto);
    }

    private LeaveBalanceDto mapToDto(LeaveBalance leaveBalance) {
        LeaveBalanceDto dto = new LeaveBalanceDto();
        dto.setLeaveTypeId(leaveBalance.getLeaveTypeId());
        // In a real application, you would fetch the leave type name from the leave type repository
        dto.setLeaveType("Leave Type Name"); // Placeholder
        dto.setAllocatedDays(leaveBalance.getAllocatedDays());
        dto.setUsedDays(leaveBalance.getUsedDays());
        dto.setCarriedForwardDays(leaveBalance.getCarryforwardDays());
        dto.setBalanceDays(leaveBalance.getBalanceDays());
        return dto;
    }
}