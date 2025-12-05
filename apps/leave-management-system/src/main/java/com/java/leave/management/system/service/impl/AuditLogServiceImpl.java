package com.java.leave.management.system.service.impl;

import com.java.leave.management.system.dto.AuditLogDto;
import com.java.leave.management.system.entity.AuditLog;
import com.java.leave.management.system.repository.AuditLogRepository;
import com.java.leave.management.system.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Override
    public Mono<AuditLogDto> createAuditLog(Long leaveRequestId, String action, Long performedBy, String oldStatus, String newStatus, String remarks) {
        AuditLog auditLog = AuditLog.builder()
            .leaveRequestId(leaveRequestId)
            .action(action)
            .performedBy(performedBy)
            .oldStatus(oldStatus)
            .newStatus(newStatus)
            .remarks(remarks)
            .createdAt(LocalDateTime.now())
            .build();
        
        return auditLogRepository.save(auditLog)
            .map(this::mapToDto);
    }

    @Override
    public Flux<AuditLogDto> getAuditLogsByLeaveRequestId(Long leaveRequestId, int page, int size) {
        return auditLogRepository.findByLeaveRequestId(leaveRequestId)
            .map(this::mapToDto);
    }

    @Override
    public Flux<AuditLogDto> getAuditLogsByPerformerId(Long performedBy, int page, int size) {
        return auditLogRepository.findByPerformedBy(performedBy)
            .map(this::mapToDto);
    }

    private AuditLogDto mapToDto(AuditLog auditLog) {
        AuditLogDto dto = new AuditLogDto();
        dto.setId(auditLog.getId());
        dto.setLeaveRequestId(auditLog.getLeaveRequestId());
        dto.setAction(auditLog.getAction());
        dto.setPerformedBy(auditLog.getPerformedBy());
        // In a real application, you would fetch the performer's name from the employee repository
        dto.setPerformedByName("Performer Name"); // Placeholder
        dto.setOldStatus(auditLog.getOldStatus());
        dto.setNewStatus(auditLog.getNewStatus());
        dto.setRemarks(auditLog.getRemarks());
        dto.setCreatedAt(auditLog.getCreatedAt());
        return dto;
    }
}