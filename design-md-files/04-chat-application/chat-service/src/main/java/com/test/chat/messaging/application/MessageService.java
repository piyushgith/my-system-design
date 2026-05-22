package com.test.chat.messaging.application;

import com.test.chat.conversation.application.ConversationService;
import com.test.chat.conversation.domain.Conversation;
import com.test.chat.conversation.infrastructure.ConversationRepository;
import com.test.chat.messaging.domain.Message;
import com.test.chat.messaging.infrastructure.MessageRepository;
import com.test.chat.messaging.infrastructure.SequenceNumberService;
import com.test.chat.shared.error.ChatException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

	private static final int MAX_CONTENT_LENGTH = 4096;

	private final MessageRepository messageRepository;
	private final ConversationRepository conversationRepository;
	private final ConversationService conversationService;
	private final SequenceNumberService sequenceNumberService;
	private final ApplicationEventPublisher eventPublisher;

	public MessageService(
			MessageRepository messageRepository,
			ConversationRepository conversationRepository,
			ConversationService conversationService,
			SequenceNumberService sequenceNumberService,
			ApplicationEventPublisher eventPublisher) {
		this.messageRepository = messageRepository;
		this.conversationRepository = conversationRepository;
		this.conversationService = conversationService;
		this.sequenceNumberService = sequenceNumberService;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public Message sendMessage(UUID senderId, UUID conversationId, String contentType, String content, String idempotencyKey) {
		conversationService.ensureMember(conversationId, senderId);
		validateContent(content);

		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			var existing = messageRepository.findByConversationIdAndIdempotencyKey(conversationId, idempotencyKey);
			if (existing.isPresent()) {
				return existing.get();
			}
		}

		long sequenceNum = sequenceNumberService.nextSequence(conversationId);
		Message message = new Message(conversationId, senderId, sequenceNum, contentType, content, idempotencyKey);
		messageRepository.save(message);

		Conversation conversation = conversationRepository.findById(conversationId)
				.orElseThrow(() -> ChatException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found"));
		conversation.setLastMessageAt(Instant.now());

		eventPublisher.publishEvent(new MessageCreatedEvent(
				message.getMessageId(),
				conversationId,
				senderId,
				sequenceNum,
				contentType,
				content,
				message.getSentAt(),
				message.getServerReceivedAt()));

		return message;
	}

	@Transactional(readOnly = true)
	public List<Message> getHistory(UUID userId, UUID conversationId, Long beforeSeq, int limit) {
		conversationService.ensureMember(conversationId, userId);
		int pageSize = Math.min(Math.max(limit, 1), 100);
		return messageRepository.findHistory(conversationId, beforeSeq, PageRequest.of(0, pageSize));
	}

	private void validateContent(String content) {
		if (content == null || content.isBlank()) {
			throw ChatException.badRequest("EMPTY_MESSAGE", "Message content cannot be empty");
		}
		if (content.length() > MAX_CONTENT_LENGTH) {
			throw ChatException.badRequest("MESSAGE_TOO_LARGE", "Text messages cannot exceed 4096 characters");
		}
	}
}
