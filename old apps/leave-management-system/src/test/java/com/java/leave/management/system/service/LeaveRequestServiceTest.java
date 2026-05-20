package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.CreateLeaveRequestDto;
import com.java.leave.management.system.dto.LeaveRequestResponseDto;
import com.java.leave.management.system.entity.Employee;
import com.java.leave.management.system.entity.LeaveType;
import com.java.leave.management.system.repository.EmployeeRepository;
import com.java.leave.management.system.repository.LeaveTypeRepository;
import com.java.leave.management.system.service.impl.LeaveRequestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaveRequestServiceTest {

    @Mock
    private EmployeeRepository employeeRepository;
    
    @Mock
    private LeaveTypeRepository leaveTypeRepository;
    
    // Add other mocked repositories as needed
    
    private LeaveRequestServiceImpl leaveRequestService;

    @BeforeEach
    void setUp() {
        // Initialize the service with mocked dependencies
        // Note: This is a simplified example since the full service has many dependencies
        leaveRequestService = new LeaveRequestServiceImpl(
            null, // leaveRequestRepository
            leaveTypeRepository,
            employeeRepository,
            null, // leaveBalanceRepository
            null, // notificationService
            null, // auditLogService
            null, // leaveBalanceService
            null  // employeeService
        );
    }

    @Test
    void testRequestLeave() {
        // Given
        Long employeeId = 1L;
        CreateLeaveRequestDto requestDto = new CreateLeaveRequestDto();
        requestDto.setLeaveTypeId(1L);
        requestDto.setStartDate(LocalDate.now().plusDays(5));
        requestDto.setEndDate(LocalDate.now().plusDays(7));
        requestDto.setReason("Personal reasons");

        Employee employee = Employee.builder()
            .id(employeeId)
            .name("John Doe")
            .email("john.doe@example.com")
            .build();

        LeaveType leaveType = LeaveType.builder()
            .id(1L)
            .name("PERSONAL")
            .build();

        when(employeeRepository.findById(employeeId)).thenReturn(Mono.just(employee));
        when(leaveTypeRepository.findById(1L)).thenReturn(Mono.just(leaveType));

        // When
        Mono<LeaveRequestResponseDto> result = leaveRequestService.requestLeave(employeeId, requestDto);

        // Then
        StepVerifier.create(result)
            .expectNextMatches(response -> response.getEmployeeId().equals(employeeId))
            .verifyComplete();
    }
}