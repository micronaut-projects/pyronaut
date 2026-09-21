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
package io.micronaut.pyronaut.jarbuild;

import io.micronaut.pyronaut.config.terminal.PhaseReporter;
import io.micronaut.pyronaut.config.terminal.Terminal;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command line adapter for {@link FatJarPackager}.
 */
@CommandLine.Command(name = "pyronaut-jar-build", mixinStandardHelpOptions = true,
    description = "Build an indexed runnable Pyronaut FAT JAR")
public final class PyronautJarBuildMain implements Callable<Integer> {
    private static final String DEFAULT_MAIN_CLASS = "io.micronaut.pyronaut.run.PyronautRunMain";

    @CommandLine.Option(names = "--output", required = true, description = "Output JAR")
    Path output;

    @CommandLine.Option(names = "--classes-dir", required = true, description = "Processed application classes")
    Path classesDirectory;

    @CommandLine.Option(names = "--resource-dir", description = "Application resource directory")
    List<Path> resourceDirectories = new ArrayList<>();

    @CommandLine.Option(names = "--classpath-file", required = true, description = "Line-delimited ordered runtime classpath")
    Path classpathFile;

    @CommandLine.Option(names = "--name", required = true, description = "Application name")
    String applicationName;

    @CommandLine.Option(names = "--version", required = true, description = "Application version")
    String applicationVersion;

    @CommandLine.Option(names = "--main-class", defaultValue = DEFAULT_MAIN_CLASS, description = "Target main class")
    String mainClass;

    /**
     * Creates the command line adapter.
     */
    public PyronautJarBuildMain() {
    }

    @Override
    public Integer call() {
        try (PhaseReporter progress = PhaseReporter.create()) {
            PhaseReporter.Phase packaging = progress.start("Packaging FAT JAR");
            try {
                List<Path> classpath = Files.readAllLines(classpathFile).stream()
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Path::of)
                    .toList();
                FatJarRequest request = FatJarRequest.builder(output, classesDirectory, mainClass)
                    .applicationName(applicationName)
                    .applicationVersion(applicationVersion)
                    .resourceDirectories(resourceDirectories)
                    .classpath(classpath)
                    .build();
                FatJarResult result = new FatJarPackager().packageApplication(request, (index, total, entry) ->
                    packaging.detail(index + "/" + total + " dependencies"));
                packaging.done("Packaged " + result.output().getFileName() + " (" + result.archiveEntries() + " entries, "
                    + Terminal.formatBytes(Files.size(result.output())) + ")");
                return 0;
            } catch (Exception e) {
                packaging.fail("FAT JAR build failed: " + e.getMessage());
                return 8;
            }
        }
    }

    /**
     * Runs the command line adapter.
     *
     * @param args command arguments
     */
    public static void main(String[] args) {
        Terminal.notifyLaunched();
        System.exit(new CommandLine(new PyronautJarBuildMain()).execute(args));
    }
}
