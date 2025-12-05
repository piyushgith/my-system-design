package com.java.leave.management.system.service.impl;

import com.java.leave.management.system.dto.CreateLeaveTypeDto;
import com.java.leave.management.system.dto.LeaveTypeDto;
import com.java.leave.management.system.entity.LeaveType;
import com.java.leave.management.system.repository.LeaveTypeRepository;
import com.java.leave.management.system.service.LeaveTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class LeaveTypeServiceImpl implements LeaveTypeService {

    private final LeaveTypeRepository leaveTypeRepository;

    @Override
    public Mono<LeaveTypeDto> createLeaveType(CreateLeaveTypeDto requestDto) {
        LeaveType leaveType = LeaveType.builder()
            .name(requestDto.getName().toUpperCase())
            .description(requestDto.getDescription())
            .annualAllocation(requestDto.getAnnualAllocation())
            .carryforwardLimit(requestDto.getCarryforwardLimit())
            .isActive(true)
            .build();
        
        return leaveTypeRepository.save(leaveType)
            .map(this::mapToDto);
    }

    @Override
    public Mono<LeaveTypeDto> getLeaveTypeById(Long id) {
        return leaveTypeRepository.findById(id)
            .map(this::mapToDto);
    }

    @Override
    public Mono<LeaveTypeDto> getLeaveTypeByName(String name) {
        return leaveTypeRepository.findByName(name)
            .map(this::mapToDto);
    }

    @Override
    public reactor.core.publisher.Flux<LeaveTypeDto> getAllLeaveTypes() {
        return leaveTypeRepository.findAll()
            .map(this::mapToDto);
    }

    private LeaveTypeDto mapToDto(LeaveType leaveType) {
        LeaveTypeDto dto = new LeaveTypeDto();
        dto.setId(leaveType.getId());
        dto.setName(leaveType.getName());
        dto.setDescription(leaveType.getDescription());
        dto.setAnnualAllocation(leaveType.getAnnualAllocation());
        dto.setCarryforwardLimit(leaveType.getCarryforwardLimit());
        dto.setIsActive(leaveType.getIsActive());
        dto.setCreatedAt(leaveType.getCreatedAt());
        return dto;
    }
}