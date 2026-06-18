package com.test.ride.sharing.service.tracking;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TripTrackingWebSocketHandler tripTrackingWebSocketHandler;
    private final TripWebSocketHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(TripTrackingWebSocketHandler tripTrackingWebSocketHandler,
                           TripWebSocketHandshakeInterceptor handshakeInterceptor) {
        this.tripTrackingWebSocketHandler = tripTrackingWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(tripTrackingWebSocketHandler, "/v1/trips/{tripId}/stream")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
