@org.springframework.modulith.ApplicationModule(
        displayName = "Ledger",
        allowedDependencies = {"shared", "account :: AccountApi", "account :: AccountEvents"}
)
package com.test.banking.core.ledger;
