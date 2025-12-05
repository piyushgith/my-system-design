package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.AuditLogDto;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface AuditLogService {
    Mono<AuditLogDto> createAuditLog(Long leaveRequestId, String action, Long performedBy, String oldStatus, String newStatus, String remarks);
    Flux<AuditLogDto> getAuditLogsByLeaveRequestId(Long leaveRequestId, int page, int size);
    Flux<AuditLogDto> getAuditLogsByPerformerId(Long performedBy, int page, int size);
}