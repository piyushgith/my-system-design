package com.java.leave.management.system.repository;

import com.java.leave.management.system.entity.LeaveBalance;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

@Repository
public interface LeaveBalanceRepository extends ReactiveCrudRepository<LeaveBalance, Long> {
    Flux<LeaveBalance> findByEmployeeIdAndYear(Long employeeId, Integer year);
    Mono<LeaveBalance> findByEmployeeIdAndLeaveTypeIdAndYear(Long employeeId, Long leaveTypeId, Integer year);
}