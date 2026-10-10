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
    /** The compiler option naming the Python classes to generate no Java type for. */
    static final String PYTHON_EXCLUDE = "micronaut.python.exclude";
    private static final Map<String, String> DEFAULTS = Map.of(
        "micronaut.openapi.views.spec", "swagger-ui.enabled=true,redoc.enabled=true"
    );

    private ProcessorOptions() {
    }

    static List<String> resolve(Path root, PyprojectModel model, List<String> explicit, boolean testPass) {
        Set<String> supported = readSupported((ExternalProjectLayout.isExternal(root)
            ? ExternalProjectLayout.outputDirectory(root) : root.resolve("__pyronaut__")).resolve(CACHE_FILE));
        Map<String, String> values = new TreeMap<>();
        DEFAULTS.forEach((key, value) -> {
            if (supported.contains(key)) {
                values.put(key, value);
            }
        });
        loadApplicationOptions(root, model, supported, values);
        List<String> exclude = model == null || model.pyronaut() == null || model.pyronaut().processor() == null
            ? List.of() : model.pyronaut().processor().exclude();
        if (!exclude.isEmpty()) {
            values.put(PYTHON_EXCLUDE, String.join(",", exclude));
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
