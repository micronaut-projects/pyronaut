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

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.runtime.Micronaut;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Entry point for {@code pyronaut-run}.
 */
@CommandLine.Command(name = "pyronaut-run", mixinStandardHelpOptions = true, description = "Run a processed Pyronaut application")
public final class PyronautRunMain implements Callable<Integer> {
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String RUNTIME_DEPENDENCIES_MANIFEST = "resolved-runtime-dependencies";
    private static final String DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST = "resolved-development-runtime-dependencies";
    private static final String DEFAULT_MAIN_CLASS = "pyronaut_application.PyronautMain";
    private static final List<TestResourcesProperty> TEST_RESOURCES_PROPERTIES = List.of(
        new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_URI", "micronaut.test.resources.server.uri"),
        new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", "micronaut.test.resources.server.access.token"),
        new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", "micronaut.test.resources.server.client.read.timeout")
    );

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--classes-dir", defaultValue = DEFAULT_CLASSES_DIR, description = "Processed classes directory")
    Path classesDir = Path.of(DEFAULT_CLASSES_DIR);

    @CommandLine.Option(names = "--config-dir", defaultValue = DEFAULT_CONFIG_DIR, description = "Configuration directory")
    Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @CommandLine.Option(names = "--main-class", defaultValue = DEFAULT_MAIN_CLASS, description = "Main class to invoke")
    String mainClass = DEFAULT_MAIN_CLASS;

    @CommandLine.Option(
        names = "--debug-vm",
        description = "Enable JVM JDWP debugging on port 5005 (flag is accepted for orchestrator forwarding)"
    )
    boolean debugVm;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    @CommandLine.Parameters
    List<String> appArgs = List.of();

    private final ClassResolver classResolver;
    private final ContextBootstrapper contextBootstrapper;
    private final ApplicationStarter applicationStarter;
    private final PyprojectModelReader modelReader;

    public PyronautRunMain() {
        this(
            new PyprojectModelReader(),
            (className, classLoader) -> Class.forName(className, true, classLoader),
            GraalPyContextFactory::bootstrapReusableContext,
            PyronautRunMain::startMicronautApplication
        );
    }

    PyronautRunMain(PyprojectModelReader modelReader,
                    ClassResolver classResolver,
                    ContextBootstrapper contextBootstrapper,
                    ApplicationStarter applicationStarter) {
        this.modelReader = modelReader;
        this.classResolver = classResolver;
        this.contextBootstrapper = contextBootstrapper;
        this.applicationStarter = applicationStarter;
    }

    @Override
    public Integer call() {
        initializeJavaHomeIfMissing(() -> System.getenv("JAVA_HOME"));
        Path root = projectDir.toAbsolutePath().normalize();
        ResolvedProjectLayout layout;
        try {
            applyTestResourcesProperties(System.getenv());
            PyprojectModel model = modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME));
            Path resolvedConfigDir = resolveConfiguredPath(root, configDir, DEFAULT_CONFIG_DIR, model.pyronaut().sources().resources(), "--config-dir");
            layout = resolveProjectLayout(root, classesDir, resolvedConfigDir, resolveConfiguredPaths(root, model.pyronaut().sources().additionalResources()));
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        }

        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader applicationClassLoader = layout.applicationClassLoader()) {
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            Class<?> loadedClass = loadConfiguredMainClass(applicationClassLoader);
            contextBootstrapper.bootstrap(applicationClassLoader);
            if (applicationStarter.start(loadedClass, layout.processedClassesRoot(), appArgs)) {
                blockUntilInterrupted();
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    private static boolean startMicronautApplication(Class<?> loadedClass,
                                                     Path resolvedClassesDir,
                                                     List<String> appArgs) {
        Micronaut micronaut = Micronaut.build(appArgs.toArray(String[]::new));
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            micronaut.classLoader(contextClassLoader);
        }
        List<Class<?>> applicationClasses = discoverApplicationClasses(resolvedClassesDir, contextClassLoader);
        if (!applicationClasses.isEmpty()) {
            micronaut.classes(applicationClasses.toArray(Class[]::new));
        }
        if (loadedClass != null) {
            micronaut.mainClass(loadedClass);
        }
        List<String> packages = discoverApplicationPackages(resolvedClassesDir);
        if (!packages.isEmpty()) {
            micronaut.packages(packages.toArray(String[]::new));
        }
        micronaut.start();
        return true;
    }

    static void initializeJavaHomeIfMissing(Supplier<String> javaHomeSupplier) {
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isBlank()) {
            return;
        }
        String javaHome = javaHomeSupplier.get();
        if (javaHome != null && !javaHome.isBlank()) {
            System.setProperty("java.home", javaHome);
        }
    }

    private Class<?> loadConfiguredMainClass(ClassLoader classLoader) throws Exception {
        if (!DEFAULT_MAIN_CLASS.equals(mainClass)) {
            return classResolver.load(mainClass, classLoader);
        }
        try {
            return classResolver.load(mainClass, classLoader);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    static ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path configDir) throws IOException {
        return resolveProjectLayout(root, classesDir, configDir, List.of());
    }

    static ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path configDir, List<Path> additionalResourceDirs) throws IOException {
        Path pyronautDir = root.resolve(DEFAULT_PYRONAUT_DIR).normalize();
        Path resolvedClassesDir = root.resolve(classesDir).normalize();
        if (!Files.isDirectory(resolvedClassesDir)) {
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }

        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        addManifestEntries(urls, resolveRunManifest(pyronautDir));
        urls.add(archiveProcessedClasses(pyronautDir, resolvedClassesDir).toUri().toURL());
        addPathIfDirectory(urls, root.resolve(configDir).normalize());
        addResourceDirectories(urls, additionalResourceDirs);
        return new ResolvedProjectLayout(
            resolvedClassesDir,
            new URLClassLoader(urls.toArray(URL[]::new), resolveApplicationClassLoader())
        );
    }

    private Path resolveConfiguredPath(Path root,
                                       Path cliValue,
                                       String defaultValue,
                                       String configuredValue,
                                       String optionName) {
        if (isExplicitlyConfigured(optionName)) {
            return root.resolve(cliValue).normalize();
        }
        if (Path.of(defaultValue).equals(cliValue)) {
            return root.resolve(configuredValue).normalize();
        }
        return root.resolve(cliValue).normalize();
    }

    private boolean isExplicitlyConfigured(String optionName) {
        return commandSpec != null
            && commandSpec.commandLine() != null
            && commandSpec.commandLine().getParseResult() != null
            && commandSpec.commandLine().getParseResult().hasMatchedOption(optionName);
    }

    private static List<Path> resolveConfiguredPaths(Path root, List<String> configuredDirs) {
        if (configuredDirs == null || configuredDirs.isEmpty()) {
            return List.of();
        }
        return configuredDirs.stream()
            .map(Path::of)
            .map(path -> path.isAbsolute() ? path.normalize() : root.resolve(path).normalize())
            .toList();
    }

    private static ClassLoader resolveApplicationClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : PyronautRunMain.class.getClassLoader();
    }

    private static void addManifestEntries(LinkedHashSet<URL> urls, Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return;
        }
        for (String line : Files.readAllLines(manifest)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            urls.add(Path.of(trimmed).toAbsolutePath().normalize().toUri().toURL());
        }
    }

    private static Path resolveRunManifest(Path pyronautDir) {
        Path developmentManifest = pyronautDir.resolve(DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST);
        if (Files.exists(developmentManifest)) {
            return developmentManifest;
        }
        return pyronautDir.resolve(RUNTIME_DEPENDENCIES_MANIFEST);
    }

    private static void addPathIfDirectory(LinkedHashSet<URL> urls, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            urls.add(path.toAbsolutePath().normalize().toUri().toURL());
        }
    }

    private static void addResourceDirectories(LinkedHashSet<URL> urls, List<Path> resourceDirs) throws IOException {
        for (Path resourceDir : resourceDirs) {
            addPathIfDirectory(urls, resourceDir);
        }
    }

    private static Path archiveProcessedClasses(Path pyronautDir, Path resolvedClassesDir) throws IOException {
        Files.createDirectories(pyronautDir);
        Path archive = pyronautDir.resolve("run-classes.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            try (Stream<Path> stream = Files.walk(resolvedClassesDir)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String entryName = resolvedClassesDir.relativize(file).toString().replace('\\', '/');
                    jar.putNextEntry(new JarEntry(entryName));
                    jar.write(Files.readAllBytes(file));
                    jar.closeEntry();
                }
            }
        }
        return archive;
    }

    static void applyTestResourcesProperties(java.util.Map<String, String> environment) {
        for (TestResourcesProperty property : TEST_RESOURCES_PROPERTIES) {
            if (System.getProperty(property.systemProperty()) != null) {
                continue;
            }
            String value = environment.get(property.environmentVariable());
            if (value == null || value.isBlank()) {
                continue;
            }
            System.setProperty(property.systemProperty(), value);
        }
    }

    @FunctionalInterface
    interface ClassResolver {
        Class<?> load(String className, ClassLoader classLoader) throws Exception;
    }

    @FunctionalInterface
    interface ContextBootstrapper {
        void bootstrap(ClassLoader classLoader) throws Exception;
    }

    @FunctionalInterface
    interface ApplicationStarter {
        boolean start(Class<?> loadedClass, Path resolvedClassesDir, List<String> appArgs) throws Exception;
    }

    record ResolvedProjectLayout(Path processedClassesRoot, URLClassLoader applicationClassLoader) {
    }

    private record TestResourcesProperty(String environmentVariable, String systemProperty) {
    }

    private static void blockUntilInterrupted() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> discoverApplicationPackages(Path classesDirectory) {
        try (Stream<Path> stream = Files.list(classesDirectory)) {
            return stream
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.isBlank())
                .filter(name -> !"META-INF".equals(name))
                .filter(name -> !"pyronaut_application".equals(name))
                .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static List<Class<?>> discoverApplicationClasses(Path classesDirectory, ClassLoader classLoader) {
        if (classLoader == null) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(classesDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .map(classesDirectory::relativize)
                .map(path -> path.toString().replace('\\', '/'))
                .filter(name -> name.endsWith(".class"))
                .filter(name -> !name.startsWith("META-INF/"))
                .filter(name -> !name.startsWith("pyronaut_application/"))
                .filter(name -> !name.endsWith("package-info.class"))
                .filter(name -> !name.contains("$Definition"))
                .filter(name -> !name.contains("$Introspection"))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .map(className -> loadApplicationClass(className, classLoader))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover application classes from " + classesDirectory + ": " + e.getMessage(), e);
        }
    }

    private static Class<?> loadApplicationClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load application class " + className, e);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautRunMain()).execute(args);
        System.exit(exitCode);
    }
}
