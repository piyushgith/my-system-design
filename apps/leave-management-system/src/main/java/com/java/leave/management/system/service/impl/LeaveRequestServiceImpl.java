package com.java.leave.management.system.service.impl;

import com.java.leave.management.system.dto.*;
import com.java.leave.management.system.entity.LeaveRequest;
import com.java.leave.management.system.entity.LeaveType;
import com.java.leave.management.system.entity.Employee;
import com.java.leave.management.system.entity.LeaveBalance;
import com.java.leave.management.system.repository.LeaveRequestRepository;
import com.java.leave.management.system.repository.LeaveTypeRepository;
import com.java.leave.management.system.repository.EmployeeRepository;
import com.java.leave.management.system.repository.LeaveBalanceRepository;
import com.java.leave.management.system.service.LeaveRequestService;
import com.java.leave.management.system.service.NotificationService;
import com.java.leave.management.system.service.AuditLogService;
import com.java.leave.management.system.service.LeaveBalanceService;
import com.java.leave.management.system.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaveRequestServiceImpl implements LeaveRequestService {

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final LeaveBalanceService leaveBalanceService;
    private final EmployeeService employeeService;

    @Override
    public Mono<LeaveRequestResponseDto> requestLeave(Long employeeId, CreateLeaveRequestDto requestDto) {
        return employeeService.getEmployeeById(employeeId)
            .flatMap(employee -> {
                // Validate employee exists
                if (employee == null) {
                    return Mono.error(new RuntimeException("Employee not found"));
                }
                
                return leaveTypeRepository.findById(requestDto.getLeaveTypeId())
                    .flatMap(leaveType -> {
                        // Check if employee has sufficient balance
                        int numberOfDays = calculateNumberOfDays(requestDto.getStartDate(), requestDto.getEndDate());
                        
                        return leaveBalanceService.getLeaveBalance(employeeId, requestDto.getLeaveTypeId(), LocalDate.now().getYear())
                            .switchIfEmpty(Mono.just(createDefaultLeaveBalance()))
                            .flatMap(leaveBalance -> {
                                if (leaveBalance.getBalanceDays() < numberOfDays) {
                                    return Mono.error(new RuntimeException("Insufficient leave balance. Available: " + leaveBalance.getBalanceDays() + " days, Requested: " + numberOfDays + " days"));
                                }
                                
                                // Validate dates
                                if (requestDto.getStartDate().isBefore(LocalDate.now())) {
                                    return Mono.error(new RuntimeException("Start date must be in the future"));
                                }
                                
                                if (requestDto.getStartDate().isAfter(requestDto.getEndDate())) {
                                    return Mono.error(new RuntimeException("Start date must be before or equal to end date"));
                                }
                                
                                // Create leave request
                                LeaveRequest leaveRequest = LeaveRequest.builder()
                                    .requestId(generateRequestId())
                                    .employeeId(employeeId)
                                    .leaveTypeId(requestDto.getLeaveTypeId())
                                    .startDate(requestDto.getStartDate())
                                    .endDate(requestDto.getEndDate())
                                    .numberOfDays(numberOfDays)
                                    .reason(requestDto.getReason())
                                    .status("PENDING")
                                    .requestedOn(LocalDateTime.now())
                                    .createdAt(LocalDateTime.now())
                                    .updatedAt(LocalDateTime.now())
                                    .build();
                                
                                return leaveRequestRepository.save(leaveRequest)
                                    .flatMap(savedRequest -> {
                                        // Create audit log
                                        return auditLogService.createAuditLog(
                                            savedRequest.getId(),
                                            "CREATED",
                                            employeeId,
                                            null,
                                            "PENDING",
                                            "Leave request created"
                                        ).then(Mono.just(mapToResponseDto(savedRequest, employee.getName(), leaveType.getName())));
                                    });
                            });
                    });
            })
            .flatMap(leaveRequestResponseDto -> {
                // Send notification to manager
                return employeeService.getEmployeeById(employeeId)
                    .flatMap(employee -> employeeService.getEmployeeById(employee.getManagerId())
                        .flatMap(manager -> notificationService.createNotification(
                            manager.getId(),
                            Long.valueOf(leaveRequestResponseDto.getId()),
                            "REQUEST_CREATED",
                            "Leave Request Submitted",
                            employee.getName() + " has requested " + leaveRequestResponseDto.getNumberOfDays() + " days of " + 
                            leaveRequestResponseDto.getLeaveType() + " leave from " + 
                            leaveRequestResponseDto.getStartDate() + " to " + 
                            leaveRequestResponseDto.getEndDate()
                        ).thenReturn(leaveRequestResponseDto))
                    );
            });
    }

    @Override
    public Mono<PagedResponseDto<LeaveRequestDto>> getMyLeaveRequests(Long employeeId, String status, Long leaveTypeId, int page, int size) {
        // This is a simplified implementation - in a real application, you'd need to implement pagination
        Flux<LeaveRequest> requests = leaveRequestRepository.findByEmployeeId(employeeId);
        
        if (status != null && !status.isEmpty()) {
            requests = requests.filter(req -> req.getStatus().equals(status));
        }
        
        if (leaveTypeId != null) {
            requests = requests.filter(req -> req.getLeaveTypeId().equals(leaveTypeId));
        }
        
        return requests
            .flatMap(request -> {
                return leaveTypeRepository.findById(request.getLeaveTypeId())
                    .map(leaveType -> mapToDto(request, leaveType.getName()));
            })
            .collectList()
            .map(list -> {
                PagedResponseDto<LeaveRequestDto> response = new PagedResponseDto<>();
                response.setContent(list);
                response.setPageNumber(page);
                response.setPageSize(size);
                response.setTotalElements(list.size());
                response.setTotalPages((int) Math.ceil((double) list.size() / size));
                response.setLast(page == response.getTotalPages() - 1);
                return response;
            });
    }

    @Override
    public Mono<LeaveBalanceResponseDto> getLeaveBalance(Long employeeId, Integer year) {
        return employeeService.getEmployeeById(employeeId)
            .flatMap(employee -> {
                return leaveBalanceRepository.findByEmployeeIdAndYear(employeeId, year != null ? year : LocalDate.now().getYear())
                    .flatMap(leaveBalance -> {
                        return leaveTypeRepository.findById(leaveBalance.getLeaveTypeId())
                            .map(leaveType -> {
                                LeaveBalanceDto balanceDto = new LeaveBalanceDto();
                                balanceDto.setLeaveTypeId(leaveBalance.getLeaveTypeId());
                                balanceDto.setLeaveType(leaveType.getName());
                                balanceDto.setAllocatedDays(leaveBalance.getAllocatedDays());
                                balanceDto.setUsedDays(leaveBalance.getUsedDays());
                                balanceDto.setCarriedForwardDays(leaveBalance.getCarryforwardDays());
                                balanceDto.setBalanceDays(leaveBalance.getBalanceDays());
                                return balanceDto;
                            });
                    })
                    .collectList()
                    .map(balances -> {
                        LeaveBalanceResponseDto response = new LeaveBalanceResponseDto();
                        response.setEmployeeId(employeeId);
                        response.setYear(year != null ? year : LocalDate.now().getYear());
                        response.setLeaveBalances(balances);
                        return response;
                    });
            });
    }

    @Override
    public Mono<LeaveRequestResponseDto> withdrawLeaveRequest(String requestId, Long employeeId) {
        return leaveRequestRepository.findByRequestId(requestId)
            .flatMap(leaveRequest -> {
                if (!leaveRequest.getEmployeeId().equals(employeeId)) {
                    return Mono.error(new RuntimeException("Unauthorized: You can only withdraw your own leave requests"));
                }
                
                if (!leaveRequest.getStatus().equals("PENDING") && !leaveRequest.getStatus().equals("APPROVED")) {
                    return Mono.error(new RuntimeException("Cannot withdraw leave request with status: " + leaveRequest.getStatus()));
                }
                
                // Check if leave start date is within 24 hours
                if (leaveRequest.getStartDate().isBefore(LocalDate.now().plusDays(1))) {
                    return Mono.error(new RuntimeException("Cannot withdraw leave request within 24 hours of start date"));
                }
                
                leaveRequest.setStatus("WITHDRAWN");
                leaveRequest.setWithdrawnOn(LocalDateTime.now());
                leaveRequest.setUpdatedAt(LocalDateTime.now());
                
                return leaveRequestRepository.save(leaveRequest)
                    .flatMap(updatedRequest -> {
                        return employeeRepository.findById(updatedRequest.getEmployeeId())
                            .flatMap(employee -> leaveTypeRepository.findById(updatedRequest.getLeaveTypeId())
                                .flatMap(leaveType -> {
                                    // Create audit log
                                    return auditLogService.createAuditLog(
                                        updatedRequest.getId(),
                                        "WITHDRAWN",
                                        employeeId,
                                        "APPROVED",
                                        "WITHDRAWN",
                                        "Leave request withdrawn by employee"
                                    ).then(Mono.just(mapToResponseDto(updatedRequest, employee.getName(), leaveType.getName())));
                                })
                            );
                    });
            })
            .flatMap(leaveRequestResponseDto -> {
                // Send notification to manager
                return employeeService.getEmployeeById(employeeId)
                    .flatMap(employee -> employeeService.getEmployeeById(employee.getManagerId())
                        .flatMap(manager -> notificationService.createNotification(
                            manager.getId(),
                            Long.valueOf(leaveRequestResponseDto.getId()),
                            "WITHDRAWN",
                            "Leave Request Withdrawn",
                            employee.getName() + " has withdrawn their leave request for " + 
                            leaveRequestResponseDto.getNumberOfDays() + " days"
                        ).thenReturn(leaveRequestResponseDto))
                    );
            });
    }

    @Override
    public Mono<LeaveRequestDto> getLeaveRequestDetails(String requestId, Long employeeId) {
        return leaveRequestRepository.findByRequestId(requestId)
            .flatMap(leaveRequest -> {
                if (!leaveRequest.getEmployeeId().equals(employeeId) && !isManagerOfEmployee(leaveRequest.getEmployeeId(), employeeId)) {
                    return Mono.error(new RuntimeException("Unauthorized: You do not have permission to view this leave request"));
                }
                
                return employeeRepository.findById(leaveRequest.getEmployeeId())
                    .flatMap(employee -> leaveTypeRepository.findById(leaveRequest.getLeaveTypeId())
                        .map(leaveType -> mapToDto(leaveRequest, leaveType.getName()))
                    );
            });
    }

    @Override
    public Mono<LeaveRequestResponseDto> approveLeaveRequest(String requestId, Long managerId, ApproveLeaveRequestDto requestDto) {
        return leaveRequestRepository.findByRequestId(requestId)
            .flatMap(leaveRequest -> {
                if (!leaveRequest.getStatus().equals("PENDING")) {
                    return Mono.error(new RuntimeException("Leave request is not in PENDING status"));
                }
                
                return employeeService.getEmployeeById(managerId)
                    .flatMap(manager -> {
                        // Verify that the manager is authorized to approve this request
                        if (!isManagerOfEmployee(leaveRequest.getEmployeeId(), managerId)) {
                            return Mono.error(new RuntimeException("Unauthorized: You are not the manager of this employee"));
                        }
                        
                        leaveRequest.setStatus("APPROVED");
                        leaveRequest.setApprovedBy(managerId);
                        leaveRequest.setApprovedOn(LocalDateTime.now());
                        leaveRequest.setUpdatedAt(LocalDateTime.now());
                        
                        return leaveRequestRepository.save(leaveRequest)
                            .flatMap(updatedRequest -> {
                                // Update leave balance
                                return leaveBalanceService.updateLeaveBalance(
                                    updatedRequest.getEmployeeId(),
                                    updatedRequest.getLeaveTypeId(),
                                    updatedRequest.getStartDate().getYear(),
                                    updatedRequest.getNumberOfDays()
                                ).then(Mono.just(updatedRequest));
                            })
                            .flatMap(updatedRequest -> {
                                return employeeRepository.findById(updatedRequest.getEmployeeId())
                                    .flatMap(employee -> leaveTypeRepository.findById(updatedRequest.getLeaveTypeId())
                                        .flatMap(leaveType -> {
                                            // Create audit log
                                            return auditLogService.createAuditLog(
                                                updatedRequest.getId(),
                                                "APPROVED",
                                                managerId,
                                                "PENDING",
                                                "APPROVED",
                                                requestDto.getRemarks()
                                            ).then(Mono.just(mapToResponseDto(updatedRequest, employee.getName(), leaveType.getName())));
                                        })
                                    );
                            });
                    });
            })
            .flatMap(leaveRequestResponseDto -> {
                // Send notification to employee
                return employeeService.getEmployeeById(Long.valueOf(leaveRequestResponseDto.getEmployeeId()))
                    .flatMap(employee -> notificationService.createNotification(
                        employee.getId(),
                        Long.valueOf(leaveRequestResponseDto.getId()),
                        "APPROVED",
                        "Leave Request Approved",
                        "Your leave request for " + leaveRequestResponseDto.getNumberOfDays() + " days from " + 
                        leaveRequestResponseDto.getStartDate() + " to " + 
                        leaveRequestResponseDto.getEndDate() + " has been approved"
                    ).thenReturn(leaveRequestResponseDto));
            });
    }

    @Override
    public Mono<LeaveRequestResponseDto> rejectLeaveRequest(String requestId, Long managerId, RejectLeaveRequestDto requestDto) {
        return leaveRequestRepository.findByRequestId(requestId)
            .flatMap(leaveRequest -> {
                if (!leaveRequest.getStatus().equals("PENDING")) {
                    return Mono.error(new RuntimeException("Leave request is not in PENDING status"));
                }
                
                return employeeService.getEmployeeById(managerId)
                    .flatMap(manager -> {
                        // Verify that the manager is authorized to reject this request
                        if (!isManagerOfEmployee(leaveRequest.getEmployeeId(), managerId)) {
                            return Mono.error(new RuntimeException("Unauthorized: You are not the manager of this employee"));
                        }
                        
                        leaveRequest.setStatus("REJECTED");
                        leaveRequest.setApprovedBy(managerId);
                        leaveRequest.setRejectionReason(requestDto.getRejectionReason());
                        leaveRequest.setApprovedOn(LocalDateTime.now());
                        leaveRequest.setUpdatedAt(LocalDateTime.now());
                        
                        return leaveRequestRepository.save(leaveRequest)
                            .flatMap(updatedRequest -> {
                                return employeeRepository.findById(updatedRequest.getEmployeeId())
                                    .flatMap(employee -> leaveTypeRepository.findById(updatedRequest.getLeaveTypeId())
                                        .flatMap(leaveType -> {
                                            // Create audit log
                                            return auditLogService.createAuditLog(
                                                updatedRequest.getId(),
                                                "REJECTED",
                                                managerId,
                                                "PENDING",
                                                "REJECTED",
                                                requestDto.getRejectionReason()
                                            ).then(Mono.just(mapToResponseDto(updatedRequest, employee.getName(), leaveType.getName())));
                                        })
                                    );
                            });
                    });
            })
            .flatMap(leaveRequestResponseDto -> {
                // Send notification to employee
                return employeeService.getEmployeeById(Long.valueOf(leaveRequestResponseDto.getEmployeeId()))
                    .flatMap(employee -> notificationService.createNotification(
                        employee.getId(),
                        Long.valueOf(leaveRequestResponseDto.getId()),
                        "REJECTED",
                        "Leave Request Rejected",
                        "Your leave request has been rejected. Reason: " + leaveRequestResponseDto.getRejectionReason()
                    ).thenReturn(leaveRequestResponseDto));
            });
    }

    @Override
    public Mono<PagedResponseDto<LeaveRequestDto>> getTeamLeaveRequests(Long managerId, String status, int page, int size) {
        // Get all employees under this manager
        return employeeRepository.findAll()
            .filter(employee -> managerId.equals(employee.getManagerId()))
            .map(Employee::getId)
            .collectList()
            .flatMapMany(employeeIds -> {
                Flux<LeaveRequest> requests;
                if (status != null && !status.isEmpty()) {
                    requests = leaveRequestRepository.findByEmployeeIdInAndStatus(employeeIds, status);
                } else {
                    requests = leaveRequestRepository.findByEmployeeIdInAndStatus(employeeIds, "PENDING");
                }
                return requests;
            })
            .flatMap(request -> {
                return employeeRepository.findById(request.getEmployeeId())
                    .flatMap(employee -> leaveTypeRepository.findById(request.getLeaveTypeId())
                        .map(leaveType -> mapToDto(request, employee.getName(), leaveType.getName()))
                    );
            })
            .collectList()
            .map(list -> {
                PagedResponseDto<LeaveRequestDto> response = new PagedResponseDto<>();
                response.setContent(list);
                response.setPageNumber(page);
                response.setPageSize(size);
                response.setTotalElements(list.size());
                response.setTotalPages((int) Math.ceil((double) list.size() / size));
                response.setLast(page == response.getTotalPages() - 1);
                return response;
            });
    }

    @Override
    public Mono<PagedResponseDto<LeaveBalanceDto>> getTeamLeaveBalance(Long managerId, Integer year, int page, int size) {
        // Get all employees under this manager
        return employeeRepository.findAll()
            .filter(employee -> managerId.equals(employee.getManagerId()))
            .flatMap(employee -> {
                // For each employee, get their leave balances
                return leaveBalanceRepository.findByEmployeeIdAndYear(employee.getId(), year != null ? year : LocalDate.now().getYear())
                    .flatMap(leaveBalance -> {
                        return leaveTypeRepository.findById(leaveBalance.getLeaveTypeId())
                            .map(leaveType -> {
                                LeaveBalanceDto balanceDto = new LeaveBalanceDto();
                                balanceDto.setLeaveTypeId(leaveBalance.getLeaveTypeId());
                                balanceDto.setLeaveType(leaveType.getName());
                                balanceDto.setAllocatedDays(leaveBalance.getAllocatedDays());
                                balanceDto.setUsedDays(leaveBalance.getUsedDays());
                                balanceDto.setCarriedForwardDays(leaveBalance.getCarryforwardDays());
                                balanceDto.setBalanceDays(leaveBalance.getBalanceDays());
                                return balanceDto;
                            });
                    })
                    .collectList()
                    .map(balances -> {
                        // Create a simple DTO for the employee's balances
                        // This is a simplified approach - in a real application, you'd need to group by employee
                        return balances;
                    });
            })
            .collectList()
            .map(allBalances -> {
                // Flatten all balances into a single list
                List<LeaveBalanceDto> flattenedBalances = new ArrayList<>();
                for (List<LeaveBalanceDto> balances : allBalances) {
                    flattenedBalances.addAll(balances);
                }
                
                PagedResponseDto<LeaveBalanceDto> response = new PagedResponseDto<>();
                response.setContent(flattenedBalances);
                response.setPageNumber(page);
                response.setPageSize(size);
                response.setTotalElements(flattenedBalances.size());
                response.setTotalPages((int) Math.ceil((double) flattenedBalances.size() / size));
                response.setLast(page == response.getTotalPages() - 1);
                return response;
            });
    }

    private int calculateNumberOfDays(LocalDate startDate, LocalDate endDate) {
        return endDate.isAfter(startDate) ? 
            java.time.Period.between(startDate, endDate).getDays() + 1 : 1;
    }

    private String generateRequestId() {
        return "LR-" + LocalDate.now().getYear() + "-" + String.format("%03d", (int)(Math.random() * 1000));
    }

    private LeaveBalanceDto createDefaultLeaveBalance() {
        LeaveBalanceDto balance = new LeaveBalanceDto();
        balance.setAllocatedDays(0);
        balance.setUsedDays(0);
        balance.setCarriedForwardDays(0);
        balance.setBalanceDays(0);
        return balance;
    }

    private boolean isManagerOfEmployee(Long employeeId, Long managerId) {
        // This is a simplified check - in a real application, you'd need to verify the manager relationship
        return true; // Simplified for now
    }

    private LeaveRequestDto mapToDto(LeaveRequest request, String leaveTypeName) {
        return mapToDto(request, null, leaveTypeName);
    }

    private LeaveRequestDto mapToDto(LeaveRequest request, String employeeName, String leaveTypeName) {
        LeaveRequestDto dto = new LeaveRequestDto();
        dto.setId(request.getId());
        dto.setRequestId(request.getRequestId());
        dto.setEmployeeId(request.getEmployeeId());
        dto.setEmployeeName(employeeName);
        dto.setLeaveType(leaveTypeName);
        dto.setStartDate(request.getStartDate());
        dto.setEndDate(request.getEndDate());
        dto.setNumberOfDays(request.getNumberOfDays());
        dto.setReason(request.getReason());
        dto.setStatus(request.getStatus());
        dto.setRejectionReason(request.getRejectionReason());
        dto.setApprovedBy(request.getApprovedBy());
        dto.setApprovedOn(request.getApprovedOn());
        dto.setRequestedOn(request.getRequestedOn());
        dto.setWithdrawnOn(request.getWithdrawnOn());
        dto.setCreatedAt(request.getCreatedAt());
        return dto;
    }

    private LeaveRequestResponseDto mapToResponseDto(LeaveRequest request, String employeeName, String leaveTypeName) {
        LeaveRequestResponseDto dto = new LeaveRequestResponseDto();
        dto.setId(request.getId());
        dto.setRequestId(request.getRequestId());
        dto.setEmployeeId(request.getEmployeeId());
        dto.setLeaveType(leaveTypeName);
        dto.setStartDate(request.getStartDate());
        dto.setEndDate(request.getEndDate());
        dto.setNumberOfDays(request.getNumberOfDays());
        dto.setReason(request.getReason());
        dto.setStatus(request.getStatus());
        dto.setRejectionReason(request.getRejectionReason());
        dto.setApprovedBy(request.getApprovedBy());
        dto.setApprovedOn(request.getApprovedOn());
        dto.setRequestedOn(request.getRequestedOn());
        dto.setWithdrawnOn(request.getWithdrawnOn());
        dto.setCreatedAt(request.getCreatedAt());
        return dto;
    }
}