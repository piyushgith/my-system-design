package com.java.leave.management.system.repository;

import com.java.leave.management.system.entity.Employee;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface EmployeeRepository extends ReactiveCrudRepository<Employee, Long> {
    Mono<Employee> findByEmpId(String empId);
    Mono<Employee> findByEmail(String email);
}