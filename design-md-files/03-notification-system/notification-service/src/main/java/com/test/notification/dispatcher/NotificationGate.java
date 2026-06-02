package com.test.notification.dispatcher;

import com.test.notification.domain.enums.Channel;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import com.test.notification.service.PreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationGate {

    private final PreferenceService preferenceService;

    public enum GateResult { ALLOWED, EXPIRED, OPTED_OUT }

    public GateResult check(NotificationRequestedEvent event, Channel channel) {
        if (isExpired(event)) {
            log.info("Notification expired, skipping. notificationId={}", event.getNotificationId());
            return GateResult.EXPIRED;
        }
        if (!isChannelAllowed(event, channel)) {
            log.info("User opted out of {} channel. notificationId={} userId={}",
                    channel, event.getNotificationId(), event.getRecipientUserId());
            return GateResult.OPTED_OUT;
        }
        return GateResult.ALLOWED;
    }

    private boolean isExpired(NotificationRequestedEvent event) {
        return event.getExpiresAt() != null && Instant.now().isAfter(event.getExpiresAt());
    }

    private boolean isChannelAllowed(NotificationRequestedEvent event, Channel channel) {
        List<Channel> overrides = event.getChannelsOverride();
        if (overrides != null && overrides.contains(channel)) return true;
        return preferenceService.isOptedIn(event.getRecipientUserId(), channel, event.getCategory());
    }
}
