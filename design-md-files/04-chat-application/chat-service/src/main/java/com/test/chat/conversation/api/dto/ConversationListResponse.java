package com.test.chat.conversation.api.dto;

import com.test.chat.conversation.domain.ConversationType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationListResponse(
		List<ConversationSummary> conversations,
		boolean hasMore
) {
	public record ConversationSummary(
			UUID conversationId,
			ConversationType type,
			String name,
			Instant lastMessageAt,
			int memberCount
	) {
	}
}
