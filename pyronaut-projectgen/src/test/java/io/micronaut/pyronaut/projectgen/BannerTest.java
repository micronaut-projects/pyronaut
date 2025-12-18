package io.micronaut.pyronaut.projectgen;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(startApplication = false)
class BannerTest {

    @Inject
    Banner feature;

    @Test
    void featuresIsNotVisible() {
        assertFalse(feature.isVisible());
    }
}
