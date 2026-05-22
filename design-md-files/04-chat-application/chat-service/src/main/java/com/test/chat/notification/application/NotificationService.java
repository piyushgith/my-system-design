package com.test.chat.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NotificationService {

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	public void notifyOfflineUser(UUID userId, UUID conversationId, String preview) {
		// MVP stub: production would call FCM/APNs via Firebase Admin SDK.
		log.info("Push notification queued for user={} conversation={} preview='{}'", userId, conversationId, preview);
	}
}
