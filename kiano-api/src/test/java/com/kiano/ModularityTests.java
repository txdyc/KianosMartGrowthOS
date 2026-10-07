package com.kiano;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    @Test
    void modulesRespectBoundaries() {
        ApplicationModules.of(KianoApplication.class).verify();
    }
}
