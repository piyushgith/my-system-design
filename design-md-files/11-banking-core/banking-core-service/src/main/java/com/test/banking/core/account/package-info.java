@org.springframework.modulith.ApplicationModule(
        displayName = "Account",
        allowedDependencies = {"shared", "kyc :: KycApi"}
)
package com.test.banking.core.account;
