package com.test.ride.sharing.service.tracking;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class TripTrackingWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<UUID, CopyOnWriteArraySet<WebSocketSession>> sessionsByTrip = new ConcurrentHashMap<>();

    public TripTrackingWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID tripId = (UUID) session.getAttributes().get(TripWebSocketHandshakeInterceptor.TRIP_ID_ATTR);
        if (tripId != null) {
            sessionsByTrip.computeIfAbsent(tripId, id -> new CopyOnWriteArraySet<>()).add(session);
            sendJson(session, Map.of("type", "CONNECTED", "data", Map.of("trip_id", tripId.toString())));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("{\"type\":\"PONG\"}".equals(message.getPayload()) || message.getPayload().contains("PONG")) {
            return;
        }
        if (message.getPayload().contains("SYNC_REQUEST")) {
            UUID tripId = (UUID) session.getAttributes().get(TripWebSocketHandshakeInterceptor.TRIP_ID_ATTR);
            sendJson(session, Map.of("type", "SYNC_ACK", "data", Map.of("trip_id", tripId.toString())));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID tripId = (UUID) session.getAttributes().get(TripWebSocketHandshakeInterceptor.TRIP_ID_ATTR);
        if (tripId != null) {
            CopyOnWriteArraySet<WebSocketSession> sessions = sessionsByTrip.get(tripId);
            if (sessions != null) {
                sessions.remove(session);
            }
        }
    }

    public void broadcast(UUID tripId, String type, Map<String, Object> data) {
        CopyOnWriteArraySet<WebSocketSession> sessions = sessionsByTrip.get(tripId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of("type", type, "data", data);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    sendJson(session, payload);
                } catch (IOException ignored) {
                    // drop stale session
                }
            }
        }
    }

    private void sendJson(WebSocketSession session, Map<String, Object> payload) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
    }
}
