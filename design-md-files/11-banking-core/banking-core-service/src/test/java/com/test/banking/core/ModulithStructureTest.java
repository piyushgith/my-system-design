package com.test.banking.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ModulithStructureTest {

    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(BankingCoreApplication.class).verify();
    }

    @Test
    void modulesAreDetected() {
        ApplicationModules modules = ApplicationModules.of(BankingCoreApplication.class);
        assertFalse(modules.stream().toList().isEmpty());
    }
}
