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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds shell commands for direct-source IDE launch configurations.
 */
final class DirectSourceLaunchCommand {
    private DirectSourceLaunchCommand() {
    }

    static Commands build(Path projectDir, List<Path> sourceFiles) {
        List<Path> applicationSources = new ArrayList<>();
        List<Path> testSources = new ArrayList<>();
        for (Path source : sourceFiles) {
            (isTestSource(projectDir, source) ? testSources : applicationSources).add(source);
        }
        List<Path> developmentSources = applicationSources.isEmpty() ? sourceFiles : applicationSources;
        String test = applicationSources.isEmpty() || testSources.isEmpty()
            ? null
            : command(projectDir, "test", applicationSources, testSources);
        return new Commands(command(projectDir, "dev", developmentSources, List.of()), test);
    }

    private static String command(Path projectDir,
                                  String action,
                                  List<Path> applicationSources,
                                  List<Path> testSources) {
        StringBuilder command = new StringBuilder("pyronaut ").append(action);
        appendSources(command, projectDir, applicationSources);
        if (!testSources.isEmpty()) {
            command.append(" --");
            appendSources(command, projectDir, testSources);
        }
        return command.toString();
    }

    private static void appendSources(StringBuilder command, Path projectDir, List<Path> sourceFiles) {
        sourceFiles.stream()
            .map(path -> sourceArgument(projectDir, path))
            .distinct()
            .sorted()
            .forEach(path -> command.append(' ').append(quote(path)));
    }

    private static String sourceArgument(Path projectDir, Path source) {
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path argument = normalizedSource.startsWith(normalizedProject)
            ? normalizedProject.relativize(normalizedSource)
            : normalizedSource;
        return argument.toString().replace('\\', '/');
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static boolean isTestSource(Path projectDir, Path source) {
        String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith("test.java")
            || fileName.endsWith("tests.java")
            || fileName.endsWith("testcase.java")
            || fileName.endsWith("spec.java")
            || fileName.startsWith("test_")
            || fileName.endsWith("_test.py")) {
            return true;
        }
        // Only inspect the path below the project directory so that a project
        // located under a directory such as ".../tests/app" is not treated as
        // consisting solely of test sources.
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path relative = normalizedSource.startsWith(normalizedProject)
            ? normalizedProject.relativize(normalizedSource)
            : normalizedSource.getFileName();
        for (Path segment : relative) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if ("test".equals(name) || "tests".equals(name) || "test-java".equals(name)) {
                return true;
            }
        }
        return false;
    }

    record Commands(String development, String test) {
    }
}
