package com.java.leave.management.system.service.impl;


import com.java.leave.management.system.dto.UsersDto;
import com.java.leave.management.system.entity.User;
import com.java.leave.management.system.repository.UserRepository;
import com.java.leave.management.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final PasswordEncoder passwordEncoder;

    private final UserRepository userRepository;


    public Mono<UsersDto> createUser(UsersDto usersDto) {
        // Encode the password before saving
        String encodedPassword = passwordEncoder.encode(usersDto.getPassword());
        usersDto.setPassword(encodedPassword);
        // Save the user to the repository Convert back to DTO and return
        return userRepository.save(toEntity(usersDto))
                .map(this::fromEntity);
    }

    private User toEntity(UsersDto usersDto) {
        return User.builder()
                //.id(UUID.randomUUID().toString())
                .username(usersDto.getUsername())
                .password(usersDto.getPassword())
                .email(usersDto.getEmail())
                .active(usersDto.isActive())
                .roles(usersDto.getRoles())
                .build();
    }

    public UsersDto fromEntity(User userEntity) {
        return UsersDto.builder()
                .id(userEntity.getId())
                .username(userEntity.getUsername())
                .password(userEntity.getPassword())
                .email(userEntity.getEmail())
                .active(userEntity.isActive())
                .roles(userEntity.getRoles())
                .build();
    }
}
