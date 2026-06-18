package com.test.ride.sharing.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockFcmNotificationStrategy implements NotificationStrategy {

    private static final Logger log = LoggerFactory.getLogger(MockFcmNotificationStrategy.class);

    @Override
    public String name() {
        return "mock-fcm";
    }

    @Override
    public void sendPush(UUID userId, String title, String body, String deepLink) {
        log.info("[mock-fcm] userId={} title='{}' body='{}' link={}", userId, title, body, deepLink);
    }
}
