package com.java.leave.management.system.repository;

import com.java.leave.management.system.entity.LeaveRequest;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.util.List;

@Repository
public interface LeaveRequestRepository extends ReactiveCrudRepository<LeaveRequest, Long> {
    Mono<LeaveRequest> findByRequestId(String requestId);
    Flux<LeaveRequest> findByEmployeeId(Long employeeId);
    Flux<LeaveRequest> findByEmployeeIdAndStatus(Long employeeId, String status);
    Flux<LeaveRequest> findByStatus(String status);
    Flux<LeaveRequest> findByEmployeeIdInAndStatus(List<Long> employeeIds, String status);
}