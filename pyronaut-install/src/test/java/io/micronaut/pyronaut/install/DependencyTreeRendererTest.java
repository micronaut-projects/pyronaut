package io.micronaut.pyronaut.install;

import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyTreeRendererTest {

    @Test
    void resolutionErrorsRemainPlainWhenColorDisabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DependencyTreeRenderer renderer = new DependencyTreeRenderer(new PrintStream(output), false);

        renderer.renderResolutionError(
            InstallScope.RUNTIME,
            new DependencyTreeRenderer.ResolutionFailure("missing artifact", null, Set.of())
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("ERROR: missing artifact"));
        assertFalse(text.contains("\u001B[31m"));
    }

    @Test
    void resolutionErrorsUseAnsiWhenColorEnabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DependencyTreeRenderer renderer = new DependencyTreeRenderer(new PrintStream(output), true);

        renderer.renderResolutionError(
            InstallScope.RUNTIME,
            new DependencyTreeRenderer.ResolutionFailure("missing artifact", null, Set.of())
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\u001B[31mERROR: missing artifact\u001B[0m"));
    }

    @Test
    void unresolvedNodesAreMarkedAndPathsPrinted() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DependencyTreeRenderer renderer = new DependencyTreeRenderer(new PrintStream(output), false);

        DefaultDependencyNode root = new DefaultDependencyNode(new DefaultArtifact("com.example:root:1.0.0"));
        DefaultDependencyNode parent = new DefaultDependencyNode(
            new Dependency(new DefaultArtifact("com.example:parent:1.0.0"), "runtime")
        );
        DefaultDependencyNode missing = new DefaultDependencyNode(
            new Dependency(new DefaultArtifact("com.example:missing:1.0.0"), "runtime")
        );
        parent.setChildren(List.of(missing));
        root.setChildren(List.of(parent));

        renderer.renderResolutionError(
            InstallScope.RUNTIME,
            new DependencyTreeRenderer.ResolutionFailure(
                "Dependency resolution failed",
                root,
                Set.of("com.example:missing:1.0.0", "com.example:missing")
            )
        );

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("com.example:missing:1.0.0 [runtime] [ERROR]"));
        assertTrue(text.contains("ERROR path: com.example:parent:1.0.0 -> com.example:missing:1.0.0"));
    }
}
