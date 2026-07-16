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

import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.context.env.Environment;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderApplicationContextConfigurers;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanDefinitionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
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
import java.util.stream.Stream;

/**
 * Entry point for {@code pyronaut-run}.
 */
@SuppressWarnings("checkstyle:InnerTypeLast")
@CommandLine.Command(name = "pyronaut-run", mixinStandardHelpOptions = true, description = "Run a processed Pyronaut application")
public final class PyronautRunMain implements Callable<Integer> {
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String RUNTIME_DEPENDENCIES_MANIFEST = "resolved-runtime-dependencies";
    private static final String DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST = "resolved-development-runtime-dependencies";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String NATIVE_IMAGE_CODE = "org.graalvm.nativeimage.imagecode";
    private static final String PYTHON_APPLICATION_MAIN = "META-INF/GRAALPY-VFS/micronaut-application/src/main.py";
    private static final String LOGGER_CONFIG_PROPERTY = "logger.config";
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

    @CommandLine.Option(
        names = "--debug-vm",
        description = "Enable JVM JDWP debugging on port 5005 (flag is accepted for orchestrator forwarding)"
    )
    boolean debugVm;

    @CommandLine.Option(
            names = "--verbose",
            arity = "0..1",
            fallbackValue = "",
            description = "Enable verbose output, optionally scoped to a logger name"
    )
    String verboseLogger;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    @CommandLine.Parameters
    List<String> appArgs = List.of();

    private final ContextBootstrapper contextBootstrapper;
    private final ApplicationStarter applicationStarter;
    private final PyprojectModelReader modelReader;
    private final LoggingInitializer loggingInitializer;

    public PyronautRunMain() {
        this(
            new PyprojectModelReader(),
            classLoader -> { },
            PyronautRunMain::startMicronautApplication,
                () -> PyronautLauncherLogging.initializeApplicationDefaults((String) null)
        );
    }

    PyronautRunMain(PyprojectModelReader modelReader,
                    ContextBootstrapper contextBootstrapper,
                    ApplicationStarter applicationStarter) {
        this(
            modelReader,
            contextBootstrapper,
            applicationStarter,
                () -> PyronautLauncherLogging.initializeApplicationDefaults((String) null)
        );
    }

    PyronautRunMain(PyprojectModelReader modelReader,
                    ContextBootstrapper contextBootstrapper,
                    ApplicationStarter applicationStarter,
                    LoggingInitializer loggingInitializer) {
        this.modelReader = modelReader;
        this.contextBootstrapper = contextBootstrapper;
        this.applicationStarter = applicationStarter;
        this.loggingInitializer = loggingInitializer;
    }

    @Override
    public Integer call() {
        initializeJavaHomeIfMissing(() -> System.getenv("JAVA_HOME"));
        Path root = projectDir.toAbsolutePath().normalize();
        ResolvedProjectLayout layout;
        PyprojectModel model;
        try {
            applyTestResourcesProperties(System.getenv());
            if (ExternalProjectLayout.isExternal(root)) {
                ExternalProjectLayout external = ExternalProjectLayout.read(root);
                layout = resolveExternalProjectLayout(root, classesDir, external);
                model = null;
            } else {
                model = modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME));
                Path resolvedConfigDir = resolveConfiguredPath(root, configDir, DEFAULT_CONFIG_DIR, model.pyronaut().sources().resources(), "--config-dir");
                layout = resolveProjectLayout(root, classesDir, resolvedConfigDir, resolveConfiguredPaths(root, model.pyronaut().sources().additionalResources()));
            }
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        }

        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        String previousLoggerConfigProperty = System.getProperty(LOGGER_CONFIG_PROPERTY);
        BeanIntrospectionsProvider previousBeanIntrospectionsProvider = null;
        try (layout) {
            ClassLoader applicationClassLoader = layout.applicationClassLoader();
            boolean hasPythonApplicationMain = hasPythonApplicationMain(applicationClassLoader);
            boolean defaultLoggingConfigurationApplied = PyronautLauncherLogging.setDefaultApplicationConfigurationProperty();
            if (!hasPythonApplicationMain && defaultLoggingConfigurationApplied) {
                loggingInitializer.initializeApplicationDefaults();
            }
            if (verboseLogger != null) {
                PyronautLauncherLogging.initializeApplicationDefaults(verboseLogger);
            }
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            contextBootstrapper.bootstrap(applicationClassLoader);
            ApplicationArgs applicationArgs = new ApplicationArgs(
                    model == null ? Boolean.TRUE : model.pyronaut().run().bannerEnabled(), appArgs, verboseLogger != null
            );
            if (applicationStarter.start(layout.processedClassesRoot(), applicationArgs)) {
                blockUntilInterrupted();
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            if (previousBeanIntrospectionsProvider != null) {
                BeanIntrospectionProviders.set(previousBeanIntrospectionsProvider);
            }
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
            restoreSystemProperty(LOGGER_CONFIG_PROPERTY, previousLoggerConfigProperty);
        }
    }

    private static boolean hasPythonApplicationMain(ClassLoader classLoader) {
        return classLoader.getResource(PYTHON_APPLICATION_MAIN) != null;
    }

    static void enableContextClassLoaderIntrospections() {
        if (System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER) == null) {
            System.setProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, "true");
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static boolean startMicronautApplication(Path resolvedClassesDir,
                                                     ApplicationArgs applicationArgs) throws Exception {
        Micronaut micronaut = Micronaut.build(applicationArgs.appArgs.toArray(String[]::new));
        micronaut.banner(!Boolean.FALSE.equals(applicationArgs.bannerEnabled));
        micronaut.environments(Environment.DEVELOPMENT);
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            micronaut.classLoader(contextClassLoader);
            micronaut.beanDefinitionsProvider(new ContextClassLoaderBeanDefinitionsProvider());
            ContextClassLoaderApplicationContextConfigurers.configure(micronaut, contextClassLoader);
        }
        List<Class<?>> applicationClasses = discoverApplicationClasses(resolvedClassesDir, contextClassLoader);
        if (!applicationClasses.isEmpty()) {
            micronaut.classes(applicationClasses.toArray(Class[]::new));
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

    static ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path configDir) throws IOException {
        return resolveProjectLayout(root, classesDir, configDir, List.of());
    }

    static ResolvedProjectLayout resolveExternalProjectLayout(Path root, Path classesDir, ExternalProjectLayout external) throws IOException {
        Path resolvedClassesDir = root.resolve(classesDir).normalize();
        if (!Files.isDirectory(resolvedClassesDir)) {
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }
        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        for (Path entry : external.developmentRuntimeClasspath().isEmpty() ? external.runtimeClasspath() : external.developmentRuntimeClasspath()) {
            if (Files.exists(entry)) urls.add(entry.toUri().toURL());
        }
        urls.add(resolvedClassesDir.toUri().toURL());
        for (Path resource : external.mainResources()) if (Files.isDirectory(resource)) urls.add(resource.toUri().toURL());
        return new ResolvedProjectLayout(resolvedClassesDir, List.copyOf(urls));
    }


    static ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path configDir, List<Path> additionalResourceDirs) throws IOException {
        Path pyronautDir = root.resolve(DEFAULT_PYRONAUT_DIR).normalize();
        Path resolvedClassesDir = root.resolve(classesDir).normalize();
        if (!Files.isDirectory(resolvedClassesDir)) {
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }

        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        addManifestEntries(urls, resolveRunManifest(pyronautDir));
        addPathIfDirectory(urls, resolvedClassesDir);
        addPathIfDirectory(urls, root.resolve(configDir).normalize());
        addResourceDirectories(urls, additionalResourceDirs);
        return new ResolvedProjectLayout(resolvedClassesDir, List.copyOf(urls));
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
        boolean start(Path resolvedClassesDir, ApplicationArgs args) throws Exception;
    }

    record ApplicationArgs(Boolean bannerEnabled, List<String> appArgs, boolean verbose) {}

    @FunctionalInterface
    interface LoggingInitializer {
        void initializeApplicationDefaults();
    }

    record ResolvedProjectLayout(Path processedClassesRoot, List<URL> classpathUrls) implements AutoCloseable {
        ResolvedProjectLayout {
            classpathUrls = List.copyOf(classpathUrls);
        }

        ClassLoader applicationClassLoader() {
            if (usesNativeSystemClassLoader()) {
                return ClassLoader.getSystemClassLoader();
            }
            return new URLClassLoader(classpathUrls.toArray(URL[]::new), resolveApplicationClassLoader());
        }

        @Override
        public void close() throws IOException {
        }

        private static boolean usesNativeSystemClassLoader() {
            return System.getProperty(NATIVE_IMAGE_CODE) != null
                && System.getProperty("java.class.path") != null
                && !System.getProperty("java.class.path").isBlank();
        }
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

    static void main(String[] args) {
        PyronautLauncherLogging.initialize();
        int exitCode = new CommandLine(new PyronautRunMain()).execute(args);
        System.exit(exitCode);
    }
}
