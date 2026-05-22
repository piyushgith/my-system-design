package com.test.chat.messaging.api;

import com.test.chat.messaging.api.dto.MessageHistoryResponse;
import com.test.chat.messaging.api.dto.MessageResponse;
import com.test.chat.messaging.api.dto.SendMessageRequest;
import com.test.chat.messaging.application.MessageService;
import com.test.chat.messaging.domain.Message;
import com.test.chat.shared.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public class MessageController {

	private final MessageService messageService;

	public MessageController(MessageService messageService) {
		this.messageService = messageService;
	}

	@GetMapping
	public MessageHistoryResponse history(
			@PathVariable UUID conversationId,
			@RequestParam(required = false) Long beforeSeq,
			@RequestParam(defaultValue = "50") int limit) {
		UUID userId = AuthContext.currentUserId();
		List<Message> messages = messageService.getHistory(userId, conversationId, beforeSeq, limit);
		List<MessageResponse> responses = messages.stream().map(this::toResponse).toList();
		Long oldestSeq = messages.isEmpty() ? beforeSeq : messages.getLast().getSequenceNum();
		return new MessageHistoryResponse(responses, messages.size() == limit, oldestSeq);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public MessageResponse send(
			@PathVariable UUID conversationId,
			@Valid @RequestBody SendMessageRequest request) {
		UUID userId = AuthContext.currentUserId();
		Message message = messageService.sendMessage(
				userId, conversationId, request.contentType(), request.content(), request.idempotencyKey());
		return toResponse(message);
	}

	private MessageResponse toResponse(Message message) {
		return new MessageResponse(
				message.getMessageId(),
				message.getSequenceNum(),
				message.getSenderId(),
				message.getContentType(),
				message.getContent(),
				message.getSentAt(),
				message.getServerReceivedAt(),
				message.getStatus());
	}
}
