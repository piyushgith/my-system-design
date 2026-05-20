package com.test.kyc.identification.vendor.mock;

import com.test.kyc.identification.vendor.LivenessResult;
import com.test.kyc.identification.vendor.OcrResult;
import com.test.kyc.identification.vendor.VendorClient;
import com.test.kyc.identification.vendor.WatchlistResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Mock vendor client for local dev and testing.
 * Returns successful results for all calls.
 * Activate with kyc.vendor.mock=true.
 */
@Component
@ConditionalOnProperty(name = "kyc.vendor.mock", havingValue = "true", matchIfMissing = true)
public class MockVendorClient implements VendorClient {

    @Override
    public String vendorName() {
        return "MOCK";
    }

    @Override
    public OcrResult performDocumentOcr(String s3DocumentKey, String documentType) {
        return OcrResult.builder()
                .success(true)
                .confidenceScore(0.97)
                .extractedName("Test User")
                .extractedDob("1990-01-01")
                .documentNumber("TEST123456")
                .expiryDate("2030-12-31")
                .rawFields(Map.of(
                        "vendor", vendorName(),
                        "documentKey", s3DocumentKey,
                        "documentType", documentType
                ))
                .build();
    }

    @Override
    public LivenessResult performLivenessCheck(String s3SelfieKey) {
        return LivenessResult.builder()
                .live(true)
                .confidenceScore(0.99)
                .build();
    }

    @Override
    public WatchlistResult performWatchlistScreening(String fullName, String dateOfBirth, String nationality) {
        return WatchlistResult.builder()
                .clear(true)
                .hits(List.of())
                .build();
    }
}
