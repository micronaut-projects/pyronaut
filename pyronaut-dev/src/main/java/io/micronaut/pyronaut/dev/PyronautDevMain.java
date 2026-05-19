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

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.pyronaut.install.PyronautInstallMain;
import io.micronaut.pyronaut.processor.PyronautProcessorMain;
import io.micronaut.pyronaut.run.ContextClassLoaderBeanDefinitionsProvider;
import io.micronaut.pyronaut.run.PyronautRunMain;
import io.micronaut.pyronaut.test.PyronautTestMain;
import io.micronaut.pyronaut.testresources.PyronautTestResourcesServerMain;
import io.micronaut.pyronaut.validateconfig.PyronautValidateConfigMain;
import io.micronaut.python.compiler.PyronautCompiler;
import io.micronaut.runtime.Micronaut;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Single native-capable development launcher for the Pyronaut SDK toolchain.
 */
@CommandLine.Command(
    name = "pyronaut-dev",
    mixinStandardHelpOptions = true,
    description = "Run Pyronaut development commands and direct Python sources"
)
public final class PyronautDevMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int USAGE_ERROR = 2;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_MAIN_CLASS = "pyronaut_application.PyronautMain";
    private static final String BUILD_DEPENDENCIES_MANIFEST = "resolved-build-dependencies";
    private static final String RUNTIME_DEPENDENCIES_MANIFEST = "resolved-runtime-dependencies";
    private static final String DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST = "resolved-development-runtime-dependencies";
    private static final String TEST_DEPENDENCIES_MANIFEST = "resolved-test-dependencies";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String DEFAULT_TESTS_DIR = "tests";
    private static final String TEST_APPLICATION_MAIN = "tests.py";
    private static final String DEFAULT_REPORTS_DIR = "__pyronaut__/reports/tests";
    private static final String DEFAULT_JUNIT_XML_REPORT = "junit.xml";
    private static final String DEFAULT_HTML_REPORT = "index.html";
    private static final String DEFAULT_NODEID_REPORT = ".pyronaut-last-nodeid.txt";
    private static final String DEFAULT_EVENTS_REPORT = "events.ndjson";
    private static final String MICRONAUT_SERVER_PORT = "micronaut.server.port";
    private static final String DEFAULT_TEST_SERVER_PORT = "0";
    private static final String PYTEST_SOURCE_DIR = "pytest.src.dir";
    private static final String PYTEST_REPORT_DIR = "pytest.report.dir";
    private static final String PYTEST_JUNIT_XML_REPORT = "pytest.report.junit";
    private static final String PYTEST_HTML_REPORT = "pytest.report.html";
    private static final String PYTEST_LAST_NODEID_REPORT = "pytest.report.nodeid";
    private static final String PYTEST_EVENTS_REPORT = "pytest.report.events";
    private static final String NETTY_NO_UNSAFE = "io.netty.noUnsafe";
    private static final String SUN_MISC_UNSAFE_MEMORY_ACCESS = "sun.misc.unsafe.memory.access";
    private static final Set<String> TOOL_COMMANDS = Set.of(
        "install",
        "process",
        "run",
        "test",
        "validate-config",
        "test-resources-server"
    );

    private final DelegateInvoker delegateInvoker;
    private final DirectSourceRunner directSourceRunner;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    @CommandLine.Unmatched
    List<String> directArgs = new ArrayList<>();

    public PyronautDevMain() {
        this(new DefaultDelegateInvoker(), new DefaultDirectSourceRunner());
    }

    PyronautDevMain(DelegateInvoker delegateInvoker) {
        this(delegateInvoker, new DefaultDirectSourceRunner());
    }

    PyronautDevMain(DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner) {
        this.delegateInvoker = delegateInvoker;
        this.directSourceRunner = directSourceRunner;
    }

    public static void main(String[] args) {
        configureNativeRuntimeDefaults();
        initializeLauncherLogging();
        int exit = execute(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static void configureNativeRuntimeDefaults() {
        setDefaultProperty(NETTY_NO_UNSAFE, "false");
        setDefaultProperty(SUN_MISC_UNSAFE_MEMORY_ACCESS, "allow");
    }

    static void initializeLauncherLogging() {
        PyronautDevLogging.initializeLauncherLogging();
    }


    private static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }

    public static int execute(String[] args) {
        return execute(args, new DefaultDelegateInvoker(), new DefaultDirectSourceRunner());
    }

    static int execute(String[] args, DelegateInvoker delegateInvoker) {
        return execute(args, delegateInvoker, new DefaultDirectSourceRunner());
    }

    static int execute(String[] args, DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner) {
        if (args.length > 0 && TOOL_COMMANDS.contains(args[0])) {
            ToolCommand command = ToolCommand.fromCliValue(args[0]);
            String[] forwarded = Arrays.copyOfRange(args, 1, args.length);
            return delegateInvoker.invoke(command, forwarded);
        }
        return new CommandLine(new PyronautDevMain(delegateInvoker, directSourceRunner)).execute(args);
    }

    @Override
    public Integer call() {
        DirectSourceInvocation invocation;
        try {
            invocation = parseDirectSourceArgs(directArgs);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return USAGE_ERROR;
        }
        if (invocation.sources().isEmpty()) {
            commandSpec.commandLine().usage(System.err);
            return USAGE_ERROR;
        }
        return runDirectSources(invocation);
    }

    static DirectSourceInvocation parseDirectSourceArgs(List<String> args) {
        boolean test = false;
        String port = null;
        Path setup = null;
        List<Path> configs = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> testSources = new ArrayList<>();
        Map<String, String> properties = new LinkedHashMap<>();
        boolean afterSeparator = false;

        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if ("--".equals(token)) {
                afterSeparator = true;
                continue;
            }
            if (!afterSeparator) {
                switch (token) {
                    case "--test" -> {
                        test = true;
                        continue;
                    }
                    case "--port" -> {
                        port = requireValue(args, ++i, "--port");
                        continue;
                    }
                    case "--property" -> {
                        putProperty(properties, requireValue(args, ++i, "--property"));
                        continue;
                    }
                    case "-D" -> {
                        putProperty(properties, requireValue(args, ++i, "-D"));
                        continue;
                    }
                    case "--config" -> {
                        configs.add(Path.of(requireValue(args, ++i, "--config")));
                        continue;
                    }
                    case "--setup" -> {
                        setup = Path.of(requireValue(args, ++i, "--setup"));
                        continue;
                    }
                    default -> {
                        if (token.startsWith("-D") && token.length() > 2) {
                            putProperty(properties, token.substring(2));
                            continue;
                        }
                    }
                }
            }
            (afterSeparator ? testSources : sources).add(Path.of(token));
        }
        if (port != null) {
            properties.put("micronaut.server.port", port);
        }
        return new DirectSourceInvocation(test, setup, List.copyOf(configs), List.copyOf(sources), List.copyOf(testSources), Map.copyOf(properties));
    }

    private Integer runDirectSources(DirectSourceInvocation invocation) {
        Path stagingRoot = null;
        Map<String, String> previousProperties = new LinkedHashMap<>();
        try {
            stagingRoot = Files.createTempDirectory("pyronaut-dev-source-");
            stageSetup(invocation, stagingRoot);
            stageSources(invocation.sources(), stagingRoot.resolve("src"));
            if (!invocation.testSources().isEmpty()) {
                stageSources(invocation.testSources(), stagingRoot.resolve("tests"));
            }
            stageConfig(invocation.configs(), stagingRoot.resolve("config"));
            applyProperties(invocation.properties(), previousProperties);

            int install = delegateInvoker.invoke(ToolCommand.INSTALL, "--project-dir", stagingRoot.toString());
            if (install != SUCCESS) {
                return install;
            }
            return directSourceRunner.run(invocation, stagingRoot);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (Exception e) {
            System.err.println("Direct source launch failed: " + e.getMessage());
            return INTERNAL_ERROR;
        } finally {
            restoreProperties(previousProperties, invocation.properties().keySet());
            if (stagingRoot != null) {
                deleteDirectoryBestEffort(stagingRoot);
            }
        }
    }

    private static int runInMemoryApplication(DirectSourceInvocation invocation, Path stagingRoot) throws Exception {
        if (invocation.test()) {
            return runInMemoryTests(invocation, stagingRoot);
        }
        Path pyronautDir = stagingRoot.resolve(DEFAULT_PYRONAUT_DIR);
        List<Path> buildDependencies = readManifest(pyronautDir.resolve(BUILD_DEPENDENCIES_MANIFEST));
        List<Path> runtimeDependencies = readManifest(resolveRunManifest(pyronautDir));
        List<URL> runtimeUrls = toUrls(runtimeDependencies);
        Path configDir = stagingRoot.resolve("config");
        if (Files.isDirectory(configDir)) {
            runtimeUrls.add(configDir.toUri().toURL());
        }
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        try (URLClassLoader runtimeClassLoader = new URLClassLoader(runtimeUrls.toArray(URL[]::new), PyronautDevMain.class.getClassLoader())) {
            PyronautCompiler.Builder builder = PyronautCompiler.builder()
                .annotationProcessorPath(toFiles(buildDependencies))
                .classpath(toFiles(runtimeDependencies))
                .parentClassLoader(runtimeClassLoader);
            configureDirectSource(builder, invocation, stagingRoot);
            ClassLoader applicationClassLoader = builder.build().buildClassLoader();
            enableContextClassLoaderIntrospections();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            Class<?> mainClass = applicationClassLoader.loadClass(DEFAULT_MAIN_CLASS);
            GraalPyContextFactory.bootstrapReusableContext(applicationClassLoader);
            ApplicationContextBuilder micronaut = Micronaut.build(new String[0])
                .classLoader(applicationClassLoader)
                .beanDefinitionsProvider(new ContextClassLoaderBeanDefinitionsProvider())
                .mainClass(mainClass);
            List<String> configLocations = toConfigLocations(invocation.configs());
            if (!configLocations.isEmpty()) {
                micronaut.overrideConfigLocations(configLocations.toArray(String[]::new));
            }
            micronaut.start();
            blockUntilInterrupted();
            return SUCCESS;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
        }
    }

    private static int runInMemoryTests(DirectSourceInvocation invocation, Path stagingRoot) throws Exception {
        Path pyronautDir = stagingRoot.resolve(DEFAULT_PYRONAUT_DIR);
        List<Path> buildDependencies = readManifest(pyronautDir.resolve(BUILD_DEPENDENCIES_MANIFEST));
        List<Path> runtimeDependencies = readManifest(resolveRunManifest(pyronautDir));
        List<Path> testDependencies = readManifest(pyronautDir.resolve(TEST_DEPENDENCIES_MANIFEST));
        List<Path> compilerClasspath = new ArrayList<>(runtimeDependencies);
        compilerClasspath.addAll(testDependencies);
        List<URL> runtimeUrls = toUrls(compilerClasspath);
        Path configDir = stagingRoot.resolve("config");
        if (Files.isDirectory(configDir)) {
            runtimeUrls.add(configDir.toUri().toURL());
        }
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        String previousServerPortProperty = System.getProperty(MICRONAUT_SERVER_PORT);
        try (URLClassLoader runtimeClassLoader = new URLClassLoader(runtimeUrls.toArray(URL[]::new), PyronautDevMain.class.getClassLoader())) {
            PyronautCompiler.Builder builder = PyronautCompiler.builder()
                .annotationProcessorPath(toFiles(buildDependencies))
                .classpath(toFiles(compilerClasspath))
                .parentClassLoader(runtimeClassLoader);
            configureDirectSource(builder, invocation, stagingRoot);
            ClassLoader applicationClassLoader = builder.build().buildClassLoader();
            enableContextClassLoaderIntrospections();
            defaultTestServerPort();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            Path testsDir = stagingRoot.resolve(DEFAULT_TESTS_DIR);
            GraalPyContextFactory.bootstrapReusableContext(applicationClassLoader, Map.of(), selectTestApplicationMain(testsDir));

            LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
            if (Files.isDirectory(testsDir)) {
                requestBuilder.selectors(DiscoverySelectors.selectDirectory(testsDir.toString()));
                requestBuilder.configurationParameter(PYTEST_SOURCE_DIR, testsDir.toString());
            }
            configureReports(requestBuilder, stagingRoot);
            LauncherDiscoveryRequest request = requestBuilder.build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);
            return listener.getSummary().getTotalFailureCount() == 0 ? SUCCESS : 7;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
            restoreSystemProperty(MICRONAUT_SERVER_PORT, previousServerPortProperty);
        }
    }

    private static String selectTestApplicationMain(Path testsDir) {
        if (Files.isRegularFile(testsDir.resolve(TEST_APPLICATION_MAIN))) {
            return TEST_APPLICATION_MAIN;
        }
        return GraalPyContextFactory.APPLICATION_MAIN;
    }

    private static void configureReports(LauncherDiscoveryRequestBuilder requestBuilder, Path stagingRoot) throws IOException {
        Path reportsDir = stagingRoot.resolve(DEFAULT_REPORTS_DIR).normalize();
        Files.createDirectories(reportsDir);
        requestBuilder.configurationParameter(PYTEST_REPORT_DIR, reportsDir.toString());
        requestBuilder.configurationParameter(PYTEST_JUNIT_XML_REPORT, reportsDir.resolve(DEFAULT_JUNIT_XML_REPORT).toString());
        requestBuilder.configurationParameter(PYTEST_HTML_REPORT, reportsDir.resolve(DEFAULT_HTML_REPORT).toString());
        requestBuilder.configurationParameter(PYTEST_LAST_NODEID_REPORT, reportsDir.resolve(DEFAULT_NODEID_REPORT).toString());
        requestBuilder.configurationParameter(PYTEST_EVENTS_REPORT, reportsDir.resolve(DEFAULT_EVENTS_REPORT).toString());
    }

    private static void configureDirectSource(PyronautCompiler.Builder builder, DirectSourceInvocation invocation, Path stagingRoot) throws IOException {
        if (invocation.sources().size() == 1) {
            Path source = invocation.sources().get(0).toAbsolutePath().normalize();
            if (Files.isRegularFile(source) && source.getFileName().toString().endsWith(".py")) {
                builder.pythonCode(Files.readString(source));
                return;
            }
            if (Files.isDirectory(source)) {
                builder.pythonSrc(source.toString());
                return;
            }
        }
        builder.pythonSrc(stagingRoot.resolve("src").toString());
    }

    private static List<String> toConfigLocations(List<Path> configs) {
        List<String> locations = new ArrayList<>(configs.size());
        for (Path config : configs) {
            locations.add(config.toAbsolutePath().normalize().toUri().toString());
        }
        return locations;
    }

    private static Path resolveRunManifest(Path pyronautDir) {
        Path developmentManifest = pyronautDir.resolve(DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST);
        if (Files.exists(developmentManifest)) {
            return developmentManifest;
        }
        return pyronautDir.resolve(RUNTIME_DEPENDENCIES_MANIFEST);
    }

    private static List<Path> readManifest(Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                entries.add(Path.of(trimmed).toAbsolutePath().normalize());
            }
        }
        return entries;
    }

    private static List<URL> toUrls(List<Path> paths) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path path : paths) {
            urls.add(path.toUri().toURL());
        }
        return urls;
    }

    private static List<File> toFiles(List<Path> paths) {
        return paths.stream().map(Path::toFile).toList();
    }

    private static void enableContextClassLoaderIntrospections() {
        if (System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER) == null) {
            System.setProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, "true");
        }
    }

    private static void defaultTestServerPort() {
        if (System.getProperty(MICRONAUT_SERVER_PORT) == null) {
            System.setProperty(MICRONAUT_SERVER_PORT, DEFAULT_TEST_SERVER_PORT);
        }
    }

    private static void blockUntilInterrupted() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static String requireValue(List<String> args, int index, String option) {
        if (index >= args.size()) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args.get(index);
    }

    private static void putProperty(Map<String, String> properties, String assignment) {
        int index = assignment.indexOf('=');
        if (index <= 0) {
            throw new IllegalArgumentException("Expected property assignment in the form name=value: " + assignment);
        }
        properties.put(assignment.substring(0, index), assignment.substring(index + 1));
    }

    private static void applyProperties(Map<String, String> properties, Map<String, String> previousProperties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            previousProperties.put(entry.getKey(), System.getProperty(entry.getKey()));
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    private static void restoreProperties(Map<String, String> previousProperties, Set<String> touched) {
        for (String name : touched) {
            String previous = previousProperties.get(name);
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }

    private static void stageSetup(DirectSourceInvocation invocation, Path stagingRoot) throws IOException {
        Path pyproject = stagingRoot.resolve("pyproject.toml");
        if (invocation.setup() != null) {
            Files.copy(invocation.setup(), pyproject, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Files.writeString(pyproject, DEFAULT_PYPROJECT);
    }

    private static void stageSources(List<Path> sources, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        for (Path source : sources) {
            Path resolved = source.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved)) {
                copyDirectoryContents(resolved, targetDir);
            } else if (Files.isRegularFile(resolved)) {
                Files.copy(resolved, targetDir.resolve(resolved.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new IllegalArgumentException("Source does not exist: " + source);
            }
        }
    }

    private static void stageConfig(List<Path> configs, Path targetDir) throws IOException {
        if (configs.isEmpty()) {
            return;
        }
        Files.createDirectories(targetDir);
        for (Path config : configs) {
            Path resolved = config.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved)) {
                copyDirectoryContents(resolved, targetDir);
            } else if (Files.isRegularFile(resolved)) {
                Files.copy(resolved, targetDir.resolve(resolved.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new IllegalArgumentException("Config path does not exist: " + config);
            }
        }
    }

    private static void copyDirectoryContents(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteDirectoryBestEffort(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup only.
        }
    }

    enum ToolCommand {
        INSTALL("install"),
        PROCESS("process"),
        RUN("run"),
        TEST("test"),
        VALIDATE_CONFIG("validate-config"),
        TEST_RESOURCES_SERVER("test-resources-server");

        private final String cliValue;

        ToolCommand(String cliValue) {
            this.cliValue = cliValue;
        }

        static ToolCommand fromCliValue(String value) {
            for (ToolCommand command : values()) {
                if (command.cliValue.equals(value)) {
                    return command;
                }
            }
            throw new IllegalArgumentException("Unsupported pyronaut-dev command: " + value);
        }
    }

    interface DelegateInvoker {
        int invoke(ToolCommand command, String... args);
    }

    interface DirectSourceRunner {
        int run(DirectSourceInvocation invocation, Path stagingRoot) throws Exception;
    }

    private static final class DefaultDirectSourceRunner implements DirectSourceRunner {
        @Override
        public int run(DirectSourceInvocation invocation, Path stagingRoot) throws Exception {
            return runInMemoryApplication(invocation, stagingRoot);
        }
    }

    private static final class DefaultDelegateInvoker implements DelegateInvoker {
        @Override
        public int invoke(ToolCommand command, String... args) {
            return switch (command) {
                case INSTALL -> new CommandLine(new PyronautInstallMain()).execute(args);
                case PROCESS -> new CommandLine(new PyronautProcessorMain()).execute(args);
                case RUN -> new CommandLine(new PyronautRunMain()).execute(args);
                case TEST -> new CommandLine(new PyronautTestMain()).execute(args);
                case VALIDATE_CONFIG -> new CommandLine(new PyronautValidateConfigMain()).execute(args);
                case TEST_RESOURCES_SERVER -> new CommandLine(new PyronautTestResourcesServerMain()).execute(args);
            };
        }
    }

    record DirectSourceInvocation(boolean test,
                                  Path setup,
                                  List<Path> configs,
                                  List<Path> sources,
                                  List<Path> testSources,
                                  Map<String, String> properties) {
        DirectSourceInvocation {
            configs = List.copyOf(configs);
            sources = List.copyOf(sources);
            testSources = List.copyOf(testSources);
            properties = Map.copyOf(properties);
        }
    }

    private static final String DEFAULT_PYPROJECT = """
        [project]
        name = "pyronaut-direct-source"
        version = "0.1.0"
        dynamic = ["scripts"]

        [build-system]
        requires = ["setuptools", "wheel", "tomli"]
        build-backend = "setuptools.build_meta"

        [tool.pyronaut]
        repositories = [
          "mavenCentral",
          "https://repo.gradle.org/gradle/libs-releases"
        ]

        [tool.pyronaut.dependencies]
        runtime = [
          "io.micronaut:micronaut-inject-python",
          "io.micronaut:micronaut-context-python",
          "io.micronaut:micronaut-http-server-netty",
          "io.micronaut:micronaut-json-core",
          "io.micronaut:micronaut-jackson-databind",
          "ch.qos.logback:logback-classic"
        ]
        build = [
          "io.micronaut:micronaut-inject-python",
          "io.micronaut:micronaut-context-python"
        ]
        test = [
          "io.micronaut.pyronaut:micronaut-pyronaut-pytest",
          "io.micronaut.test:micronaut-test-junit5",
          "org.junit.jupiter:junit-jupiter-engine",
          "org.junit.platform:junit-platform-launcher"
        ]
        """;
}
