package com.test.chat.messaging.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.chat.conversation.application.ConversationService;
import com.test.chat.messaging.ws.WebSocketSessionRegistry;
import com.test.chat.notification.application.NotificationService;
import com.test.chat.presence.application.PresenceService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MessageDeliveryListener {

	private final ConversationService conversationService;
	private final WebSocketSessionRegistry sessionRegistry;
	private final PresenceService presenceService;
	private final NotificationService notificationService;
	private final ObjectMapper objectMapper;

	public MessageDeliveryListener(
			ConversationService conversationService,
			WebSocketSessionRegistry sessionRegistry,
			PresenceService presenceService,
			NotificationService notificationService,
			ObjectMapper objectMapper) {
		this.conversationService = conversationService;
		this.sessionRegistry = sessionRegistry;
		this.presenceService = presenceService;
		this.notificationService = notificationService;
		this.objectMapper = objectMapper;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMessageCreated(MessageCreatedEvent event) {
		List<UUID> memberIds = conversationService.getMemberUserIds(event.conversationId());
		Map<String, Object> payload = Map.of(
				"message_id", event.messageId(),
				"conversation_id", event.conversationId(),
				"sender_id", event.senderId(),
				"sequence_num", event.sequenceNum(),
				"content_type", event.contentType(),
				"content", event.content(),
				"sent_at", event.sentAt().toString());

		for (UUID memberId : memberIds) {
			if (memberId.equals(event.senderId())) {
				continue;
			}
			if (sessionRegistry.hasActiveSession(memberId)) {
				sessionRegistry.sendToUser(memberId, frame("NEW_MESSAGE", payload));
			} else {
				notificationService.notifyOfflineUser(memberId, event.conversationId(), event.content());
			}
		}
	}

	private String frame(String type, Object payload) {
		try {
			return objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to serialize websocket frame", ex);
		}
	}
}
