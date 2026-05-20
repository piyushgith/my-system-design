package com.test.kyc.identification.vendor;

public interface VendorClient {

    String vendorName();

    OcrResult performDocumentOcr(String s3DocumentKey, String documentType);

    LivenessResult performLivenessCheck(String s3SelfieKey);

    WatchlistResult performWatchlistScreening(String fullName, String dateOfBirth, String nationality);
}
