package com.test.chat.conversation.api.dto;

import com.test.chat.conversation.domain.ConversationType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateConversationRequest(
		@NotNull ConversationType type,
		@Size(max = 255) String name,
		@NotEmpty List<UUID> memberIds
) {
}
