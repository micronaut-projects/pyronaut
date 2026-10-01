package io.micronaut.pyronaut.dev;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautTestEngineFilterTest {

    private static final TestDescriptor PYTEST = new EngineDescriptor(UniqueId.forEngine("pyronaut-pytest"), "pytest");
    private static final TestDescriptor JUPITER = new EngineDescriptor(UniqueId.forEngine("junit-jupiter"), "jupiter");

    @Test
    void keepsTheTestsOfTheEnginesTheProjectSelects() {
        PyronautTestEngineFilter pytestOnly = new PyronautTestEngineFilter("pyronaut-pytest");
        assertTrue(pytestOnly.apply(PYTEST).included());
        assertFalse(pytestOnly.apply(JUPITER).included());
    }

    @Test
    void aTestBelongsToTheEngineThatDiscoveredIt() {
        // the pytest engine roots the identifiers of its tests at an engine segment of its own
        EngineDescriptor engine = new EngineDescriptor(UniqueId.forEngine("pyronaut-pytest"), "pytest");
        TestDescriptor test = new EngineDescriptor(UniqueId.forEngine("pytest-engine").append("test", "test_hello"), "test_hello");
        engine.addChild(test);
        assertTrue(new PyronautTestEngineFilter("junit-jupiter,pyronaut-pytest").apply(test).included());
        assertFalse(new PyronautTestEngineFilter("junit-jupiter").apply(test).included());
    }

    @Test
    void keepsEveryTestUnlessTheLauncherNamedTheEngines() {
        PyronautTestEngineFilter every = new PyronautTestEngineFilter("");
        assertTrue(every.apply(PYTEST).included());
        assertTrue(every.apply(JUPITER).included());
    }
}
