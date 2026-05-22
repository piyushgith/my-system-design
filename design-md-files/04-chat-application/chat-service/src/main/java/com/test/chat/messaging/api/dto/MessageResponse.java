package com.test.chat.messaging.api.dto;

import com.test.chat.messaging.domain.MessageStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
		UUID messageId,
		long sequenceNum,
		UUID senderId,
		String contentType,
		String content,
		Instant sentAt,
		Instant serverReceivedAt,
		MessageStatus status
) {
}
