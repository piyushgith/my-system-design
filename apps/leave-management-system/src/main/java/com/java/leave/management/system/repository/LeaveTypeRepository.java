package com.java.leave.management.system.repository;

import com.java.leave.management.system.entity.LeaveType;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface LeaveTypeRepository extends ReactiveCrudRepository<LeaveType, Long> {
    Mono<LeaveType> findByName(String name);
}