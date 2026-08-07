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
package io.micronaut.pyronaut.createapp;

import io.micronaut.context.ApplicationContext;
import io.micronaut.projectgen.core.feature.AvailableFeatures;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.io.PreviewGenerator;
import io.micronaut.projectgen.core.options.ConfigurationFormat;
import io.micronaut.projectgen.core.options.GenericOptionsBuilder;
import io.micronaut.projectgen.core.options.Language;
import io.micronaut.projectgen.core.options.Options;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.pyronaut.projectgen.PyronautProjectSettings;
import io.micronaut.pyronaut.projectgen.PyronautProjectSettingsContext;
import jakarta.inject.Singleton;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Command line entry point that creates a Pyronaut application from the
 * Micronaut ProjectGen preview output.
 */
@Singleton
@CommandLine.Command(name = "pyronaut-create", mixinStandardHelpOptions = true, description = "Create a Pyronaut application")
@SuppressWarnings("checkstyle:DeclarationOrder")
public final class PyronautCreateAppMain implements Callable<Integer> {
    /**
     * Default project version used when the caller does not pass {@code --version}.
     */
    private static final String DEFAULT_PROJECT_VERSION = "0.1.0";

    /**
     * Pattern used to validate each segment of a Python package/module name.
     */
    private static final Pattern PYTHON_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Python reserved words that cannot be used as package/module name segments.
     */
    private static final Set<String> PYTHON_KEYWORDS = Set.of(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
        "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
        "with", "yield"
    );

    /**
     * Default VS Code launch configuration written into new scaffolded apps.
     */
    private static final String VSCODE_LAUNCH_JSON = """
        {
          "version": "0.2.0",
          "configurations": [
            {
              "name": "Pyronaut: Run",
              "type": "node-terminal",
              "request": "launch",
              "command": "pyronaut dev"
            },
            {
              "name": "Pyronaut: Debug VM",
              "type": "node-terminal",
              "request": "launch",
              "command": "pyronaut dev --debug-vm"
            },
            {
                // Enable DAP via 'pyronaut run -Dgraalpy.engine.options.dap=true [...]'
                "name": "Pyronaut: Attach Python Debugger",
                "type": "node",
                "request": "attach",
                "debugServer": 4711
            }
          ]
        }
        """;

    /**
     * Project name to generate. When absent with {@code --inplace}, the output directory name is used.
     */
    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "NAME", description = "Project name")
    String name;

    /**
     * Feature names requested for the generated project.
     */
    @CommandLine.Option(names = "--features", split = ",", description = "Comma-separated feature names")
    List<String> features = List.of();

    /**
     * Whether to print the Pyronaut-compatible feature catalog and exit.
     */
    @CommandLine.Option(names = "--list-features", description = "List Pyronaut-compatible features")
    boolean listFeatures;

    /**
     * Whether to write generated files directly into the selected output directory.
     */
    @CommandLine.Option(names = "--inplace", description = "Write into the selected output directory")
    boolean inplace;

    /**
     * Output directory or parent directory for the generated project.
     */
    @CommandLine.Option(names = "--output", paramLabel = "DIR", description = "Output directory")
    Path output;

    /**
     * Python package/module name to use for generated sources.
     */
    @CommandLine.Option(names = "--package", paramLabel = "MODULE", description = "Python package/module name")
    String packageName;

    /**
     * Python project version written to generated metadata.
     */
    @CommandLine.Option(names = "--version", defaultValue = DEFAULT_PROJECT_VERSION, description = "Project version")
    String version = DEFAULT_PROJECT_VERSION;

    /**
     * Micronaut platform version override for generated Pyronaut settings.
     */
    @CommandLine.Option(names = "--micronaut-version", description = "Micronaut platform version")
    String micronautVersion;

    /**
     * Repository names, URLs, or local paths to write into generated Pyronaut settings.
     */
    @CommandLine.Option(names = "--repository", split = ",", description = "Maven repository name, URL, or path. Repeatable.")
    List<String> repositories = List.of();

    /**
     * ProjectGen preview generator used to produce the file map before writing to disk.
     */
    private final PreviewGenerator previewGenerator;

    /**
     * Available feature providers used when listing Pyronaut-compatible features.
     */
    private final List<AvailableFeatures> availableFeatures;

    public PyronautCreateAppMain(PreviewGenerator previewGenerator,
                                 List<AvailableFeatures> availableFeatures) {
        this.previewGenerator = previewGenerator;
        this.availableFeatures = availableFeatures;
    }

    @Override
    public Integer call() {
        try {
            if (listFeatures) {
                printFeatures();
                return CommandLine.ExitCode.OK;
            }

            Path cwd = Path.of("").toAbsolutePath().normalize();
            Path selectedOutput = output == null ? cwd : output.toAbsolutePath().normalize();
            String projectName = resolveProjectName(selectedOutput);
            String pythonPackage = packageName == null || packageName.isBlank()
                ? defaultPackageName(projectName)
                : packageName.trim();
            validatePackageName(pythonPackage);
            Path target = inplace ? selectedOutput : selectedOutput.resolve(projectName).normalize();

            Options options = GenericOptionsBuilder.builder()
                .name(projectName)
                .packageName(pythonPackage)
                .version(version)
                .language(Language.PYTHON)
                .configurationFormat(ConfigurationFormat.TOML)
                .features(normalizedFeatures())
                .build();
            PyronautProjectSettings settings = new PyronautProjectSettings(micronautVersion, normalizedRepositories());
            Map<String, String> project = PyronautProjectSettingsContext.withSettings(
                settings,
                () -> previewGenerator.generate(options)
            );
            project = addDefaultEditorLaunchConfiguration(project);
            validateTarget(target, project, inplace);
            writeProject(target, project);
            commandSpec.commandLine().getOut().println("Created Pyronaut application at " + target);
            return CommandLine.ExitCode.OK;
        } catch (IllegalArgumentException e) {
            commandSpec.commandLine().getErr().println(e.getMessage());
            return CommandLine.ExitCode.USAGE;
        } catch (Exception e) {
            commandSpec.commandLine().getErr().println("Create-app failed: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Picocli command metadata injected for command output and error streams.
     */
    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    private void printFeatures() {
        Options options = GenericOptionsBuilder.builder()
            .name("demo")
            .packageName("demo")
            .version(DEFAULT_PROJECT_VERSION)
            .language(Language.PYTHON)
            .configurationFormat(ConfigurationFormat.TOML)
            .build();
        PrintWriter out = commandSpec.commandLine().getOut();
        availableFeatures.stream()
            .filter(features -> features.supports(options))
            .findFirst()
            .orElseThrow()
            .getFeatures()
            .sorted(Comparator.comparing(Feature::getName))
            .forEach(feature -> {
                String description = feature.getDescription();
                if (description == null || description.isBlank()) {
                    out.println(feature.getName());
                } else {
                    out.println(feature.getName() + " - " + description);
                }
            });
    }

    private String resolveProjectName(Path selectedOutput) {
        String projectName;
        if (name != null && !name.isBlank()) {
            projectName = name.trim();
        } else if (inplace) {
            Path fileName = selectedOutput.getFileName();
            if (fileName != null && !fileName.toString().isBlank()) {
                projectName = fileName.toString();
            } else {
                throw new IllegalArgumentException("Missing project NAME. Use --inplace to derive it from the output directory.");
            }
        } else {
            throw new IllegalArgumentException("Missing project NAME. Use --inplace to derive it from the output directory.");
        }
        validateProjectName(projectName);
        return projectName;
    }

    private List<String> normalizedFeatures() {
        return normalizedValues(features);
    }

    private List<String> normalizedRepositories() {
        return normalizedValues(repositories);
    }

    private static List<String> normalizedValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private static String defaultPackageName(String projectName) {
        String normalized = projectName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "app";
        }
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') {
            normalized = "app_" + normalized;
        }
        if (PYTHON_KEYWORDS.contains(normalized)) {
            normalized = normalized + "_app";
        }
        return normalized;
    }

    private static void validatePackageName(String value) {
        for (String part : value.split("\\.")) {
            if (!PYTHON_IDENTIFIER.matcher(part).matches() || PYTHON_KEYWORDS.contains(part)) {
                throw new IllegalArgumentException("Invalid Python package/module name: " + value);
            }
        }
    }

    private static void validateProjectName(String value) {
        if (".".equals(value) || "..".equals(value) || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("Invalid project NAME: " + value);
        }
    }

    private static Map<String, String> addDefaultEditorLaunchConfiguration(Map<String, String> project) {
        Map<String, String> files = new LinkedHashMap<>(project);
        files.put(".vscode/launch.json", VSCODE_LAUNCH_JSON);
        return files;
    }

    private static void validateTarget(Path target, Map<String, String> project, boolean inplace) throws IOException {
        if (Files.exists(target) && !inplace) {
            throw new IllegalArgumentException("Target directory already exists: " + target);
        }
        if (!Files.exists(target)) {
            return;
        }
        List<Path> conflicts = new ArrayList<>();
        for (String relativePath : project.keySet()) {
            Path candidate = target.resolve(relativePath).normalize();
            if (!candidate.startsWith(target)) {
                throw new IllegalArgumentException("Generated path escapes target directory: " + relativePath);
            }
            if (Files.isDirectory(candidate)) {
                conflicts.add(candidate);
            } else if (Files.isRegularFile(candidate) && Files.size(candidate) > 0L) {
                conflicts.add(candidate);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalArgumentException("Target contains conflicting generated files: " + conflicts);
        }
    }

    private static void writeProject(Path target, Map<String, String> project) throws IOException {
        Files.createDirectories(target);
        for (Map.Entry<String, String> entry : project.entrySet()) {
            Path file = target.resolve(entry.getKey()).normalize();
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) {
        PyronautLauncherLogging.initialize();
        int exitCode;
        try (ApplicationContext context = ApplicationContext.run()) {
            exitCode = new CommandLine(context.getBean(PyronautCreateAppMain.class)).execute(args);
        }
        System.exit(exitCode);
    }
}
