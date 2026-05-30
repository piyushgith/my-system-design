package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.CreateLeaveTypeDto;
import com.java.leave.management.system.dto.LeaveTypeDto;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

public interface LeaveTypeService {
    Mono<LeaveTypeDto> createLeaveType(CreateLeaveTypeDto requestDto);
    Flux<LeaveTypeDto> getAllLeaveTypes();
    Mono<LeaveTypeDto> getLeaveTypeById(Long id);
    Mono<LeaveTypeDto> getLeaveTypeByName(String name);
}