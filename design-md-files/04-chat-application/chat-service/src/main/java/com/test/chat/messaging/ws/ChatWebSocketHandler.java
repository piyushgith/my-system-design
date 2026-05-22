package com.test.chat.messaging.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.chat.messaging.application.MessageService;
import com.test.chat.messaging.domain.Message;
import com.test.chat.presence.application.PresenceService;
import com.test.chat.shared.error.ChatException;
import com.test.chat.shared.security.JwtService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

	private final JwtService jwtService;
	private final MessageService messageService;
	private final WebSocketSessionRegistry sessionRegistry;
	private final PresenceService presenceService;
	private final ObjectMapper objectMapper;

	public ChatWebSocketHandler(
			JwtService jwtService,
			MessageService messageService,
			WebSocketSessionRegistry sessionRegistry,
			PresenceService presenceService,
			ObjectMapper objectMapper) {
		this.jwtService = jwtService;
		this.messageService = messageService;
		this.sessionRegistry = sessionRegistry;
		this.presenceService = presenceService;
		this.objectMapper = objectMapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		UUID userId = authenticate(session);
		session.getAttributes().put("userId", userId);
		sessionRegistry.register(userId, session);
		presenceService.markOnline(userId);
		session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
				"type", "CONNECTED",
				"payload", Map.of(
						"server_id", "chat-mvp-1",
						"user_id", userId.toString())))));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		UUID userId = (UUID) session.getAttributes().get("userId");
		JsonNode frame = objectMapper.readTree(message.getPayload());
		String type = frame.path("type").asText();
		String frameId = frame.path("frame_id").asText(null);

		switch (type) {
			case "SEND_MESSAGE" -> handleSendMessage(session, userId, frameId, frame.path("payload"));
			case "PING" -> session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
					Map.of("type", "PONG", "frame_id", frameId == null ? "" : frameId))));
			default -> sendError(session, frameId, "UNSUPPORTED_FRAME", "Unsupported frame type: " + type);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		Object userIdObj = session.getAttributes().get("userId");
		if (userIdObj instanceof UUID userId) {
			sessionRegistry.unregister(userId, session);
			if (!sessionRegistry.hasActiveSession(userId)) {
				presenceService.markOffline(userId);
			}
		}
	}

	private void handleSendMessage(WebSocketSession session, UUID userId, String frameId, JsonNode payload) throws Exception {
		try {
			UUID conversationId = UUID.fromString(payload.path("conversation_id").asText());
			String contentType = payload.path("content_type").asText("TEXT");
			String content = payload.path("content").asText();
			String idempotencyKey = payload.path("idempotency_key").asText(null);

			Message saved = messageService.sendMessage(userId, conversationId, contentType, content, idempotencyKey);
			Map<String, Object> ackPayload = Map.of(
					"message_id", saved.getMessageId(),
					"sequence_num", saved.getSequenceNum(),
					"status", saved.getStatus().name(),
					"server_received_at", saved.getServerReceivedAt().toString());
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
					"type", "MESSAGE_ACK",
					"frame_id", frameId == null ? "" : frameId,
					"payload", ackPayload))));
		} catch (ChatException ex) {
			sendError(session, frameId, ex.getCode(), ex.getMessage());
		} catch (Exception ex) {
			sendError(session, frameId, "INVALID_REQUEST", "Invalid send message payload");
		}
	}

	private void sendError(WebSocketSession session, String frameId, String code, String message) throws Exception {
		session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
				"type", "ERROR",
				"frame_id", frameId == null ? "" : frameId,
				"payload", Map.of("code", code, "message", message)))));
	}

	private UUID authenticate(WebSocketSession session) {
		String token = extractToken(session);
		if (token == null || token.isBlank()) {
			throw new ChatException("UNAUTHORIZED", "Missing JWT token", org.springframework.http.HttpStatus.UNAUTHORIZED);
		}
		return jwtService.parseUserId(token);
	}

	private String extractToken(WebSocketSession session) {
		URI uri = session.getUri();
		if (uri != null && uri.getQuery() != null) {
			for (String part : uri.getQuery().split("&")) {
				if (part.startsWith("token=")) {
					return part.substring("token=".length());
				}
			}
		}
		return null;
	}
}
