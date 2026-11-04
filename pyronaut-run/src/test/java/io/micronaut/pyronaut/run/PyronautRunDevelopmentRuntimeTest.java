package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class PyronautRunDevelopmentRuntimeTest {

    @Test
    void theDevelopmentRuntimeIsOffUnlessTheCliAsksForIt() {
        assertNull(System.getProperty("pyronaut.dev.reload"));
        assertNull(PyronautRunMain.developmentRuntime());
    }
}
