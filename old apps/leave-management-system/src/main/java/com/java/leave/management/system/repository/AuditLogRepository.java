package com.java.leave.management.system.repository;

import com.java.leave.management.system.entity.AuditLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface AuditLogRepository extends ReactiveCrudRepository<AuditLog, Long> {
    Flux<AuditLog> findByLeaveRequestId(Long leaveRequestId);
    Flux<AuditLog> findByPerformedBy(Long performedBy);
}