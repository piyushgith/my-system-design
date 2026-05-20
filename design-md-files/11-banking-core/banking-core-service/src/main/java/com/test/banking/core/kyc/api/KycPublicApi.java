package com.test.banking.core.kyc.api;

public interface KycPublicApi {

    boolean isKycVerified(String cifId);

    boolean customerExists(String cifId);

    String getCustomerStatus(String cifId);
}
