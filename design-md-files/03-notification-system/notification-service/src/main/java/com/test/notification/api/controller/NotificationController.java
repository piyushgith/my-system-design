package com.test.notification.api.controller;

import com.test.notification.api.dto.NotificationStatusResponse;
import com.test.notification.api.dto.SubmitNotificationRequest;
import com.test.notification.api.dto.SubmitNotificationResponse;
import com.test.notification.domain.enums.NotificationStatus;
import com.test.notification.domain.model.NotificationRequest;
import com.test.notification.exception.NotificationNotCancellableException;
import com.test.notification.service.NotificationStatusService;
import com.test.notification.service.NotificationSubmissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationSubmissionService submissionService;
    private final NotificationStatusService statusService;

    @PostMapping
    public ResponseEntity<SubmitNotificationResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitNotificationRequest req) {

        NotificationRequest notification = NotificationRequest.builder()
                .idempotencyKey(idempotencyKey)
                .category(req.getCategory())
                .priority(req.getPriority())
                .recipientUserId(req.getRecipientUserId())
                .templateId(req.getTemplateId())
                .templateVersion(req.getTemplateVersion())
                .variables(req.getVariables())
                .channelsOverride(req.getChannelsOverride())
                .scheduledAt(req.getScheduledAt())
                .expiresAt(req.getExpiresAt())
                .producerService(req.getProducerContext() != null ? req.getProducerContext().getService() : null)
                .producerTraceId(req.getProducerContext() != null ? req.getProducerContext().getTraceId() : null)
                .build();

        NotificationRequest saved = submissionService.submit(notification);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                SubmitNotificationResponse.builder()
                        .notificationId(saved.getNotificationId())
                        .status(saved.getStatus())
                        .idempotencyKey(saved.getIdempotencyKey())
                        .estimatedDeliverySeconds(saved.getPriority().ordinal() == 0 ? 5 : 30)
                        .build()
        );
    }

    @GetMapping("/{notificationId}")
    public ResponseEntity<NotificationStatusResponse> getStatus(@PathVariable UUID notificationId) {
        NotificationRequest notification = statusService.getNotification(notificationId);
        var attempts = statusService.getDeliveryAttempts(notificationId).stream()
                .map(NotificationStatusResponse.DeliveryAttemptDto::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(NotificationStatusResponse.builder()
                .notificationId(notification.getNotificationId())
                .status(notification.getStatus())
                .category(notification.getCategory())
                .priority(notification.getPriority())
                .recipientUserId(notification.getRecipientUserId())
                .createdAt(notification.getCreatedAt())
                .completedAt(notification.getCompletedAt())
                .deliveryAttempts(attempts)
                .build());
    }

    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID notificationId) {
        boolean cancelled = submissionService.cancel(notificationId);
        if (!cancelled) {
            throw new NotificationNotCancellableException(notificationId);
        }
        return ResponseEntity.ok(Map.of(
                "notificationId", notificationId,
                "status", NotificationStatus.CANCELLED,
                "cancelledAt", Instant.now()
        ));
    }
}
