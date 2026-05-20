package com.test.notification.dispatcher;

public interface EmailProvider {

    EmailSendResult send(EmailMessage message);

    record EmailMessage(
            String to,
            String from,
            String subject,
            String bodyText,
            String bodyHtml,
            String notificationId
    ) {}

    record EmailSendResult(
            boolean success,
            String providerMessageId,
            String failureReason,
            String failureCode
    ) {}
}
