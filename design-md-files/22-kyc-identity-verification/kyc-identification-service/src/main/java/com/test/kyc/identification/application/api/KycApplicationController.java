package com.test.kyc.identification.application.api;

import com.test.kyc.identification.application.api.dto.ApplicationStatusResponse;
import com.test.kyc.identification.application.api.dto.PresignedUrlRequest;
import com.test.kyc.identification.application.api.dto.PresignedUrlResponse;
import com.test.kyc.identification.application.api.dto.SubmitApplicationRequest;
import com.test.kyc.identification.application.api.dto.SubmitApplicationResponse;
import com.test.kyc.identification.application.api.dto.TransitionHistoryResponse;
import com.test.kyc.identification.application.domain.KycApplication;
import com.test.kyc.identification.application.domain.KycTier;
import com.test.kyc.identification.application.service.KycApplicationService;
import com.test.kyc.identification.application.service.KycApplicationService.DocumentInput;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
public class KycApplicationController {

    private final KycApplicationService applicationService;

    /**
     * Step 1: request a presigned S3 URL so the client uploads the document
     * directly to S3 — raw bytes never pass through this service.
     * MVP stub: returns a placeholder URL since we have no real S3.
     */
    @PostMapping("/uploads/presigned-url")
    public ResponseEntity<PresignedUrlResponse> getPresignedUrl(
            @Valid @RequestBody PresignedUrlRequest request) {

        String tempKey = "temp/" + UUID.randomUUID() + "/" + request.documentType() + "_" + request.side();
        var response = new PresignedUrlResponse(
                "https://s3.mock.local/kyc-docs/" + tempKey,
                tempKey,
                300,
                5_242_880L
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Step 2: submit KYC application after documents are uploaded to S3.
     * Returns 202 Accepted — processing is async.
     */
    @PostMapping("/applications")
    public ResponseEntity<SubmitApplicationResponse> submitApplication(
            @Valid @RequestBody SubmitApplicationRequest request) {

        List<DocumentInput> docs = request.documents().stream()
                .map(d -> new DocumentInput(d.documentKey(), d.documentType(), d.side()))
                .toList();

        KycApplication application = applicationService.submitApplication(
                request.userId(),
                KycTier.valueOf(request.kycTier()),
                request.idempotencyKey(),
                request.personalData(),
                docs
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(SubmitApplicationResponse.from(application));
    }

    /** Poll application status. No PII in response. */
    @GetMapping("/applications/{applicationId}")
    public ResponseEntity<ApplicationStatusResponse> getStatus(
            @PathVariable UUID applicationId) {

        KycApplication application = applicationService.getApplication(applicationId);
        return ResponseEntity.ok(ApplicationStatusResponse.from(application));
    }

    /** Full state transition audit trail. Requires elevated scope in production. */
    @GetMapping("/applications/{applicationId}/transitions")
    public ResponseEntity<TransitionHistoryResponse> getTransitions(
            @PathVariable UUID applicationId) {

        var transitions = applicationService.getTransitionHistory(applicationId)
                .stream()
                .map(TransitionHistoryResponse.TransitionEntry::from)
                .toList();

        return ResponseEntity.ok(new TransitionHistoryResponse(applicationId, transitions));
    }
}
