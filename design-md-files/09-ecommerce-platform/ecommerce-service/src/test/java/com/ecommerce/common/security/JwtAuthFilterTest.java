package com.ecommerce.common.security;

import com.ecommerce.user.domain.User;
import com.ecommerce.user.domain.UserRole;
import com.ecommerce.user.domain.UserStatus;
import com.ecommerce.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock JwtService jwtService;
    @Mock UserRepository userRepository;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token-123");
        return request;
    }

    private User user(UserStatus status, UserRole role) {
        return User.builder().id(UUID.randomUUID()).name("A").email("a@b.com")
                .passwordHash("h").role(role).status(status).build();
    }

    @Test
    void authenticatesActiveUserUsingDbRole() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userRepository);
        User user = user(UserStatus.ACTIVE, UserRole.ADMIN);
        when(jwtService.extractUser("token-123"))
                .thenReturn(new AuthenticatedUser(user.getId(), "CUSTOMER"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        // DB role (ADMIN) wins over the stale token claim (CUSTOMER)
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void rejectsSuspendedUser() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userRepository);
        User user = user(UserStatus.SUSPENDED, UserRole.CUSTOMER);
        when(jwtService.extractUser("token-123"))
                .thenReturn(new AuthenticatedUser(user.getId(), "CUSTOMER"));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }
}
