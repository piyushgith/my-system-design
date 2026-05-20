package com.test.notification.dispatcher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "mock", matchIfMissing = true)
public class MockEmailProvider implements EmailProvider {

    @Override
    public EmailSendResult send(EmailMessage message) {
        String mockMessageId = "mock-" + UUID.randomUUID();
        log.info("[MOCK EMAIL] to={} subject='{}' notificationId={} providerMessageId={}",
                message.to(), message.subject(), message.notificationId(), mockMessageId);
        return new EmailSendResult(true, mockMessageId, null, null);
    }
}
