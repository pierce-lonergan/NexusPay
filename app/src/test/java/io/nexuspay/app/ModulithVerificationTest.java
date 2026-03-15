package io.nexuspay.app;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithVerificationTest {

    @Test
    void verifyModuleBoundaries() {
        ApplicationModules modules = ApplicationModules.of(NexusPayApplication.class);
        modules.verify();
    }

    @Test
    void printModuleStructure() {
        ApplicationModules modules = ApplicationModules.of(NexusPayApplication.class);
        modules.forEach(System.out::println);
    }
}
