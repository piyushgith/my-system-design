package com.test.notification.dispatcher;

import java.util.UUID;

public interface UserEmailResolver {
    String resolveEmail(UUID userId);
}
