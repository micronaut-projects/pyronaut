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
package io.micronaut.pyronaut.run;

import picocli.CommandLine;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Entry point for {@code pyronaut-run}.
 */
@CommandLine.Command(name = "pyronaut-run", mixinStandardHelpOptions = true, description = "Run a processed Pyronaut application")
public final class PyronautRunMain implements Callable<Integer> {
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_MAIN_CLASS = "pyronaut_application.PyronautMain";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--classes-dir", defaultValue = DEFAULT_CLASSES_DIR, description = "Processed classes directory")
    Path classesDir = Path.of(DEFAULT_CLASSES_DIR);

    @CommandLine.Option(names = "--config-dir", defaultValue = DEFAULT_CONFIG_DIR, description = "Configuration directory")
    Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @CommandLine.Option(names = "--classpath", split = "${sys:path.separator}", description = "Override runtime classpath")
    List<Path> classpath;

    @CommandLine.Option(names = "--main-class", defaultValue = DEFAULT_MAIN_CLASS, description = "Main class to invoke")
    String mainClass = DEFAULT_MAIN_CLASS;

    @CommandLine.Parameters
    List<String> appArgs = List.of();

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            List<Path> runtimeClasspath = classpath == null || classpath.isEmpty()
                ? readManifest(root.resolve(".pytest_cache").resolve("resolved-runtime-dependencies"))
                : classpath;

            Path resolvedClassesDir = root.resolve(classesDir).normalize();
            if (!Files.isDirectory(resolvedClassesDir)) {
                System.err.println("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
                return 8;
            }

            List<URL> urls = new ArrayList<>();
            for (Path path : runtimeClasspath) {
                urls.add(path.toUri().toURL());
            }
            urls.add(resolvedClassesDir.toUri().toURL());

            Path resolvedConfigDir = root.resolve(configDir).normalize();
            if (Files.isDirectory(resolvedConfigDir)) {
                urls.add(resolvedConfigDir.toUri().toURL());
            }

            try (URLClassLoader classLoader = new URLClassLoader(urls.toArray(URL[]::new), Thread.currentThread().getContextClassLoader())) {
                Thread.currentThread().setContextClassLoader(classLoader);
                Class<?> loadedClass = classLoader.loadClass(mainClass);
                var method = loadedClass.getMethod("main", String[].class);
                method.invoke(null, (Object) appArgs.toArray(String[]::new));
            }
            return 0;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        }
    }

    private static List<Path> readManifest(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("Missing runtime scope cache. Run pyronaut install first. (missing: " + file + ")");
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Path::of)
                .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading runtime classpath manifest: " + file, e);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautRunMain()).execute(args);
        System.exit(exitCode);
    }
}
