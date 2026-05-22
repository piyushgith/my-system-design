package com.test.chat.messaging.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionRegistry {

	private final Map<UUID, Set<WebSocketSession>> sessionsByUser = new ConcurrentHashMap<>();

	public void register(UUID userId, WebSocketSession session) {
		sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
	}

	public void unregister(UUID userId, WebSocketSession session) {
		Set<WebSocketSession> sessions = sessionsByUser.get(userId);
		if (sessions == null) {
			return;
		}
		sessions.remove(session);
		if (sessions.isEmpty()) {
			sessionsByUser.remove(userId);
		}
	}

	public boolean hasActiveSession(UUID userId) {
		Set<WebSocketSession> sessions = sessionsByUser.get(userId);
		return sessions != null && !sessions.isEmpty();
	}

	public void sendToUser(UUID userId, String payload) {
		Set<WebSocketSession> sessions = sessionsByUser.get(userId);
		if (sessions == null) {
			return;
		}
		for (WebSocketSession session : sessions) {
			if (session.isOpen()) {
				try {
					session.sendMessage(new TextMessage(payload));
				} catch (IOException ignored) {
					// Session cleanup happens on close callback.
				}
			}
		}
	}
}
