package com.test.ride.sharing.service.notification;

import java.util.UUID;

public interface NotificationStrategy {

    String name();

    void sendPush(UUID userId, String title, String body, String deepLink);
}
