package com.test.chat.messaging.application;

import java.time.Instant;
import java.util.UUID;

public record MessageCreatedEvent(
		UUID messageId,
		UUID conversationId,
		UUID senderId,
		long sequenceNum,
		String contentType,
		String content,
		Instant sentAt,
		Instant serverReceivedAt
) {
}
