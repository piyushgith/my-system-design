package com.java.leave.management.system.service;

import com.java.leave.management.system.dto.UsersDto;
import reactor.core.publisher.Mono;

public interface UserService {

    Mono<UsersDto> createUser(UsersDto usersDto);
}
