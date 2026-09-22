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
package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Resolves annotation processor arguments from discovered visitors and application.toml. */
final class ProcessorOptions {
    private static final String CACHE_FILE = "annotation-processor-options.properties";
    private static final String OPENAPI_ENABLED = "micronaut.openapi.enabled";
    private static final Map<String, String> DEFAULTS = Map.of(
        "micronaut.openapi.views.spec", "swagger-ui.enabled=true,redoc.enabled=true"
    );

    private ProcessorOptions() {
    }

    /**
     * The type checking and static compilation settings of a run, resolved from the flags and the
     * project model. They travel as {@code -A} options, so the processor source cache fingerprints
     * them without further work: switching a mode on or off re-processes the sources.
     *
     * @param typeCheckMode           off, warn or error
     * @param staticCompilationMode   off, annotated or all
     * @param staticCompilationReport the report directory, given a sub-directory per pass
     * @param staticCompilationStrict whether an unhonoured CompileStatic fails the build
     */
    record ProcessorSettings(String typeCheckMode,
                             String staticCompilationMode,
                             Path staticCompilationReport,
                             boolean staticCompilationStrict) {
        static final ProcessorSettings DEFAULT = new ProcessorSettings("off", "off", null, false);
        static final String TYPE_CHECK_OPTION = "micronaut.python.typecheck";
        static final String STATIC_COMPILATION_OPTION = "micronaut.python.compile.static";
        static final String STATIC_COMPILATION_REPORT_OPTION = "micronaut.python.compile.static.report";
        static final String STATIC_COMPILATION_STRICT_OPTION = "micronaut.python.compile.static.strict";

        boolean typeChecks() {
            return !"off".equals(typeCheckMode);
        }

        boolean compilesStatically() {
            return !"off".equals(staticCompilationMode);
        }

        /**
         * @return The settings a pass runs with, for the console, or {@code null} when both are off
         */
        String describe() {
            List<String> parts = new ArrayList<>();
            if (typeChecks()) {
                parts.add("type-check " + typeCheckMode);
            }
            if (compilesStatically()) {
                parts.add("static " + staticCompilationMode + (staticCompilationStrict ? " (strict)" : ""));
            }
            return parts.isEmpty() ? null : String.join(" · ", parts);
        }

        /**
         * @param testPass Whether the test pass is meant
         * @return The report directory of the pass, or {@code null} when nothing is compiled
         */
        Path reportDirectory(boolean testPass) {
            return compilesStatically() && staticCompilationReport != null
                ? staticCompilationReport.resolve(testPass ? "test" : "main").toAbsolutePath().normalize()
                : null;
        }
    }

    static List<String> resolve(Path root, PyprojectModel model, List<String> explicit, boolean testPass) {
        return resolve(root, model, explicit, testPass, ProcessorSettings.DEFAULT);
    }

    static List<String> resolve(Path root, PyprojectModel model, List<String> explicit, boolean testPass, ProcessorSettings settings) {
        Set<String> supported = readSupported((ExternalProjectLayout.isExternal(root)
            ? ExternalProjectLayout.outputDirectory(root) : root.resolve("__pyronaut__")).resolve(CACHE_FILE));
        Map<String, String> values = new TreeMap<>();
        DEFAULTS.forEach((key, value) -> {
            if (supported.contains(key)) {
                values.put(key, value);
            }
        });
        loadApplicationOptions(root, model, supported, values);
        // only settings that are on are emitted: a project with both off keeps its option list, and its cache hits
        if (settings.typeChecks()) {
            values.put(ProcessorSettings.TYPE_CHECK_OPTION, settings.typeCheckMode());
        }
        if (settings.compilesStatically()) {
            values.put(ProcessorSettings.STATIC_COMPILATION_OPTION, settings.staticCompilationMode());
            Path report = settings.reportDirectory(testPass);
            if (report != null) {
                values.put(ProcessorSettings.STATIC_COMPILATION_REPORT_OPTION, report.toString());
            }
            if (settings.staticCompilationStrict()) {
                values.put(ProcessorSettings.STATIC_COMPILATION_STRICT_OPTION, "true");
            }
        }
        Map<String, String> manual = new LinkedHashMap<>();
        List<String> raw = new ArrayList<>();
        for (String option : explicit == null ? List.<String>of() : explicit) {
            String normalized = option.startsWith("-A") ? option.substring(2) : option;
            int equals = normalized.indexOf('=');
            if (equals > 0) {
                manual.put(normalized.substring(0, equals), normalized.substring(equals + 1));
            } else {
                raw.add(option);
            }
        }
        manual.forEach((key, value) -> {
            if (supported.contains(key) || !key.startsWith("micronaut.openapi.")) {
                values.put(key, value);
            }
        });
        if (testPass && supported.contains(OPENAPI_ENABLED)) {
            values.put(OPENAPI_ENABLED, "false");
        }
        List<String> result = new ArrayList<>(raw);
        values.forEach((key, value) -> result.add("-A" + key + "=" + value));
        return List.copyOf(result);
    }

    private static Set<String> readSupported(Path cacheFile) {
        Properties properties = new Properties();
        if (Files.isRegularFile(cacheFile)) {
            try (var reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException ignored) {
                // A missing or unreadable cache simply means no discovered options.
            }
        }
        String value = properties.getProperty("options", "");
        Set<String> result = new LinkedHashSet<>();
        for (String option : value.split(",")) {
            if (!option.isBlank()) {
                result.add(option.trim());
            }
        }
        return result;
    }

    private static Path applicationToml(Path root, PyprojectModel model) {
        String resources = model == null || model.pyronaut() == null || model.pyronaut().sources() == null
            ? "src/main/resources" : model.pyronaut().sources().resources();
        Path path = Path.of(resources);
        return (path.isAbsolute() ? path : root.resolve(path)).normalize().resolve("application.toml");
    }

    private static void loadApplicationOptions(Path root,
                                                PyprojectModel model,
                                                Set<String> supported,
                                                Map<String, String> values) {
        Path resources = applicationToml(root, model).getParent();
        if (!Files.isDirectory(resources)) {
            return;
        }
        try {
            URL url = resources.toUri().toURL();
            try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, ProcessorOptions.class.getClassLoader());
                 io.micronaut.context.ApplicationContext context = io.micronaut.context.ApplicationContext.builder(loader)
                     .deduceEnvironment(false)
                     .deducePackage(false)
                     .exclude("io.micronaut.logging.*")
                     .build()) {
                context.getEnvironment().start();
                for (String key : supported) {
                    context.getProperty(key, Object.class).ifPresent(value -> values.put(key, scalar(value)));
                }
            }
        } catch (Exception e) {
            System.err.println("Unable to load application configuration from " + resources + ": " + e.getMessage());
        }
    }

    private static String scalar(Object value) {
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            iterable.forEach(item -> values.add(scalar(item)));
            return String.join(",", values);
        }
        return String.valueOf(value);
    }
}
