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
package io.micronaut.pyronaut.dev;

import io.micronaut.dev.DevRuntime;
import io.micronaut.dev.MicronautDevMain;
import io.micronaut.dev.loader.DevClassLoader;
import io.micronaut.dev.manifest.DevManifest;
import io.micronaut.dev.manifest.ResourceRoot;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.run.PyronautRunMain;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Development mode on the JVM toolchain: the application runs in the reloading development runtime
 * of {@code micronaut-dev}, which watches the Python and Java sources and the configuration,
 * compiles what changed in process with the Pyronaut compiler, and starts the application again in a
 * new class loader generation, without a new JVM. The runtime jars, the GraalPy engine and what the
 * runtime retains stay loaded.
 *
 * <p>The project's dependencies are the parent tier; the processed classes are the reloadable tier,
 * and the configuration directories are read live. A change to the dependencies needs a new process,
 * which the CLI starts when {@code pyproject.toml} changes.</p>
 *
 * <p>The native toolchain cannot do this: a native image has no instrumentation API and defines no
 * class at runtime, so there the CLI keeps restarting the process. {@code micronaut-dev} is on the
 * classpath only when the CLI runs this mode, which puts this module's jar beside the run command's
 * classpath; the run command loads this class by name, so that neither launcher nor native image
 * depends on {@code micronaut-dev}.</p>
 */
public final class PyronautDevReload extends MicronautDevMain implements PyronautRunMain.DevelopmentRuntime {

    private static final String PYRONAUT_DIR = "__pyronaut__";
    private static final String DEV_DIR = "micronaut-dev";
    private static final String MAIN_CLASS = "pyronaut_application.PyronautMain";
    private static final String BUILD_DEPENDENCIES_MANIFEST = "resolved-build-dependencies";
    private static final String PROCESSOR_OPTIONS = "resolved-processor-options";
    private static final String PROCESSOR_MAIN_HASH = "processor-main.sha256";
    private static final String PROCESSOR_MAIN_INCREMENTAL = "incremental/main";

    private ClassLoader parent;
    private Path generations;
    private Callable<Boolean> launcher;

    /**
     * Created by name by {@link PyronautRunMain}.
     */
    public PyronautDevReload() {
    }

    /**
     * Starts the application in the development runtime, returning once the first generation started.
     *
     * @param root The project directory
     * @param layout The layout the run command resolved
     * @param model The project model
     * @param appArgs The application's arguments
     * @param launcher Starts the application in a generation, its loader the thread context loader
     * @throws IOException if the manifest cannot be written
     */
    @Override
    public void start(Path root,
                      PyronautRunMain.ResolvedProjectLayout layout,
                      PyprojectModel model,
                      List<String> appArgs,
                      Callable<Boolean> launcher) throws IOException {
        Path classes = layout.processedClassesRoot().toAbsolutePath().normalize();
        PyprojectModel.Sources sources = model.pyronaut().sources();
        Path python = resolve(root, sources.python());
        Path java = resolve(root, sources.java());
        Set<Path> additionalResources = new LinkedHashSet<>();
        for (String resource : sources.additionalResources()) {
            additionalResources.add(resolve(root, resource));
        }
        Tiers tiers = tiers(layout.classpathUrls(), classes, additionalResources);
        List<Path> config = tiers.config();
        List<Path> additional = tiers.additional();
        List<Path> runtime = tiers.runtime();
        ClassLoader launcherLoader = PyronautDevReload.class.getClassLoader();
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context.getParent() != null) {
            // the parent the run command gave the project's loader
            launcherLoader = context.getParent();
        }
        URL[] urls = new URL[runtime.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = runtime.get(i).toUri().toURL();
        }
        this.parent = new URLClassLoader("pyronaut-dev-runtime", urls, launcherLoader);
        this.launcher = launcher;

        Path pyronautDir = root.resolve(PYRONAUT_DIR);
        // from here on the development runtime writes the processed classes: the next pyronaut process must not
        // take them for its own output, by its source fingerprint or its incremental state
        Files.deleteIfExists(pyronautDir.resolve(PROCESSOR_MAIN_HASH));
        deleteRecursively(pyronautDir.resolve(PROCESSOR_MAIN_INCREMENTAL));
        Path devDir = Files.createDirectories(pyronautDir.resolve(DEV_DIR));
        this.generations = devDir.resolve("generations");
        List<String> options = readStrings(pyronautDir.resolve(PROCESSOR_OPTIONS));
        Path manifestFile = writeManifest(root, devDir, classes, python, java, config, additional, runtime,
            readLines(pyronautDir.resolve(BUILD_DEPENDENCIES_MANIFEST)), options);

        DevRuntime devRuntime = launch(DevManifest.load(manifestFile), appArgs.toArray(String[]::new));
        Runtime.getRuntime().addShutdownHook(new Thread(devRuntime::close, "pyronaut-dev-shutdown"));
    }

    /**
     * Writes the manifest the development runtime reads, with its classpaths and compiler options in
     * argument files beside it.
     */
    static Path writeManifest(Path root,
                              Path devDir,
                              Path classes,
                              Path python,
                              Path java,
                              List<Path> config,
                              List<Path> additional,
                              List<Path> compileClasspath,
                              List<Path> processorPath,
                              List<String> options) throws IOException {
        writeLines(devDir.resolve("compile.argfile"), compileClasspath.stream().map(Path::toString).toList());
        writeLines(devDir.resolve("processor.argfile"), processorPath.stream().map(Path::toString).toList());
        writeLines(devDir.resolve("options.argfile"), options);
        Properties properties = new Properties();
        properties.setProperty("micronaut.dev.main-class", MAIN_CLASS);
        properties.setProperty("micronaut.dev.project-dir", root.toString());
        properties.setProperty("micronaut.dev.strategy", "restart");
        properties.setProperty("micronaut.dev.reloadable", classes.toString());
        properties.setProperty("micronaut.dev.compile-classpath", "@compile.argfile");
        properties.setProperty("micronaut.dev.processor-path", "@processor.argfile");
        if (Files.isDirectory(python)) {
            properties.setProperty("micronaut.dev.sources.python", python.toString());
            properties.setProperty("micronaut.dev.compile.python.output", classes.toString());
            properties.setProperty("micronaut.dev.compile.python.options", "@options.argfile");
        }
        if (Files.isDirectory(java)) {
            // compiled with the Python sources, into the same output
            properties.setProperty("micronaut.dev.sources.java", java.toString());
            properties.setProperty("micronaut.dev.compile.java.output", classes.toString());
            properties.setProperty("micronaut.dev.compile.java.options", "@options.argfile");
        }
        List<String> configRoots = config.stream().filter(Files::isDirectory).map(Path::toString).toList();
        if (!configRoots.isEmpty()) {
            properties.setProperty("micronaut.dev.resources.config", String.join(",", configRoots));
        }
        List<String> other = additional.stream().filter(Files::isDirectory).map(Path::toString).toList();
        if (!other.isEmpty()) {
            properties.setProperty("micronaut.dev.resources.other", String.join(",", other));
        }
        Path manifest = devDir.resolve("dev.properties");
        try (OutputStream out = Files.newOutputStream(manifest)) {
            properties.store(out, "Written by pyronaut dev for the micronaut-dev runtime");
        }
        return manifest;
    }

    /**
     * Sorts the run command's classpath: the processed classes and the resource directories it resolved, a
     * {@code --config-dir} included, are the reloadable tier, and the jars are the parent tier.
     */
    static Tiers tiers(List<URL> classpath, Path classes, Set<Path> additionalResources) {
        List<Path> config = new ArrayList<>();
        List<Path> additional = new ArrayList<>();
        List<Path> runtime = new ArrayList<>();
        for (URL url : classpath) {
            Path entry = path(url);
            if (entry.equals(classes)) {
                continue;
            }
            if (!Files.isDirectory(entry)) {
                runtime.add(entry);
            } else if (additionalResources.contains(entry)) {
                additional.add(entry);
            } else {
                config.add(entry);
            }
        }
        return new Tiers(config, additional, runtime);
    }

    @Override
    protected ClassLoader parentClassLoader() {
        return parent;
    }

    @Override
    protected DevClassLoader createClassLoader(DevManifest manifest) {
        // the generations live under __pyronaut__ rather than build/
        List<Path> live = new ArrayList<>();
        for (ResourceRoot resourceRoot : manifest.resourceRoots()) {
            if (Files.isDirectory(resourceRoot.path())) {
                live.add(resourceRoot.path());
            }
        }
        return new DevClassLoader(parent, live, manifest.reloadableRoots(), generations);
    }

    @Override
    protected void launchApplication(ClassLoader classLoader, String mainClass, String[] args) throws Exception {
        // the application's threads inherit the generation's loader, as the run command's do the project's
        Thread.currentThread().setContextClassLoader(classLoader);
        launcher.call();
    }

    private static Path resolve(Path root, String configured) {
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    private static Path path(URL url) {
        try {
            return Path.of(url.toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a file URL: " + url, e);
        }
    }

    private static List<Path> readLines(Path file) {
        return readStrings(file).stream().map(Path::of).toList();
    }

    private static List<String> readStrings(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * The run command's classpath, by tier.
     *
     * @param config The configuration directories
     * @param additional The additional resource directories
     * @param runtime The jars
     */
    record Tiers(List<Path> config, List<Path> additional, List<Path> runtime) {
    }
}
