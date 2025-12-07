package com.java.leave.management.system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private boolean active = true;
    private List<String> roles = new ArrayList<>();
}
