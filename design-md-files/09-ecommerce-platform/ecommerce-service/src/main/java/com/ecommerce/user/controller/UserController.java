package com.ecommerce.user.controller;

import com.ecommerce.common.security.AuthenticatedUser;
import com.ecommerce.user.service.UserService;
import com.ecommerce.user.service.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getProfile(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(userService.getProfile(principal.userId()));
    }
}
