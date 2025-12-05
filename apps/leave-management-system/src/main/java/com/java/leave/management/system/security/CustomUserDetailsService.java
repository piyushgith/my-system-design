/*
package com.java.leave.management.system.security;

import com.java.leave.management.system.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements ReactiveUserDetailsService {

    private final EmployeeService employeeService;

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        // In a real application, you would fetch the employee by email/username
        // and return the appropriate UserDetails object
        return employeeService.getEmployeeByEmail(username)
                .map(employee -> User.builder()
                        .username(employee.getEmail())
                        .password("encoded_password") // In a real app, this would be the actual encoded password
                        .roles(employee.getRoleName())
                        .build())
                .cast(UserDetails.class);
    }
}*/
