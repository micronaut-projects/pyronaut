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
package io.micronaut.pyronaut.config.model;

import io.micronaut.core.annotation.Internal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal resolved layout for a Java project managed by an external build.
 * The format is deliberately a line-oriented properties file so it remains
 * usable by the native launchers without a JSON runtime dependency.
 */
@Internal
public record ExternalProjectLayout(ProjectKind kind,
                                    List<Path> mainJavaSources,
                                    List<Path> testJavaSources,
                                    List<Path> mainResources,
                                    List<Path> testResources,
                                    List<Path> buildClasspath,
                                    List<Path> runtimeClasspath,
                                    List<Path> developmentRuntimeClasspath,
                                    List<Path> testClasspath,
                                    List<Path> annotationProcessorClasspath) {
    public static final String FILE_NAME = "project-layout.properties";

    public ExternalProjectLayout {
        kind = kind == null ? ProjectKind.PYPROJECT : kind;
        mainJavaSources = copy(mainJavaSources);
        testJavaSources = copy(testJavaSources);
        mainResources = copy(mainResources);
        testResources = copy(testResources);
        buildClasspath = copy(buildClasspath);
        runtimeClasspath = copy(runtimeClasspath);
        developmentRuntimeClasspath = copy(developmentRuntimeClasspath);
        testClasspath = copy(testClasspath);
        annotationProcessorClasspath = copy(annotationProcessorClasspath);
    }

    /**
     * Compatibility constructor for layouts written before annotation processors were persisted separately.
     */
    public ExternalProjectLayout(ProjectKind kind,
                                 List<Path> mainJavaSources,
                                 List<Path> testJavaSources,
                                 List<Path> mainResources,
                                 List<Path> testResources,
                                 List<Path> buildClasspath,
                                 List<Path> runtimeClasspath,
                                 List<Path> developmentRuntimeClasspath,
                                 List<Path> testClasspath) {
        this(kind, mainJavaSources, testJavaSources, mainResources, testResources,
            buildClasspath, runtimeClasspath, developmentRuntimeClasspath, testClasspath, List.of());
    }

    private static List<Path> copy(List<Path> paths) {
        return paths == null ? List.of() : paths.stream().map(Path::toAbsolutePath).map(Path::normalize).distinct().toList();
    }

    public static ProjectKind detect(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return ProjectKind.MAVEN;
        }
        for (String descriptor : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            if (Files.isRegularFile(root.resolve(descriptor))) {
                return ProjectKind.GRADLE;
            }
        }
        return ProjectKind.PYPROJECT;
    }

    public static boolean isExternal(Path root) {
        return detect(root) != ProjectKind.PYPROJECT;
    }

    public static Path file(Path root) {
        return root.resolve("__pyronaut__").resolve(FILE_NAME);
    }

    public void write(Path root) throws IOException {
        Path target = file(root);
        Files.createDirectories(target.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("kind=" + kind.name());
        write(lines, "mainJavaSources", mainJavaSources);
        write(lines, "testJavaSources", testJavaSources);
        write(lines, "mainResources", mainResources);
        write(lines, "testResources", testResources);
        write(lines, "buildClasspath", buildClasspath);
        write(lines, "runtimeClasspath", runtimeClasspath);
        write(lines, "developmentRuntimeClasspath", developmentRuntimeClasspath);
        write(lines, "testClasspath", testClasspath);
        write(lines, "annotationProcessorClasspath", annotationProcessorClasspath);
        Files.write(target, lines, StandardCharsets.UTF_8);
    }

    private static void write(List<String> lines, String key, List<Path> values) {
        lines.add(key + "=" + values.stream().map(Path::toString).map(ExternalProjectLayout::escape).reduce((a, b) -> a + java.io.File.pathSeparator + b).orElse(""));
    }

    public static ExternalProjectLayout read(Path root) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file(root), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return new ExternalProjectLayout(
            ProjectKind.valueOf(values.getOrDefault("kind", ProjectKind.PYPROJECT.name())),
            paths(values.get("mainJavaSources")), paths(values.get("testJavaSources")),
            paths(values.get("mainResources")), paths(values.get("testResources")),
            paths(values.get("buildClasspath")), paths(values.get("runtimeClasspath")),
            paths(values.get("developmentRuntimeClasspath")), paths(values.get("testClasspath")),
            paths(values.get("annotationProcessorClasspath"))
        );
    }

    private static List<Path> paths(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .filter(s -> !s.isBlank()).map(ExternalProjectLayout::unescape).map(Path::of).toList();
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("=", "\\="); }
    private static String unescape(String value) { return value.replace("\\=", "=").replace("\\\\", "\\"); }

    public enum ProjectKind { PYPROJECT, MAVEN, GRADLE }
}
