package com.java.leave.management.system.dto;

import com.java.leave.management.system.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsersDto {
    private String id;
    private String username;
    private String password;
    private String email;
    @Builder.Default
    private boolean active = true;
    private List<String> roles = new ArrayList<>();

}
