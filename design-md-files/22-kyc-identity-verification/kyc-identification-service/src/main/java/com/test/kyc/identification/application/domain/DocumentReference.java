package com.test.kyc.identification.application.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_references")
@Getter
@Setter
@NoArgsConstructor
public class DocumentReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "doc_ref_id", updatable = false, nullable = false)
    private UUID docRefId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "s3_key_encrypted", nullable = false)
    private byte[] s3KeyEncrypted;

    @Column(name = "s3_key_version", nullable = false, length = 50)
    private String s3KeyVersion;

    @Column(name = "document_type", nullable = false, length = 30)
    private String documentType;

    @Column(name = "side", nullable = false, length = 10)
    private String side;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "is_purged", nullable = false)
    private boolean isPurged = false;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @PrePersist
    void prePersist() {
        uploadedAt = Instant.now();
    }
}
