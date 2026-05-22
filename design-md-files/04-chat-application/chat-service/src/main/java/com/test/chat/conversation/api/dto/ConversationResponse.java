package com.test.chat.conversation.api.dto;

import com.test.chat.conversation.domain.ConversationType;
import com.test.chat.conversation.domain.MemberRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ConversationResponse(
		UUID conversationId,
		ConversationType type,
		String name,
		Instant createdAt,
		List<MemberResponse> members
) {
	public record MemberResponse(UUID userId, MemberRole role, Instant joinedAt) {
	}
}
