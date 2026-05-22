package com.test.kyc.identification.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.kyc.identification.application.domain.DocumentReference;
import com.test.kyc.identification.application.domain.KycApplication;
import com.test.kyc.identification.application.domain.KycStatus;
import com.test.kyc.identification.application.domain.KycTier;
import com.test.kyc.identification.application.domain.StateTransition;
import com.test.kyc.identification.application.repository.DocumentReferenceRepository;
import com.test.kyc.identification.application.repository.KycApplicationRepository;
import com.test.kyc.identification.application.repository.StateTransitionRepository;
import com.test.kyc.identification.common.encryption.PiiEncryptionService;
import com.test.kyc.identification.common.exception.ActiveApplicationExistsException;
import com.test.kyc.identification.common.exception.ApplicationNotFoundException;
import com.test.kyc.identification.verification.VerificationQueryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KycApplicationService {

    private final KycApplicationRepository applicationRepository;
    private final DocumentReferenceRepository documentReferenceRepository;
    private final StateTransitionRepository transitionRepository;
    private final PiiEncryptionService encryptionService;
    private final VerificationQueryApi verificationApi;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String IDEMPOTENCY_PREFIX = "kyc:idempotency:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;
    private static final long PII_RETENTION_YEARS = 2;

    @Transactional
    public KycApplication submitApplication(UUID userId,
                                             KycTier tier,
                                             String idempotencyKey,
                                             Map<String, Object> personalData,
                                             List<DocumentInput> documents) {
        // 1. Idempotency check (Redis fast path, DB unique constraint as safety net)
        if (idempotencyKey != null) {
            String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
            String existingId = redisTemplate.opsForValue().get(redisKey);
            if (existingId != null) {
                return applicationRepository.findById(UUID.fromString(existingId))
                        .orElseThrow(() -> new ApplicationNotFoundException(UUID.fromString(existingId)));
            }

            var existing = applicationRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // 2. One active application per user
        applicationRepository.findActiveByUserId(userId).ifPresent(active -> {
            throw new ActiveApplicationExistsException(userId);
        });

        // 3. Encrypt PII blob
        String piiJson = serializePersonalData(personalData);
        byte[] encryptedPii = encryptionService.encrypt(piiJson);

        // 4. Persist application
        KycApplication application = new KycApplication();
        application.setUserId(userId);
        application.setKycTier(tier);
        application.setStatus(KycStatus.SUBMITTED);
        application.setPersonalDataEncrypted(encryptedPii);
        application.setPersonalDataKeyVersion(encryptionService.currentKeyVersion());
        application.setIdempotencyKey(idempotencyKey);
        application.setPiiExpiresAt(Instant.now().plus(PII_RETENTION_YEARS * 365, ChronoUnit.DAYS));
        applicationRepository.save(application);

        // 5. Initial state transition
        var transition = StateTransition.of(
                application.getApplicationId(),
                null,
                KycStatus.SUBMITTED,
                "API_CALLBACK",
                "onboarding-service",
                "Application submitted"
        );
        transitionRepository.save(transition);

        // 6. Persist document references (S3 keys encrypted)
        for (DocumentInput doc : documents) {
            DocumentReference ref = new DocumentReference();
            ref.setApplicationId(application.getApplicationId());
            ref.setS3KeyEncrypted(encryptionService.encryptS3Key(doc.s3Key()));
            ref.setS3KeyVersion(encryptionService.currentKeyVersion());
            ref.setDocumentType(doc.documentType());
            ref.setSide(doc.side());
            documentReferenceRepository.save(ref);
        }

        // 7. Cache idempotency key
        if (idempotencyKey != null) {
            redisTemplate.opsForValue().set(
                    IDEMPOTENCY_PREFIX + idempotencyKey,
                    application.getApplicationId().toString(),
                    Duration.ofHours(IDEMPOTENCY_TTL_HOURS)
            );
        }

        // 8. Kick off async pipeline
        verificationApi.startPipeline(application.getApplicationId());

        log.info("KYC application submitted: id={} user={} tier={}", application.getApplicationId(), userId, tier);
        return application;
    }

    public KycApplication getApplication(UUID applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
    }

    public List<StateTransition> getTransitionHistory(UUID applicationId) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new ApplicationNotFoundException(applicationId);
        }
        return transitionRepository.findByApplicationIdOrderByOccurredAtAsc(applicationId);
    }

    public List<VerificationQueryApi.StepSummary> getStepSummaries(UUID applicationId) {
        if (!applicationRepository.existsById(applicationId)) {
            throw new ApplicationNotFoundException(applicationId);
        }
        return verificationApi.getStepSummaries(applicationId);
    }

    private String serializePersonalData(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid personal data", e);
        }
    }

    public record DocumentInput(String s3Key, String documentType, String side) {}
}
