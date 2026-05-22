package com.test.chat.messaging.api.dto;

import java.util.List;

public record MessageHistoryResponse(
		List<MessageResponse> messages,
		boolean hasMore,
		Long oldestSeq
) {
}
