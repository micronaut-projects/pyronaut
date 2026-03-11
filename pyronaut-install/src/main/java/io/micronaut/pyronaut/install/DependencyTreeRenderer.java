/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.install;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;

import java.io.PrintStream;
import java.util.List;

final class DependencyTreeRenderer {

    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    private final PrintStream output;
    private final boolean colorEnabled;

    static DependencyTreeRenderer create(String colorMode) {
        ColorMode mode = ColorMode.fromCliValue(colorMode);
        boolean interactive = System.console() != null;
        boolean colorEnabled = mode == ColorMode.ALWAYS || (mode == ColorMode.AUTO && interactive);
        return new DependencyTreeRenderer(System.out, colorEnabled);
    }

    DependencyTreeRenderer(PrintStream output, boolean colorEnabled) {
        this.output = output;
        this.colorEnabled = colorEnabled;
    }

    void renderScope(InstallScope scope, DependencyNode root) {
        output.println("Dependency tree (" + scope.cliValue() + "):");
        List<DependencyNode> children = root == null ? List.of() : root.getChildren();
        if (children.isEmpty()) {
            output.println("(no dependencies)");
            return;
        }
        for (int index = 0; index < children.size(); index++) {
            DependencyNode child = children.get(index);
            boolean last = index == children.size() - 1;
            renderNode(child, "", last);
        }
    }

    void renderResolutionError(InstallScope scope, String message) {
        output.println("Dependency tree (" + scope.cliValue() + "):");
        output.println(applyErrorColor("ERROR: " + message));
    }

    private void renderNode(DependencyNode node, String prefix, boolean last) {
        output.println(prefix + (last ? "└─ " : "├─ ") + nodeLabel(node));
        List<DependencyNode> children = node.getChildren();
        String childPrefix = prefix + (last ? "   " : "│  ");
        for (int index = 0; index < children.size(); index++) {
            renderNode(children.get(index), childPrefix, index == children.size() - 1);
        }
    }

    private static String nodeLabel(DependencyNode node) {
        Dependency dependency = node.getDependency();
        Artifact artifact = node.getArtifact();
        if (artifact == null && dependency != null) {
            artifact = dependency.getArtifact();
        }
        if (artifact == null) {
            return "<unknown>";
        }
        String coordinate = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
        if (dependency == null || dependency.getScope() == null || dependency.getScope().isBlank()) {
            return coordinate;
        }
        return coordinate + " [" + dependency.getScope() + "]";
    }

    private String applyErrorColor(String value) {
        if (!colorEnabled) {
            return value;
        }
        return RED + value + RESET;
    }

    enum ColorMode {
        AUTO,
        ALWAYS,
        NEVER;

        static ColorMode fromCliValue(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Invalid --color value ''. Expected one of: auto|always|never");
            }
            return switch (value.trim().toLowerCase()) {
                case "auto" -> AUTO;
                case "always" -> ALWAYS;
                case "never" -> NEVER;
                default -> throw new IllegalArgumentException("Invalid --color value '" + value + "'. Expected one of: auto|always|never");
            };
        }
    }
}
