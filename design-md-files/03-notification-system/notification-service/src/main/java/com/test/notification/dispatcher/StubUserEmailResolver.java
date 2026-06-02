package com.test.notification.dispatcher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.user-resolver", havingValue = "stub", matchIfMissing = true)
public class StubUserEmailResolver implements UserEmailResolver {

    @Override
    public String resolveEmail(UUID userId) {
        log.warn("StubUserEmailResolver in use — replace with real UserService call before production. userId={}", userId);
        return "user-" + userId + "@example.com";
    }
}
