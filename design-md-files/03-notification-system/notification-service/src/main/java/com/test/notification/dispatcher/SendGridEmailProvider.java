package com.test.notification.dispatcher;

import com.test.notification.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Stub SendGrid provider. Wire up the real SendGrid Java SDK when SENDGRID_API_KEY is available.
 * Swap MockEmailProvider → SendGridEmailProvider by setting app.email.provider=sendgrid.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "sendgrid")
@RequiredArgsConstructor
public class SendGridEmailProvider implements EmailProvider {

    private static final String SENDGRID_API_URL = "https://api.sendgrid.com/v3/mail/send";

    private final AppProperties props;
    private final RestClient.Builder restClientBuilder;

    @Override
    public EmailSendResult send(EmailMessage message) {
        try {
            Map<String, Object> body = Map.of(
                    "personalizations", List.of(Map.of("to", List.of(Map.of("email", message.to())))),
                    "from", Map.of("email", message.from()),
                    "subject", message.subject(),
                    "content", List.of(Map.of("type", "text/plain", "value", message.bodyText()))
            );

            restClientBuilder.build()
                    .post()
                    .uri(SENDGRID_API_URL)
                    .header("Authorization", "Bearer " + props.getEmail().getSendgrid().getApiKey())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            // SendGrid returns 202 with X-Message-Id header; simplified here
            return new EmailSendResult(true, "sg-" + System.currentTimeMillis(), null, null);

        } catch (Exception e) {
            log.error("SendGrid send failed notificationId={} error={}", message.notificationId(), e.getMessage());
            return new EmailSendResult(false, null, e.getMessage(), "PROVIDER_ERROR");
        }
    }
}
