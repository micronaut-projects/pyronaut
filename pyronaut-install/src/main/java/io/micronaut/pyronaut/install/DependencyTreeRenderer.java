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
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DependencyTreeRenderer {

    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    private final PrintStream output;
    private final boolean colorEnabled;

    DependencyTreeRenderer(PrintStream output, boolean colorEnabled) {
        this.output = output;
        this.colorEnabled = colorEnabled;
    }

    static DependencyTreeRenderer create(String colorMode) {
        ColorMode mode = ColorMode.fromCliValue(colorMode);
        boolean interactive = io.micronaut.pyronaut.config.terminal.Terminal.isInteractive();
        boolean colorEnabled = mode == ColorMode.ALWAYS || (mode == ColorMode.AUTO && interactive);
        return new DependencyTreeRenderer(System.out, colorEnabled);
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
            renderNode(child, "", last, Set.of());
        }
    }

    void renderResolutionError(InstallScope scope, ResolutionFailure failure) {
        output.println("Dependency tree (" + scope.cliValue() + "):");
        List<DependencyNode> children = failure.root() == null ? List.of() : failure.root().getChildren();
        if (children.isEmpty()) {
            output.println("(no dependencies)");
        } else {
            for (int index = 0; index < children.size(); index++) {
                DependencyNode child = children.get(index);
                boolean last = index == children.size() - 1;
                renderNode(child, "", last, failure.unresolvedCoordinates());
            }
        }
        output.println(applyErrorColor("ERROR: " + failure.summaryMessage()));

        List<String> unresolvedPaths = unresolvedPaths(failure.root(), failure.unresolvedCoordinates());
        for (String unresolvedPath : unresolvedPaths) {
            output.println(applyErrorColor("ERROR path: " + unresolvedPath));
        }
    }

    private void renderNode(DependencyNode node,
                            String prefix,
                            boolean last,
                            Set<String> unresolvedCoordinates) {
        output.println(prefix + (last ? "└─ " : "├─ ") + markedNodeLabel(node, unresolvedCoordinates));
        List<DependencyNode> children = node.getChildren();
        String childPrefix = prefix + (last ? "   " : "│  ");
        for (int index = 0; index < children.size(); index++) {
            renderNode(children.get(index), childPrefix, index == children.size() - 1, unresolvedCoordinates);
        }
    }

    private String markedNodeLabel(DependencyNode node, Set<String> unresolvedCoordinates) {
        String label = nodeLabel(node);
        if (!isUnresolved(node, unresolvedCoordinates)) {
            return label;
        }
        String marked = label + " [ERROR]";
        return applyErrorColor(marked);
    }

    private static boolean isUnresolved(DependencyNode node, Set<String> unresolvedCoordinates) {
        if (unresolvedCoordinates.isEmpty()) {
            return false;
        }
        Artifact artifact = node.getArtifact();
        if (artifact == null && node.getDependency() != null) {
            artifact = node.getDependency().getArtifact();
        }
        if (artifact == null) {
            return false;
        }
        return unresolvedCoordinates.contains(coordinate(artifact))
            || unresolvedCoordinates.contains(artifact.getGroupId() + ":" + artifact.getArtifactId());
    }

    private static List<String> unresolvedPaths(DependencyNode root, Set<String> unresolvedCoordinates) {
        if (root == null || unresolvedCoordinates.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (DependencyNode child : root.getChildren()) {
            collectUnresolvedPaths(child, unresolvedCoordinates, new ArrayList<>(), paths);
        }
        return List.copyOf(paths);
    }

    private static void collectUnresolvedPaths(DependencyNode node,
                                               Set<String> unresolvedCoordinates,
                                               List<String> stack,
                                               Set<String> outputPaths) {
        Artifact artifact = node.getArtifact();
        if (artifact == null && node.getDependency() != null) {
            artifact = node.getDependency().getArtifact();
        }
        String current = artifact == null ? "<unknown>" : coordinate(artifact);
        stack.add(current);

        if (artifact != null
            && (unresolvedCoordinates.contains(current)
            || unresolvedCoordinates.contains(artifact.getGroupId() + ":" + artifact.getArtifactId()))) {
            outputPaths.add(String.join(" -> ", stack));
        }

        for (DependencyNode child : node.getChildren()) {
            collectUnresolvedPaths(child, unresolvedCoordinates, stack, outputPaths);
        }
        stack.remove(stack.size() - 1);
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
        String coordinate = coordinate(artifact);
        if (dependency == null || dependency.getScope() == null || dependency.getScope().isBlank()) {
            return coordinate;
        }
        return coordinate + " [" + dependency.getScope() + "]";
    }

    private static String coordinate(Artifact artifact) {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    private String applyErrorColor(String value) {
        if (!colorEnabled) {
            return value;
        }
        return RED + value + RESET;
    }

    record ResolutionFailure(String summaryMessage,
                             DependencyNode root,
                             Set<String> unresolvedCoordinates) {

        static ResolutionFailure fromException(String summaryMessage, DependencyResolutionException exception) {
            DependencyResult result = exception.getResult();
            DependencyNode root = result == null ? null : result.getRoot();
            LinkedHashSet<String> unresolved = new LinkedHashSet<>();
            if (result != null) {
                for (ArtifactResult artifactResult : result.getArtifactResults()) {
                    if (artifactResult.isResolved()) {
                        continue;
                    }
                    Artifact requested = artifactResult.getRequest() == null ? null : artifactResult.getRequest().getArtifact();
                    if (requested != null) {
                        unresolved.add(coordinate(requested));
                        unresolved.add(requested.getGroupId() + ":" + requested.getArtifactId());
                        continue;
                    }
                    Artifact unresolvedArtifact = artifactResult.getArtifact();
                    if (unresolvedArtifact != null) {
                        unresolved.add(coordinate(unresolvedArtifact));
                        unresolved.add(unresolvedArtifact.getGroupId() + ":" + unresolvedArtifact.getArtifactId());
                    }
                }
            }
            if (unresolved.isEmpty()) {
                DefaultArtifact parsed = parseFirstCoordinate(summaryMessage);
                if (parsed != null) {
                    unresolved.add(coordinate(parsed));
                    unresolved.add(parsed.getGroupId() + ":" + parsed.getArtifactId());
                }
            }
            return new ResolutionFailure(summaryMessage, root, Set.copyOf(unresolved));
        }

        static ResolutionFailure fromException(String summaryMessage, DependencyCollectionException exception) {
            CollectResult result = exception.getResult();
            DependencyNode root = result == null ? null : result.getRoot();
            LinkedHashSet<String> unresolved = new LinkedHashSet<>();
            if (result != null) {
                for (Exception failure : result.getExceptions()) {
                    if (failure instanceof ArtifactDescriptorException descriptorFailure
                        && descriptorFailure.getResult() != null
                        && descriptorFailure.getResult().getRequest() != null) {
                        Artifact artifact = descriptorFailure.getResult().getRequest().getArtifact();
                        if (artifact != null) {
                            unresolved.add(coordinate(artifact));
                            unresolved.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
                        }
                    }
                }
            }
            if (unresolved.isEmpty()) {
                DefaultArtifact parsed = parseFirstCoordinate(summaryMessage);
                if (parsed != null) {
                    unresolved.add(coordinate(parsed));
                    unresolved.add(parsed.getGroupId() + ":" + parsed.getArtifactId());
                }
            }
            return new ResolutionFailure(summaryMessage, root, Set.copyOf(unresolved));
        }

        private static DefaultArtifact parseFirstCoordinate(String summaryMessage) {
            int index = summaryMessage.indexOf(':');
            while (index >= 0) {
                int end = summaryMessage.indexOf(' ', index);
                if (end < 0) {
                    end = summaryMessage.length();
                }
                String token = summaryMessage.substring(Math.max(0, index - 64), end)
                    .replace(",", "")
                    .trim();
                int artifactStart = Math.max(token.lastIndexOf(' ') + 1, 0);
                String candidate = token.substring(artifactStart);
                try {
                    DefaultArtifact artifact = new DefaultArtifact(candidate);
                    if (artifact.getGroupId() != null && artifact.getArtifactId() != null && artifact.getVersion() != null) {
                        return artifact;
                    }
                } catch (IllegalArgumentException ignored) {
                }
                index = summaryMessage.indexOf(':', index + 1);
            }
            return null;
        }
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
