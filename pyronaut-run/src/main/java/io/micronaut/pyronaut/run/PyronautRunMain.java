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
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderApplicationContextConfigurers;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanDefinitionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.config.model.PyronautRuntimeProperties;
import io.micronaut.pyronaut.config.terminal.PhaseReporter;
import io.micronaut.pyronaut.config.terminal.Terminal;
import io.micronaut.pyronaut.config.testresources.TestResourcesLogMirror;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.runtime.Micronaut;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Entry point for {@code pyronaut-run}.
 */
@SuppressWarnings({"checkstyle:InnerTypeLast", "checkstyle:DesignForExtension"})
@CommandLine.Command(name = "pyronaut-run", mixinStandardHelpOptions = true, description = "Run a processed Pyronaut application")
public class PyronautRunMain implements Callable<Integer> {
    protected static final String RUNTIME_DEPENDENCIES_MANIFEST = "resolved-runtime-dependencies";
    protected static final String MICRONAUT_ENVIRONMENTS = "micronaut.environments";
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String MICRONAUT_PYTHON_ENABLED = "micronaut.python.enabled";
    private static final String PYTHON_ENABLED_MARKER = "META-INF/pyronaut/python-enabled";
    private static final String PACKAGED_JAR_PROPERTY = "pyronaut.packaged.jar";
    private static final String APPLICATION_CLASSES_INDEX = "META-INF/pyronaut/application-classes.idx";
    private static final String LOGGER_CONFIG_PROPERTY = "logger.config";
    /**
     * Set by the CLI on the JVM toolchain to run the application in the reloading development runtime.
     */
    private static final String DEV_RELOAD_PROPERTY = "pyronaut.dev.reload";
    private static final String DEV_RUNTIME_MAIN = "io.micronaut.dev.MicronautDevMain";
    private static final String DEV_RELOAD_CLASS = "io.micronaut.pyronaut.dev.PyronautDevReload";
    private static final String CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT = "micronaut.jsonschema.configuration.validator.fail-on-not-present";
    private static final String CONFIGURATION_VALIDATOR_SUPPRESSIONS = "micronaut.jsonschema.configuration.validator.suppressions";
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
            description = "Enable verbose output, optionally scoped to comma-separated logger names"
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
    private final PyronautRunConfigurer runConfigurer;

    public PyronautRunMain() {
        this(
            new PyprojectModelReader(),
            classLoader -> { },
            PyronautRunMain::startMicronautApplication,
            loadRunConfigurer()
        );
    }

    PyronautRunMain(PyprojectModelReader modelReader,
                    ContextBootstrapper contextBootstrapper,
                    ApplicationStarter applicationStarter) {
        this(
            modelReader,
            contextBootstrapper,
            applicationStarter,
            loadRunConfigurer()
        );
    }

    PyronautRunMain(PyprojectModelReader modelReader,
                    ContextBootstrapper contextBootstrapper,
                    ApplicationStarter applicationStarter,
                    LoggingInitializer loggingInitializer) {
        this(modelReader, contextBootstrapper, applicationStarter, PyronautRunConfigurer.NO_OP, loggingInitializer);
    }

    private PyronautRunMain(PyprojectModelReader modelReader,
                            ContextBootstrapper contextBootstrapper,
                            ApplicationStarter applicationStarter,
                            PyronautRunConfigurer runConfigurer) {
        this(modelReader, contextBootstrapper, applicationStarter, runConfigurer,
            () -> runConfigurer.initializeApplicationDefaults(null));
    }

    private PyronautRunMain(PyprojectModelReader modelReader,
                            ContextBootstrapper contextBootstrapper,
                            ApplicationStarter applicationStarter,
                            PyronautRunConfigurer runConfigurer,
                            LoggingInitializer loggingInitializer) {
        this.modelReader = modelReader;
        this.contextBootstrapper = contextBootstrapper;
        this.applicationStarter = applicationStarter;
        this.runConfigurer = runConfigurer;
        this.loggingInitializer = loggingInitializer;
    }

    @Override
    public Integer call() {
        initializeJavaHomeIfMissing(() -> System.getenv("JAVA_HOME"));
        Path root = projectDir.toAbsolutePath().normalize();
        ResolvedProjectLayout layout;
        PyprojectModel model;
        try {
            configureEnv(System.getenv());
            if (isPackagedJar()) {
                // A runnable JAR is self-contained. Do not let files in the
                // current working directory replace or augment its indexed
                // application, even when it is launched from a Maven,
                // Gradle, or another Pyronaut project.
                model = null;
                layout = new ResolvedProjectLayout(root.resolve(classesDir).normalize(), List.of());
            } else if (ExternalProjectLayout.isExternal(root)) {
                ExternalProjectLayout external = ExternalProjectLayout.read(root);
                layout = resolveExternalProjectLayout(root, external);
                Path projectToml = root.resolve("project.toml");
                model = Files.isRegularFile(projectToml) ? modelReader.readProjectToml(projectToml) : null;
            } else {
                Path projectFile = root.resolve(PyprojectModelReader.FILE_NAME);
                if (Files.isRegularFile(projectFile)) {
                    model = modelReader.readFile(projectFile);
                    Path resolvedConfigDir = resolveConfiguredPath(root, configDir, DEFAULT_CONFIG_DIR, model.pyronaut().sources().resources(), "--config-dir");
                    layout = resolveProjectLayout(root, classesDir, resolvedConfigDir, resolveConfiguredPaths(root, model.pyronaut().sources().additionalResources()));
                } else {
                    // Packaged wheels and Docker images contain compiled output
                    // and runtime metadata, not the source project's TOML.
                    model = null;
                    layout = resolveProjectLayout(root, classesDir, root.resolve(configDir).normalize(), List.of());
                }
            }
            resolveDefaultConfiguration(root, configDir);
            applyConfigurationValidationDefaults();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Run failed: " + e.getMessage());
            return 6;
        }

        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        String previousPythonEnabledProperty = System.getProperty(MICRONAUT_PYTHON_ENABLED);
        String previousLoggerConfigProperty = System.getProperty(LOGGER_CONFIG_PROPERTY);
        BeanIntrospectionsProvider previousBeanIntrospectionsProvider = null;
        try (layout) {
            ClassLoader applicationClassLoader = layout.applicationClassLoader();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            if (!isPackagedJar() && ExternalProjectLayout.isExternal(root)) {
                applyExternalPythonDefault(applicationClassLoader, System.getenv());
            }
            boolean defaultLoggingConfigurationApplied = runConfigurer.shouldInitializeApplicationDefaults(applicationClassLoader);
            if (defaultLoggingConfigurationApplied) {
                loggingInitializer.initializeApplicationDefaults();
            }
            if (verboseLogger != null) {
                runConfigurer.initializeApplicationDefaults(verboseLogger);
            }
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            contextBootstrapper.bootstrap(applicationClassLoader);
            if (verboseLogger != null) {
                PyronautLauncherLogging.reapplyVerboseLoggerDefaults(verboseLogger);
            }
            ApplicationArgs applicationArgs = new ApplicationArgs(
                    model == null ? Boolean.TRUE : model.pyronaut().run().bannerEnabled(), appArgs, verboseLogger != null
            );
            if (launch(root, layout, model, applicationArgs)) {
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
            restoreSystemProperty(MICRONAUT_PYTHON_ENABLED, previousPythonEnabledProperty);
            restoreSystemProperty(LOGGER_CONFIG_PROPERTY, previousLoggerConfigProperty);
        }
    }

    /**
     * Start the application. On a terminal a {@code Starting application} row
     * spins until the context is up, with the banner and startup logging
     * flowing above it; elsewhere nothing is added to the application's output.
     *
     * <p>Resolving a Test Resources property can block on an image pull that
     * only the server's own log file records, so that log is mirrored for as
     * long as startup lasts. The mirror belongs here rather than in the CLI
     * that started the server: this process owns the terminal, and a line
     * written from outside the live region is repainted away.
     */
    private boolean startApplication(ResolvedProjectLayout layout, ApplicationArgs applicationArgs) throws Exception {
        if (!Terminal.isInteractive() || verboseLogger != null) {
            try (TestResourcesLogMirror mirror = TestResourcesLogMirror.start(PyronautRunMain::printTestResourcesLine)) {
                return applicationStarter.start(layout.processedClassesRoot(), applicationArgs);
            }
        }
        try (PhaseReporter progress = PhaseReporter.create()) {
            PhaseReporter.Phase starting = progress.start("Starting application");
            boolean started;
            // The mirror is drained as it closes, which happens before either
            // line below, so a container's last word lands above the line that
            // ends the phase rather than after it.
            try (TestResourcesLogMirror mirror = TestResourcesLogMirror.start(entry -> report(progress, entry))) {
                started = applicationStarter.start(layout.processedClassesRoot(), applicationArgs);
            } catch (Exception e) {
                starting.fail("Application failed to start");
                throw e;
            }
            starting.done("Application started");
            return started;
        }
    }

    private static void report(PhaseReporter progress, TestResourcesLogMirror.Entry entry) {
        if (entry.error()) {
            progress.error(entry.message());
        } else {
            progress.note(entry.message());
        }
    }

    private static void printTestResourcesLine(TestResourcesLogMirror.Entry entry) {
        System.err.println(entry.message());
    }

    /**
     * Starts the application in the project's class loader, the thread context class loader when this
     * is called. Development mode replaces it with the reloading development runtime.
     *
     * @param root The project directory
     * @param layout The project layout
     * @param model The project model, null for a packaged or external application without one
     * @param applicationArgs The application's arguments
     * @return Whether the application started and the launcher must block until interrupted
     * @throws Exception if the application fails to start
     */
    protected boolean launch(Path root, ResolvedProjectLayout layout, PyprojectModel model, ApplicationArgs applicationArgs) throws Exception {
        DevelopmentRuntime development = model == null || ExternalProjectLayout.isExternal(root) ? null : createDevelopmentRuntime();
        if (development == null) {
            return startApplication(layout, applicationArgs);
        }
        // every generation starts as the run command starts the application, with its progress and the Test
        // Resources log, in the generation's loader, the thread context loader then
        development.start(root, layout, model, applicationArgs.appArgs(), () -> startApplication(layout, applicationArgs));
        return true;
    }

    /**
     * The reloading development runtime, when the CLI asked for it. The native {@code pyronaut-dev} image, which
     * holds the runtime, overrides this to create it directly; the JVM launcher loads it by name.
     *
     * @return The runtime, null when not asked for
     */
    protected DevelopmentRuntime createDevelopmentRuntime() {
        return developmentRuntime();
    }

    /**
     * Whether the CLI asked for the reloading development runtime, {@code -Dpyronaut.dev.reload=true}.
     *
     * @return True when dev mode reloads in process
     */
    protected static boolean isDevelopmentRuntimeRequested() {
        return Boolean.getBoolean(DEV_RELOAD_PROPERTY);
    }

    /**
     * The reloading development runtime, when the CLI asked for it on the JVM toolchain. It lives in
     * {@code pyronaut-dev}, whose jar the CLI adds to the classpath then, and is loaded by name, so that
     * neither this production launcher nor its native image depends on it or on {@code micronaut-dev}.
     *
     * @return The runtime, null when not asked for
     */
    static DevelopmentRuntime developmentRuntime() {
        if (!Boolean.getBoolean(DEV_RELOAD_PROPERTY) || System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return null;
        }
        ClassLoader loader = PyronautRunMain.class.getClassLoader();
        if (!io.micronaut.core.reflect.ClassUtils.isPresent(DEV_RUNTIME_MAIN, loader) || !io.micronaut.core.reflect.ClassUtils.isPresent(DEV_RELOAD_CLASS, loader)) {
            throw new IllegalStateException("Reloading in dev mode needs micronaut-dev (Micronaut 5.3 or later) on the development runtime classpath and pyronaut-dev beside it. "
                + "Set tool.pyronaut.dev.reload = \"process\" to restart the process for every change instead.");
        }
        try {
            return (DevelopmentRuntime) Class.forName(DEV_RELOAD_CLASS, true, loader).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create the development runtime: " + e.getMessage(), e);
        }
    }

    protected void configureEnv(Map<String, String> env) {

    }

    protected void resolveDefaultConfiguration(Path root, Path configDir) {
    }

    static void enableContextClassLoaderIntrospections() {
        if (System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER) == null) {
            System.setProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, "true");
        }
    }

    protected static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }

    static boolean applyExternalPythonDefault(ClassLoader classLoader, java.util.Map<String, String> environment) {
        if (classLoader.getResource(PYTHON_ENABLED_MARKER) != null
            || System.getProperty(MICRONAUT_PYTHON_ENABLED) != null
            || environment.get("MICRONAUT_PYTHON_ENABLED") != null) {
            return false;
        }
        System.setProperty(MICRONAUT_PYTHON_ENABLED, Boolean.FALSE.toString());
        return true;
    }

    private static void applyConfigurationValidationDefaults() {
        setDefaultProperty(CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT, "false");
        setDefaultProperty(CONFIGURATION_VALIDATOR_SUPPRESSIONS, "logger.levels.*");
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static boolean startMicronautApplication(Path resolvedClassesDir,
                                                     ApplicationArgs applicationArgs) {
        Micronaut micronaut = Micronaut.build(applicationArgs.appArgs.toArray(String[]::new));
        micronaut.banner(!Boolean.FALSE.equals(applicationArgs.bannerEnabled));
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

    protected ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path configDir) throws IOException {
        return resolveProjectLayout(root, classesDir, configDir, List.of());
    }

    protected ResolvedProjectLayout resolveExternalProjectLayout(Path root, ExternalProjectLayout external) throws IOException {
        Path resolvedClassesDir = ExternalProjectLayout.outputDirectory(root).resolve("classes").normalize();
        if (!Files.isDirectory(resolvedClassesDir)) {
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }
        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        // `run` is a production-style launch. Development-only dependencies
        // must never be selected merely because the external layout contains
        // a development classpath.
        for (Path entry : external.runtimeClasspath()) {
            if (Files.exists(entry)) {
                urls.add(entry.toUri().toURL());
            }
        }
        urls.add(resolvedClassesDir.toUri().toURL());
        for (Path resource : external.mainResources()) {
            if (Files.isDirectory(resource)) {
                urls.add(resource.toUri().toURL());
            }
        }
        return new ResolvedProjectLayout(resolvedClassesDir, List.copyOf(urls));
    }

    protected ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path configDir, List<Path> additionalResourceDirs) throws IOException {
        Path pyronautDir = root.resolve(DEFAULT_PYRONAUT_DIR).normalize();
        Path resolvedClassesDir = root.resolve(classesDir).normalize();
        if (!Files.isDirectory(resolvedClassesDir)) {
            if (isPackagedJar()) {
                return new ResolvedProjectLayout(resolvedClassesDir, List.of());
            }
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }

        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        addManifestEntries(urls, root, resolveRunManifest(pyronautDir));
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

    protected void addManifestEntries(LinkedHashSet<URL> urls, Path root, Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return;
        }
        for (String line : Files.readAllLines(manifest)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (includeClasspathEntry(trimmed)) {
                continue;
            }
            Path entry = Path.of(trimmed);
            if (!entry.isAbsolute()) {
                entry = root.resolve(entry);
            }
            urls.add(entry.toAbsolutePath().normalize().toUri().toURL());
        }
    }

    protected boolean includeClasspathEntry(String name) {
        return false;
    }

    protected Path resolveRunManifest(Path pyronautDir) {
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

    @FunctionalInterface
    /**
     * Runs the application in the reloading development runtime.
     */
    public interface DevelopmentRuntime {
        /**
         * Starts the application, returning once its first generation started.
         *
         * @param root The project directory
         * @param layout The layout the run command resolved
         * @param model The project model
         * @param appArgs The application's arguments
         * @param launcher Starts the application in a generation, its loader the thread context loader
         * @throws Exception if the application does not start
         */
        void start(Path root, ResolvedProjectLayout layout, PyprojectModel model, List<String> appArgs, Callable<Boolean> launcher) throws Exception;
    }

    interface ContextBootstrapper {
        void bootstrap(ClassLoader classLoader) throws Exception;
    }

    @FunctionalInterface
    interface ApplicationStarter {
        boolean start(Path resolvedClassesDir, ApplicationArgs args) throws Exception;
    }

    /**
     * The arguments an application is started with.
     *
     * @param bannerEnabled Whether the banner is shown
     * @param appArgs The command line arguments
     * @param verbose Whether verbose logging was requested
     */
    public record ApplicationArgs(Boolean bannerEnabled, List<String> appArgs, boolean verbose) {
    }

    @FunctionalInterface
    interface LoggingInitializer {
        void initializeApplicationDefaults();
    }

    /**
     * Resolved classpath and temporary resources for an external project.
     *
     * @param processedClassesRoot processed application classes root
     * @param classpathUrls ordered application classpath URLs
     */
    public record ResolvedProjectLayout(Path processedClassesRoot, List<URL> classpathUrls) implements AutoCloseable {
        public ResolvedProjectLayout {
            classpathUrls = List.copyOf(classpathUrls);
        }

        ClassLoader applicationClassLoader() {
            return new URLClassLoader(classpathUrls.toArray(URL[]::new), resolveApplicationClassLoader());
        }

        @Override
        public void close() throws IOException {
        }

    }

    private static void blockUntilInterrupted() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> discoverApplicationPackages(Path classesDirectory) {
        if (isPackagedJar()) {
            return packagedApplicationClassNames(resolveApplicationClassLoader()).stream()
                .map(PyronautRunMain::topLevelPackage)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        }
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

    static List<Class<?>> discoverApplicationClasses(Path classesDirectory, ClassLoader classLoader) {
        if (classLoader == null) {
            return List.of();
        }
        if (isPackagedJar()) {
            return packagedApplicationClassNames(classLoader).stream()
                .map(className -> loadApplicationClass(className, classLoader))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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

    static List<String> packagedApplicationClassNames(ClassLoader classLoader) {
        if (classLoader == null) {
            return List.of();
        }
        try (InputStream input = classLoader.getResourceAsStream(APPLICATION_CLASSES_INDEX)) {
            if (input == null) {
                throw new IllegalStateException("Missing packaged application class index: " + APPLICATION_CLASSES_INDEX);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines()
                    .map(String::trim)
                    .filter(name -> !name.isBlank())
                    .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read packaged application class index: " + e.getMessage(), e);
        }
    }

    private static String topLevelPackage(String className) {
        int separator = className.indexOf('.');
        return separator < 1 ? "" : className.substring(0, separator);
    }

    private static boolean isPackagedJar() {
        return Boolean.getBoolean(PACKAGED_JAR_PROPERTY);
    }

    private static Class<?> loadApplicationClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load application class " + className, e);
        }
    }

    public static void main(String[] args) {
        Terminal.notifyLaunched();
        PyronautRuntimeProperties.disableGraalVmImageSingletons();
        loadRunConfigurer().initializeLauncher();
        int exitCode = new CommandLine(new PyronautRunMain()).execute(args);
        System.exit(exitCode);
    }

    private static PyronautRunConfigurer loadRunConfigurer() {
        return ServiceLoader.load(PyronautRunConfigurer.class, PyronautRunMain.class.getClassLoader())
            .findFirst()
            .orElse(PyronautRunConfigurer.NO_OP);
    }
}
