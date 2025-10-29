package com.homeloan.notification.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentVerificationEvent {

    private Long applicationId;
    private Long documentId;
    private String documentType;
    private String verificationStatus;
    private String verifiedBy;
    private LocalDateTime verificationDate;
    private String comments;
    private String sagaId;
    private String eventType;

    public enum DocumentType {
        IDENTITY_PROOF, ADDRESS_PROOF, INCOME_PROOF, PROPERTY_DOCUMENTS, BANK_STATEMENTS, TAX_RETURNS
    }

    public enum VerificationStatus {
        PENDING, VERIFIED, REJECTED, INCOMPLETE
    }


    public enum EventType {
        DOCUMENT_VERIFICATION_STARTED, DOCUMENT_VERIFICATION_COMPLETED, DOCUMENT_VERIFICATION_FAILED, DOCUMENT_VERIFICATION_COMPENSATED
    }
}