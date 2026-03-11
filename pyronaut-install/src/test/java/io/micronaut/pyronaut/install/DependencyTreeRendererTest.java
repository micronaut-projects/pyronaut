package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyTreeRendererTest {

    @Test
    void resolutionErrorsRemainPlainWhenColorDisabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DependencyTreeRenderer renderer = new DependencyTreeRenderer(new PrintStream(output), false);

        renderer.renderResolutionError(InstallScope.RUNTIME, "missing artifact");

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("ERROR: missing artifact"));
        assertFalse(text.contains("\u001B[31m"));
    }

    @Test
    void resolutionErrorsUseAnsiWhenColorEnabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DependencyTreeRenderer renderer = new DependencyTreeRenderer(new PrintStream(output), true);

        renderer.renderResolutionError(InstallScope.RUNTIME, "missing artifact");

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[31mERROR: missing artifact\u001B[0m"));
    }
}
