package com.test.notification.dispatcher;

import com.test.notification.config.AppProperties;
import com.test.notification.domain.enums.Channel;
import com.test.notification.domain.model.Template;
import com.test.notification.exception.EmailDeliveryException;
import com.test.notification.kafka.event.NotificationRequestedEvent;
import com.test.notification.service.TemplateService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailDispatchConsumer {

    private final EmailProvider emailProvider;
    private final TemplateService templateService;
    private final NotificationGate notificationGate;
    private final UserEmailResolver userEmailResolver;
    private final DeliveryRecorder deliveryRecorder;
    private final AppProperties props;
    private final MeterRegistry meterRegistry;

    @KafkaListener(
            topics = "${app.kafka.topics.notification-requested}",
            groupId = "email-dispatcher",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(NotificationRequestedEvent event,
                        @Header(KafkaHeaders.DELIVERY_ATTEMPT) int deliveryAttempt) {

        UUID notificationId = event.getNotificationId();
        log.info("EmailDispatcher consuming notificationId={} attempt={}", notificationId, deliveryAttempt);

        NotificationGate.GateResult gate = notificationGate.check(event, Channel.EMAIL);
        if (gate == NotificationGate.GateResult.EXPIRED) {
            deliveryRecorder.markExpired(notificationId);
            return;
        }
        if (gate == NotificationGate.GateResult.OPTED_OUT) {
            return;
        }

        Template template = templateService.getTemplate(
                event.getTemplateId(), event.getTemplateVersion(), Channel.EMAIL, "en-US");

        String toEmail = userEmailResolver.resolveEmail(event.getRecipientUserId());

        EmailProvider.EmailMessage message = new EmailProvider.EmailMessage(
                toEmail,
                props.getEmail().getFromAddress(),
                templateService.renderSubject(template, event.getVariables()),
                templateService.render(template, event.getVariables()),
                template.getBodyHtml() != null
                        ? templateService.renderHtml(template, event.getVariables())
                        : null,
                notificationId.toString()
        );

        EmailProvider.EmailSendResult result = emailProvider.send(message);

        deliveryRecorder.record(notificationId, Channel.EMAIL,
                emailProvider.getClass().getSimpleName(), result, deliveryAttempt);

        meterRegistry.counter("notification." + (result.success() ? "delivered" : "failed"),
                "channel", "EMAIL").increment();

        if (result.success()) {
            log.info("Email delivered notificationId={} providerMessageId={}",
                    notificationId, result.providerMessageId());
        } else {
            log.warn("Email delivery failed notificationId={} reason={}", notificationId, result.failureReason());
            throw new EmailDeliveryException(result.failureReason());
        }
    }
}
