package com.test.notification.dispatcher;

import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.Category;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.enums.DeliveryStatus;
import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.model.DeliveryAttempt;
import com.test.notification.domain.model.Template;
import com.test.notification.domain.repository.DeliveryAttemptRepository;
import com.test.notification.domain.repository.NotificationRequestRepository;
import com.test.notification.exception.EmailDeliveryException;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import com.test.notification.service.PreferenceService;
import com.test.notification.service.TemplateService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatchConsumer {

    private final EmailProvider emailProvider;
    private final TemplateService templateService;
    private final PreferenceService preferenceService;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationRequestRepository notificationRequestRepository;
    private final AppProperties props;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${app.kafka.topics.notification-requested}",
            groupId = "email-dispatcher",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consume(NotificationRequestedEvent event,
                        @Header(KafkaHeaders.DELIVERY_ATTEMPT) int deliveryAttempt) {

        UUID notificationId = event.getNotificationId();
        log.info("EmailDispatcher consuming notificationId={} attempt={}", notificationId, deliveryAttempt);

        // Skip if expired
        if (event.getExpiresAt() != null && Instant.now().isAfter(event.getExpiresAt())) {
            log.info("Notification expired, skipping. notificationId={}", notificationId);
            updateNotificationStatus(notificationId, NotificationStatus.EXPIRED);
            return;
        }

        // Preference check (unless channel override bypasses it)
        List<Channel> overrides = event.getChannelsOverride();
        boolean hasEmailOverride = overrides != null && overrides.contains(Channel.EMAIL);
        Category category = event.getCategory();

        if (!hasEmailOverride && !preferenceService.isOptedIn(event.getRecipientUserId(), Channel.EMAIL, category)) {
            log.info("User opted out of EMAIL channel. notificationId={} userId={}",
                    notificationId, event.getRecipientUserId());
            return;
        }

        // Template render
        Template template = templateService.getTemplate(
                event.getTemplateId(), event.getTemplateVersion(), Channel.EMAIL, "en-US");
        String renderedBody = templateService.render(template, event.getVariables());
        String renderedSubject = templateService.renderSubject(template, event.getVariables());

        // Resolve recipient email — in a real system this calls User Service
        String toEmail = resolveUserEmail(event.getRecipientUserId());

        EmailProvider.EmailMessage message = new EmailProvider.EmailMessage(
                toEmail,
                props.getEmail().getFromAddress(),
                renderedSubject,
                renderedBody,
                template.getBodyHtml() != null
                        ? templateService.render(buildHtmlTemplate(template), event.getVariables())
                        : null,
                notificationId.toString()
        );

        EmailProvider.EmailSendResult result = emailProvider.send(message);

        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .notificationId(notificationId)
                .channel(Channel.EMAIL)
                .provider(result.success() ? emailProvider.getClass().getSimpleName() : "unknown")
                .providerMessageId(result.providerMessageId())
                .status(result.success() ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED)
                .attemptNumber(deliveryAttempt)
                .attemptedAt(Instant.now())
                .deliveredAt(result.success() ? Instant.now() : null)
                .failureReason(result.failureReason())
                .failureCode(result.failureCode())
                .build();

        deliveryAttemptRepository.save(attempt);

        if (result.success()) {
            updateNotificationStatus(notificationId, NotificationStatus.DELIVERED);
            meterRegistry.counter("notification.delivered", "channel", "EMAIL").increment();
            log.info("Email delivered notificationId={} providerMessageId={}", notificationId, result.providerMessageId());
        } else {
            meterRegistry.counter("notification.failed", "channel", "EMAIL").increment();
            log.warn("Email delivery failed notificationId={} reason={}", notificationId, result.failureReason());
            throw new EmailDeliveryException(result.failureReason());
        }
    }

    private void updateNotificationStatus(UUID notificationId, NotificationStatus status) {
        notificationRequestRepository.findById(notificationId).ifPresent(n -> {
            n.setStatus(status);
            if (status == NotificationStatus.DELIVERED || status == NotificationStatus.FAILED
                    || status == NotificationStatus.EXPIRED) {
                n.setCompletedAt(Instant.now());
            }
            notificationRequestRepository.save(n);
        });
    }

    private String resolveUserEmail(UUID userId) {
        // In production: call User Service gRPC or REST to get email address.
        // For MVP: return a placeholder tied to userId.
        return "user-" + userId + "@example.com";
    }

    private Template buildHtmlTemplate(Template template) {
        // Wrap the HTML body for rendering; reuse same render logic
        return Template.builder()
                .templateId(template.getTemplateId())
                .version(template.getVersion())
                .channel(template.getChannel())
                .locale(template.getLocale())
                .bodyText(template.getBodyHtml())
                .build();
    }
}
