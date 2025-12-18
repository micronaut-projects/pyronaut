package io.micronaut.pyronaut.projectgen;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@MicronautTest(startApplication = false)
class PyProjectTomlTest {

    @Inject
    PyProjectToml feature;

    @Test
    void featuresIsNotVisible() {
        assertFalse(feature.isVisible());
    }
}
