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
import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.BeanResolutionTraceMode;
import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.terminal.PhaseReporter;
import io.micronaut.pyronaut.config.terminal.Terminal;
import io.micronaut.pyronaut.install.PyronautInstallMain;
import io.micronaut.pyronaut.install.DirectSourceDependencyResolver;
import io.micronaut.pyronaut.install.InstallProgressReporter;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderApplicationContextConfigurers;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanDefinitionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.config.model.JvmOptionsFile;
import io.micronaut.pyronaut.config.model.NativeProvidedJarResolver;
import io.micronaut.pyronaut.config.model.PyronautRuntimeProperties;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarationRequest;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarations;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarationsProcessor;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarationsVisitor;
import io.micronaut.pyronaut.dev.runtime.PyronautDevTestResourcesPropertySourceLoader;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.pyronaut.processor.PyronautProcessorMain;
import io.micronaut.pyronaut.test.PyronautTestMain;
import io.micronaut.pyronaut.test.TestProgressReporter;
import io.micronaut.python.processing.model.ScriptDef;
import io.micronaut.pyronaut.testresources.DirectSourceTestResourcesSession;
import io.micronaut.pyronaut.testresources.PyronautTestResourcesServerMain;
import io.micronaut.pyronaut.validateconfig.PyronautValidateConfigMain;
import io.micronaut.test.pytest.execution.JUnitReportWriter;
import io.micronaut.python.compiler.InMemoryBeanDefinitionsProvider;
import io.micronaut.python.compiler.PyronautCompiler;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.runtime.Micronaut;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.engine.TestExecutionResult;
import org.graalvm.polyglot.Context;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int TESTS_FAILED = 1;
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String PROJECT_DIR_PROPERTY = "pyronaut.dev.project.dir";
    private static final String BUILD_DEPENDENCIES_MANIFEST = "resolved-build-dependencies";
    private static final String RUNTIME_DEPENDENCIES_MANIFEST = "resolved-runtime-dependencies";
    private static final String DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST = "resolved-development-runtime-dependencies";
    private static final String TEST_DEPENDENCIES_MANIFEST = "resolved-test-dependencies";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String MICRONAUT_SERVER_PORT = "micronaut.server.port";
    private static final String MICRONAUT_ENVIRONMENTS = "micronaut.environments";
    private static final String CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT = "micronaut.jsonschema.configuration.validator.fail-on-not-present";
    private static final String CONFIGURATION_VALIDATOR_SUPPRESSIONS = "micronaut.jsonschema.configuration.validator.suppressions";
    private static final String MICRONAUT_TEST_RESOURCES_ENABLED = "micronaut.test.resources.enabled";
    private static final String TEST_RESOURCES_CLIENT_CLASSPATH = "pyronaut.dev.test.resources.client.classpath";
    private static final String DIRECT_COMPILER_CLASSPATH = "pyronaut.dev.compiler.class.path";
    private static final String DIRECT_APPLICATION_CLASSPATH = "pyronaut.dev.application.class.path";
    private static final String DIRECT_COMPILE_PYTHON_BYTECODE = "pyronaut.dev.compile-python-bytecode";
    private static final String DIRECT_COMMAND = "pyronaut.dev.direct.command";
    private static final String PROCESSOR_DAEMON_COMMAND_PREFIX = "pyronaut.processor.daemon.command-prefix";
    private static final String DEFAULT_TEST_SERVER_PORT = "0";
    private static final String NETTY_NO_UNSAFE = "io.netty.noUnsafe";
    private static final String SUN_MISC_UNSAFE_MEMORY_ACCESS = "sun.misc.unsafe.memory.access";
    private static final String VERIFY_SYSTEM_RESOURCE = "pyronaut.dev.verify-system-resource";
    private static final String VERIFY_SYSTEM_CLASS = "pyronaut.dev.verify-system-class";
    private static final String VERIFY_SYSTEM_CLASS_RESOURCE = "pyronaut.dev.verify-system-class-resource";
    private static final String PROPERTY_SOURCE_LOADER_SERVICE = "META-INF/services/io.micronaut.context.env.PropertySourceLoader";
    private static final String OPENAPI_SWAGGER_PATHS = "micronaut.router.static-resources.swagger.paths";
    private static final String OPENAPI_SWAGGER_MAPPING = "micronaut.router.static-resources.swagger.mapping";
    private static final String OPENAPI_SWAGGER_UI_PATHS = "micronaut.router.static-resources.swagger-ui.paths";
    private static final String OPENAPI_SWAGGER_UI_MAPPING = "micronaut.router.static-resources.swagger-ui.mapping";
    private static final String OPENAPI_REDOC_PATHS = "micronaut.router.static-resources.redoc.paths";
    private static final String OPENAPI_REDOC_MAPPING = "micronaut.router.static-resources.redoc.mapping";
    private static final String OPENAPI_VIEWS_SPEC = "micronaut.openapi.views.spec";
    private static final String PROPERTY_EXPRESSION_RESOLVER_SERVICE = "META-INF/services/io.micronaut.context.env.PropertyExpressionResolver";
    private static final String APPLICATION_CONTEXT_CONFIGURER_SERVICE = "META-INF/services/io.micronaut.context.ApplicationContextConfigurer";
    private static final String TEST_RESOURCES_RESOLVER_SERVICE = "META-INF/services/io.micronaut.testresources.core.TestResourcesResolver";
    private static final String APPLICATION_VFS_FILESLIST_RESOURCE = "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt";
    private static final String MICRONAUT_METADATA_PREFIX = "META-INF/micronaut/";
    private static final String TEST_RESOURCES_PACKAGE = "io.micronaut.testresources.";
    private static final String PYRONAUT_TEST_RESOURCES_PACKAGE = "io.micronaut.pyronaut.testresources.";
    private static final String MICRONAUT_PYTHON_PACKAGE = "io.micronaut.context.python.";
    private static final String MICRONAUT_PYTHON_ENABLED = "micronaut.python.enabled";
    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern JAVA_TYPE_PATTERN = Pattern.compile("\\b(?:class|interface|record|enum)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern JAVA_BLOCK_COMMENT_PATTERN = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern JAVA_LINE_COMMENT_PATTERN = Pattern.compile("(?m)(?:^|(?<=\\s))//[^\\n]*");
    private static final Pattern PYTHON_TYPE_PATTERN = Pattern.compile("(?m)^\\s*class\\s+([A-Za-z_][\\w]*)\\s*[:(]");
    private static final Pattern PYTHON_JUNIT_MODULE_PATTERN = Pattern.compile(
        "(?m)^\\s*from\\s+micronaut\\.test\\.extensions\\.junit5\\.annotation\\s+import\\s+.*\\bMicronautTest\\b.*$"
    );
    private static final List<String> BUNDLED_TEST_RESOURCES_JARS = List.of(
        "micronaut-test-resources-",
        "micronaut-pyronaut-test-resources-server-"
    );
    private static final Set<String> PRUNED_TEST_DISCOVERY_DIRECTORIES = Set.of(
        ".git",
        ".venv",
        "venv",
        "node_modules",
        DEFAULT_PYRONAUT_DIR
    );
    private static final Set<String> TOOL_COMMANDS = Set.of(
        "install",
        "process",
        "run",
        "test",
        "validate-config",
        "test-resources-server"
    );

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    @CommandLine.Unmatched
    List<String> directArgs = new ArrayList<>();

    private final DelegateInvoker delegateInvoker;
    private final DirectSourceRunner directSourceRunner;
    private final boolean directTest;

    PyronautDevMain(DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner) {
        this(delegateInvoker, directSourceRunner, false);
    }

    private PyronautDevMain(DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner, boolean directTest) {
        this.delegateInvoker = delegateInvoker;
        this.directSourceRunner = directSourceRunner;
        this.directTest = directTest;
    }

    static void main(String[] args) {
        JvmOptionsFile.apply();
        Terminal.notifyLaunched();
        // install only uses bundled beans; keep their native-image service definitions available.
        if (args.length == 0 || !"install".equals(args[0])) {
            PyronautRuntimeProperties.disableGraalVmImageSingletons();
        }
        configureNativeRuntimeDefaults();
        initializeLauncherLogging();
        Integer verificationExit = verifySystemResourceIfRequested();
        if (verificationExit != null) {
            System.exit(verificationExit);
        }
        verificationExit = verifySystemClassIfRequested();
        if (verificationExit != null) {
            System.exit(verificationExit);
        }
        System.exit(execute(args));
    }

    static void configureNativeRuntimeDefaults() {
        setDefaultProperty(NETTY_NO_UNSAFE, "false");
        setDefaultProperty(SUN_MISC_UNSAFE_MEMORY_ACCESS, "allow");
    }

    static void initializeLauncherLogging() {
        PyronautDevLogging.initializeLauncherLogging();
    }

    static Integer verifySystemResourceIfRequested() {
        String resource = System.getProperty(VERIFY_SYSTEM_RESOURCE);
        if (resource == null || resource.isBlank()) {
            return null;
        }
        try (InputStream input = ClassLoader.getSystemResourceAsStream(resource)) {
            if (input == null) {
                System.err.println("System resource not found: " + resource);
                return PRECONDITION_FAILED;
            }
            input.readAllBytes();
            return SUCCESS;
        } catch (IOException e) {
            System.err.println("Unable to read system resource " + resource + ": " + e.getMessage());
            return INTERNAL_ERROR;
        }
    }

    static Integer verifySystemClassIfRequested() {
        String className = System.getProperty(VERIFY_SYSTEM_CLASS);
        if (className == null || className.isBlank()) {
            return null;
        }
        try {
            ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            Class<?> loadedClass = Class.forName(className, false, systemClassLoader);
            String resource = System.getProperty(VERIFY_SYSTEM_CLASS_RESOURCE);
            if (resource != null && !resource.isBlank()) {
                URL resourceUrl = loadedClass.getResource(resource);
                if (resourceUrl != null) {
                    try (InputStream input = resourceUrl.openStream()) {
                        input.readAllBytes();
                    }
                }
                try (InputStream input = loadedClass.getResourceAsStream(resource)) {
                    if (input == null) {
                        System.err.println("System class resource not found: " + className + " " + resource);
                        return PRECONDITION_FAILED;
                    }
                    input.readAllBytes();
                }
            }
            Class.forName(className, true, systemClassLoader);
            return SUCCESS;
        } catch (Throwable e) {
            System.err.println("Unable to initialize system class " + className + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return INTERNAL_ERROR;
        }
    }

    private static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }

    private static void applyConfigurationValidationDefaults() {
        setDefaultProperty(CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT, "false");
        setDefaultProperty(CONFIGURATION_VALIDATOR_SUPPRESSIONS, "logger.levels.*");
    }

    public static int execute(String[] args) {
        return execute(args, new DefaultDelegateInvoker(), new DefaultDirectSourceRunner());
    }

    static int execute(String[] args, DelegateInvoker delegateInvoker) {
        return execute(args, delegateInvoker, new DefaultDirectSourceRunner());
    }

    static int execute(String[] args, DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner) {
        if (isDirectTestCommand(args)) {
            PyronautDevMain command = new PyronautDevMain(delegateInvoker, directSourceRunner, true);
            command.directArgs = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            return command.call();
        }
        if (isDirectRunCommand(args)) {
            return new CommandLine(new PyronautDevMain(delegateInvoker, directSourceRunner))
                .execute(Arrays.copyOfRange(args, 1, args.length));
        }
        if (args.length > 0 && TOOL_COMMANDS.contains(args[0])) {
            ToolCommand command = ToolCommand.fromCliValue(args[0]);
            String[] forwarded = Arrays.copyOfRange(args, 1, args.length);
            return delegateInvoker.invoke(command, forwarded);
        }
        return new CommandLine(new PyronautDevMain(delegateInvoker, directSourceRunner)).execute(args);
    }

    private static boolean isDirectRunCommand(String[] args) {
        if (args.length < 2 || !"run".equals(args[0])) {
            return false;
        }
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if ("--project-dir".equals(argument)) {
                return false;
            }
            if (argument.endsWith(".java") || argument.endsWith(".py")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Integer call() {
        DirectSourceInvocation invocation;
        try {
            invocation = parseDirectSourceArgs(directArgs, directTest);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return USAGE_ERROR;
        }
        if (invocation.sources().isEmpty()) {
            if (commandSpec != null) {
                commandSpec.commandLine().usage(System.err);
            } else {
                // Direct test invocations are constructed without picocli, so no @Spec is injected.
                System.err.println("Direct tests require at least one application source before '--'."
                    + " Usage: pyronaut test [options] <sources>... [-- <test sources>...]");
            }
            return USAGE_ERROR;
        }
        return runDirectSources(invocation);
    }

    static DirectSourceInvocation parseDirectTestSourceArgs(List<String> args) {
        return parseDirectSourceArgs(args, true);
    }

    static DirectSourceInvocation parseDirectSourceArgs(List<String> args) {
        return parseDirectSourceArgs(args, false);
    }

    private static DirectSourceInvocation parseDirectSourceArgs(List<String> args, boolean test) {
        String port = null;
        Path setup = null;
        Path report = null;
        List<Path> configs = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        List<Path> testSources = new ArrayList<>();
        Map<String, String> properties = new LinkedHashMap<>();
        String verboseLogger = null;
        boolean afterSeparator = false;
        boolean testSourceSeparator = false;

        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if ("--".equals(token)) {
                afterSeparator = true;
                testSourceSeparator = true;
                continue;
            }
            if (!afterSeparator) {
                switch (token) {
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
                    case "--report" -> {
                        // A bare --report keeps the default location, which is resolved
                        // relative to the direct-source cache directory at execution time.
                        report = i + 1 < args.size()
                                && !args.get(i + 1).startsWith("-")
                                && !isSourceSelector(args.get(i + 1))
                            ? Path.of(args.get(++i)) : null;
                        continue;
                    }
                    case "--disable-test-resources" -> {
                        properties.put(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY, "false");
                        properties.put(MICRONAUT_TEST_RESOURCES_ENABLED, "false");
                        continue;
                    }
                    case "--compile-python-bytecode" -> {
                        properties.put(DIRECT_COMPILE_PYTHON_BYTECODE, "true");
                        continue;
                    }
                    case "--verbose" -> {
                        verboseLogger = "";
                        if (i + 1 < args.size() && !args.get(i + 1).startsWith("-") && !isSourceSelector(args.get(i + 1))) {
                            verboseLogger = args.get(++i);
                        }
                        continue;
                    }
                    default -> {
                        if (token.startsWith("--verbose=")) {
                            verboseLogger = token.substring("--verbose=".length());
                            continue;
                        }
                        if (token.startsWith("-D") && token.length() > 2) {
                            putProperty(properties, token.substring(2));
                            continue;
                        }
                    }
                }
            }
            addSourceSelector(afterSeparator ? testSources : sources, token);
        }
        if (port != null) {
            properties.put("micronaut.server.port", port);
        }
        addDefaultOpenApiExposure(properties, configs);
        return new DirectSourceInvocation(test, setup, report, List.copyOf(configs), List.copyOf(sources), List.copyOf(testSources), testSourceSeparator, Map.copyOf(properties), verboseLogger);
    }

    private static boolean isSourceSelector(String value) {
        String lowerCase = value.toLowerCase(Locale.ROOT);
        return lowerCase.endsWith(".java") || lowerCase.endsWith(".py") || lowerCase.contains("*");
    }

    private static boolean isDirectTestCommand(String[] args) {
        if (args.length < 2 || !"test".equals(args[0])) {
            return false;
        }
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if ("--project-dir".equals(argument) || isProjectTestSelector(argument)) {
                // Direct source tests have no test selection option: a Gradle-like
                // selector such as `--tests tests/test_app.py` names a project test.
                return false;
            }
            if ("--".equals(argument) || isSourceSelector(argument) || isDirectory(argument)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProjectTestSelector(String argument) {
        return "--tests".equals(argument) || argument.startsWith("--tests=");
    }

    private static boolean isDirectory(String value) {
        try {
            return Files.isDirectory(Path.of(value));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void addSourceSelector(List<Path> destination, String token) {
        if (!token.contains("*") && !token.contains("?") && !token.contains("[")) {
            destination.add(Path.of(token));
            return;
        }
        Path selector = Path.of(token);
        Path parent = selector.getParent() == null ? Path.of(".") : selector.getParent();
        if (!Files.isDirectory(parent)) {
            destination.add(selector);
            return;
        }
        String pattern = selector.getFileName().toString();
        PathMatcher matcher = parent.getFileSystem().getPathMatcher("glob:" + pattern);
        try (var files = Files.list(parent)) {
            List<Path> matches = files.filter(path -> matcher.matches(path.getFileName())).sorted().toList();
            if (matches.isEmpty()) {
                destination.add(selector);
            } else {
                destination.addAll(matches);
            }
        } catch (IOException e) {
            destination.add(selector);
        }
    }

    private static List<Path> findImplicitTestSources(SourceType sourceType) throws IOException {
        return findImplicitTestSources(Path.of(".").toAbsolutePath().normalize(), sourceType);
    }

    static List<Path> findImplicitTestSources(Path root, SourceType sourceType) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> matches = new ArrayList<>();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(normalizedRoot) && isPrunedTestDiscoveryDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && isImplicitTestSource(file, sourceType)) {
                    matches.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // Unreadable entries must not abort implicit test discovery.
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(matches);
        return List.copyOf(matches);
    }

    private static boolean isPrunedTestDiscoveryDirectory(Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.startsWith(".") || PRUNED_TEST_DISCOVERY_DIRECTORIES.contains(name);
    }

    private static boolean isImplicitTestSource(Path path, SourceType sourceType) {
        String name = path.getFileName().toString();
        if (sourceType == SourceType.JAVA) {
            return name.endsWith("Test.java");
        }
        if ("conftest.py".equals(name)) {
            return false;
        }
        return name.endsWith("Test.py") || name.startsWith("test_") && name.endsWith(".py");
    }

    private Integer runDirectSources(DirectSourceInvocation invocation) {
        Path stagingRoot = null;
        Map<String, String> previousProperties = new LinkedHashMap<>();
        DirectSourceTestResourcesSession testResourcesSession = null;
        String directCommand = System.getProperty(
            DIRECT_COMMAND,
            invocation.properties().get(DIRECT_COMMAND)
        );
        boolean testResourcesEligible = invocation.setup() == null
            && (invocation.test() || "dev".equals(directCommand))
            && !testResourcesDisabled(invocation);
        try {
            SourceType sourceType = sourceType(invocation.sources());
            if (invocation.test()) {
                if (invocation.testSources().isEmpty()) {
                    if (invocation.testSourceSeparator()) {
                        throw new IllegalArgumentException("Direct tests require test sources after '--'");
                    }
                    invocation = invocation.withTestSources(findImplicitTestSources(sourceType));
                }
                SourceType testSourceType = sourceType(invocation.testSources());
                if (sourceType != testSourceType) {
                    throw new IllegalArgumentException("Application and test sources must use the same language");
                }
            }
            stagingRoot = directSourceStagingRoot(invocation);
            Files.createDirectories(stagingRoot);
            stageSetup(invocation, stagingRoot);
            Path stagedSources = stagingRoot.resolve("src");
            resetStagedSources(stagedSources);
            stageSources(invocation.sources(), stagedSources);
            if (!invocation.testSources().isEmpty()) {
                stageSources(invocation.testSources(), stagedSources);
            }
            stageConfig(invocation.configs(), stagingRoot.resolve("config"));
            applyProperties(invocation.properties(), previousProperties);
            applyConfigurationValidationDefaults();
            if (invocation.setup() == null) {
                applyProperty(
                    PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY,
                    "false",
                    previousProperties
                );
            }
            if (sourceType == SourceType.JAVA && !invocation.properties().containsKey(MICRONAUT_PYTHON_ENABLED)) {
                previousProperties.put(MICRONAUT_PYTHON_ENABLED, System.getProperty(MICRONAUT_PYTHON_ENABLED));
                System.setProperty(MICRONAUT_PYTHON_ENABLED, "false");
            }
            if (invocation.setup() != null) {
                int install = delegateInvoker.invoke(ToolCommand.INSTALL, "--project-dir", projectDirectory(stagingRoot).toString());
                if (install != SUCCESS) {
                    return install;
                }
            }
            try {
                return directSourceRunner.run(invocation, stagingRoot);
            } catch (DirectSourceDeclarationRequest request) {
                DirectSourceDependencyResolver.LaunchResult launch = resolveDirectSourceDeclarations(
                    invocation,
                    stagingRoot,
                    request,
                    previousProperties,
                    testResourcesEligible
                );
                if (launch.testResourcesRequired()) {
                    Path projectRoot = projectCacheDirectory(invocation, stagingRoot).getParent();
                    testResourcesSession = DirectSourceTestResourcesSession.open(projectRoot);
                    applyProperties(testResourcesSession.clientProperties(), previousProperties);
                    applyProperty(
                        PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY,
                        "true",
                        previousProperties
                    );
                    applyProperty(
                        TEST_RESOURCES_CLIENT_CLASSPATH,
                        String.join(File.pathSeparator, launch.runtime()),
                        previousProperties
                    );
                }
                return directSourceRunner.run(invocation, stagingRoot);
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (Exception e) {
            System.err.println("Direct source launch failed: " + e);
            return INTERNAL_ERROR;
        } finally {
            if (testResourcesSession != null) {
                testResourcesSession.close();
            }
            restoreProperties(previousProperties, previousProperties.keySet());
            DirectSourceDeclarationState.clear();
        }
    }

    private static Path directSourceStagingRoot(DirectSourceInvocation invocation) {
        String configured = System.getProperty(PROJECT_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize().resolve(DEFAULT_PYRONAUT_DIR);
        }
        Path source = invocation.sources().getFirst().toAbsolutePath().normalize();
        Path sourceDirectory = Files.isDirectory(source) ? source : source.getParent();
        return sourceDirectory.resolve(DEFAULT_PYRONAUT_DIR);
    }

    private static DirectSourceDependencyResolver.LaunchResult resolveDirectSourceDeclarations(
        DirectSourceInvocation invocation,
        Path stagingRoot,
        DirectSourceDeclarationRequest request,
        Map<String, String> previousProperties,
        boolean testResourcesEligible) throws IOException {
        DirectSourceDeclarations declarations = request.declarations();
        List<String> build = declarations.dependencies().stream().filter(DirectSourceDeclarations.Dependency::build).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        List<String> runtime = declarations.dependencies().stream().filter(declaration -> declaration.scope() == DirectSourceDeclarations.Scope.RUNTIME).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        List<String> test = declarations.dependencies().stream().filter(DirectSourceDeclarations.Dependency::test).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        List<String> boms = declarations.dependencies().stream().filter(DirectSourceDeclarations.Dependency::bom).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        Map<String, List<String>> exclusions = new LinkedHashMap<>();
        declarations.dependencies().forEach(dependency -> exclusions.put(moduleKey(dependency.coordinate()), dependency.exclusions()));
        DirectSourceDependencyResolver.LaunchResult result;
        try (InstallProgressReporter progress = InstallProgressReporter.create("auto")) {
            result = new DirectSourceDependencyResolver().resolveForLaunch(
                projectCacheDirectory(invocation, stagingRoot),
                build,
                runtime,
                test,
                boms,
                exclusions,
                declarations.repositories(),
                declarations.runtimeProperties(),
                testResourcesEligible,
                progress
            );
        }
        if (invocation.verbose()) {
            System.out.println("Direct source dependency resolution " + (result.cacheHit() ? "cache hit" : "completed")
                + ": " + declarations.dependencies() + (declarations.repositories().isEmpty() ? "" : ", repositories=" + declarations.repositories()));
        }
        io.micronaut.pyronaut.dev.DirectSourceDeclarationState.setRuntimeProperties(declarations.runtimeProperties());
        io.micronaut.pyronaut.dev.DirectSourceDeclarationState.setTestResourcesRequired(result.testResourcesRequired());
        String resolvedProperty = io.micronaut.pyronaut.directsource.DirectSourceDeclarationState.RESOLVED_PROPERTY;
        previousProperties.put(resolvedProperty, System.getProperty(resolvedProperty));
        System.setProperty(resolvedProperty, "true");
        declarations.runtimeProperties().forEach((name, value) ->
            applyProperty(name, value, previousProperties)
        );
        declarations.buildProperties().forEach((name, value) ->
            applyProperty("micronaut.processing." + name, value, previousProperties)
        );
        return result;
    }

    /**
     * Compile the staged sources, showing the compilation as a phase. A
     * {@link DirectSourceDeclarationRequest} ends the phase as a discovery of
     * declarations and propagates so the caller can resolve them.
     */
    private static ClassLoader compileDirectSources(PyronautCompiler.Builder builder, DirectSourceInvocation invocation) {
        int sourceCount = invocation.sources().size() + invocation.testSources().size();
        String sources = sourceCount + (sourceCount == 1 ? " source" : " sources");
        try (PhaseReporter progress = PhaseReporter.create(invocation.verbose())) {
            PhaseReporter.Phase phase = progress.start("Compiling " + describeSources(invocation));
            try {
                ClassLoader loader = builder.build().buildClassLoader();
                phase.done("Compiled " + sources);
                return loader;
            } catch (DirectSourceDeclarationRequest request) {
                List<DirectSourceDeclarations.Dependency> dependencies = request.declarations().dependencies();
                phase.done("Found " + dependencies.size() + (dependencies.size() == 1 ? " dependency declaration" : " dependency declarations")
                    + " in " + sources);
                for (DirectSourceDeclarations.Dependency dependency : dependencies) {
                    progress.hint(dependency.coordinate() + " (" + dependency.scope().name().toLowerCase(Locale.ROOT) + ")");
                }
                throw request;
            } catch (RuntimeException e) {
                phase.fail("Compilation of " + sources + " failed");
                throw e;
            }
        }
    }

    private static String describeSources(DirectSourceInvocation invocation) {
        List<String> names = new ArrayList<>();
        for (Path source : invocation.sources()) {
            names.add(source.getFileName().toString());
        }
        for (Path source : invocation.testSources()) {
            names.add(source.getFileName().toString());
        }
        if (names.size() > 3) {
            return names.get(0) + ", " + names.get(1) + " and " + (names.size() - 2) + " more";
        }
        return String.join(", ", names);
    }

    static String moduleKey(String coordinate) {
        String[] parts = coordinate.split(":");
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : coordinate;
    }

    private static int runInMemoryApplication(DirectSourceInvocation invocation, Path stagingRoot) throws Exception {
        if (invocation.test()) {
            return runInMemoryTests(invocation, stagingRoot);
        }
            Path pyronautDir = projectCacheDirectory(invocation, stagingRoot);
            DirectSourceClasspaths classpaths = resolveDirectSourceClasspaths(invocation, pyronautDir);
        logClasspaths(invocation, classpaths);
        List<URL> runtimeUrls = toUrls(classpaths.runtime());
        Path configDir = stagingRoot.resolve("config");
        if (Files.isDirectory(configDir)) {
            runtimeUrls.add(configDir.toUri().toURL());
        }
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        BeanIntrospectionsProvider previousBeanIntrospectionsProvider = null;
        ClassLoader launcherClassLoader = directSourceLauncherClassLoader(invocation);
        try (URLClassLoader runtimeClassLoader = directSourceRuntimeClassLoader(runtimeUrls, invocation, launcherClassLoader)) {
            long now = System.currentTimeMillis();
            PyronautCompiler.Builder builder = PyronautCompiler.builder()
                .annotationProcessorPath(toFiles(classpaths.processor()))
                .classpath(toFiles(classpaths.compile()))
                .runtimeClasspath(toFiles(classpaths.runtime()))
                .targetDir(pyronautDir.resolve("classes").toFile())
                .options(directSourceCompilerOptions(false))
                .parentClassLoader(runtimeClassLoader);
            if (invocation.setup() == null) {
                DirectSourceDeclarationsVisitor declarationsVisitor = new DirectSourceDeclarationsVisitor();
                builder.annotationProcessors(List.of(new DirectSourceDeclarationsProcessor()));
                registerDirectDeclarationVisitors(builder, invocation, declarationsVisitor);
            }
            configureDirectSource(builder, invocation, stagingRoot);
            builder.compilePythonBytecode("true".equals(invocation.properties().get(DIRECT_COMPILE_PYTHON_BYTECODE)));
            ClassLoader applicationClassLoader = compileDirectSources(builder, invocation);
            if (invocation.verbose()) {
                System.out.println("Processing Time: " + (System.currentTimeMillis() - now) + "ms");
            }
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            PyronautDevLogging.initializeApplicationLogging(invocation.verboseLogger());
            now = System.currentTimeMillis();
            PhaseReporter startup = PhaseReporter.create(invocation.verbose());
            PhaseReporter.Phase startingApplication = startup.start("Starting application");
            ApplicationContextBuilder micronaut = Micronaut.build(new String[0])
                .classLoader(applicationClassLoader)
                .properties(directSourceApplicationProperties(invocation))
                .beanResolutionTrace(invocation.verbose() ? BeanResolutionTraceMode.STANDARD_OUT : BeanResolutionTraceMode.NONE)
                .deducePackage(false)
                .deduceCloudEnvironment(false)
                .deduceEnvironment(false);
            ContextClassLoaderApplicationContextConfigurers.configure(micronaut, applicationClassLoader);
            // Apply this last so runtime-discovered configurers cannot replace
            // the generated direct-source bean definitions provider.
            micronaut.beanDefinitionsProvider(
                directSourceBeanDefinitionsProvider(invocation, () -> applicationClassLoader, runtimeClassLoader)
            );
            List<String> configLocations = toConfigLocations(invocation.configs());
            if (!configLocations.isEmpty()) {
                micronaut.overrideConfigLocations(configLocations.toArray(String[]::new));
            }
            try {
                micronaut.start();
                startingApplication.done("Application started");
            } catch (RuntimeException e) {
                startingApplication.fail("Application failed to start");
                throw e;
            } finally {
                startup.close();
            }
            if (invocation.verbose()) {
                System.out.println("Context Startup Time: " + (System.currentTimeMillis() - now) + "ms");
            }
            blockUntilInterrupted();
            return SUCCESS;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            if (previousBeanIntrospectionsProvider != null) {
                BeanIntrospectionProviders.set(previousBeanIntrospectionsProvider);
            }
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
        }
    }

    private static int runInMemoryTests(DirectSourceInvocation invocation, Path stagingRoot) throws Exception {
        boolean pythonSource = sourceType(invocation.sources()) == SourceType.PYTHON;
        DirectPythonContextState previousPythonContext = pythonSource ? detachPythonContext() : null;
        Path pyronautDir = projectCacheDirectory(invocation, stagingRoot);
        DirectSourceClasspaths classpaths = resolveDirectSourceClasspaths(invocation, pyronautDir);
        logClasspaths(invocation, classpaths);
        List<URL> runtimeUrls = toUrls(classpaths.runtime());
        Path configDir = stagingRoot.resolve("config");
        if (Files.isDirectory(configDir)) {
            runtimeUrls.add(configDir.toUri().toURL());
        }
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        String previousServerPortProperty = System.getProperty(MICRONAUT_SERVER_PORT);
        BeanIntrospectionsProvider previousBeanIntrospectionsProvider = null;
        ClassLoader launcherClassLoader = directSourceLauncherClassLoader(invocation);
        try (URLClassLoader runtimeClassLoader = directSourceRuntimeClassLoader(runtimeUrls, invocation, launcherClassLoader)) {
            DeferredGeneratedClassLoader testContextClassLoader = new DeferredGeneratedClassLoader(runtimeClassLoader);
            Thread.currentThread().setContextClassLoader(testContextClassLoader);
            DirectSourceApplicationContextConfigurer.set(
                directSourceBeanDefinitionsProvider(
                    invocation,
                    testContextClassLoader::generatedClassLoader,
                    runtimeClassLoader
                )
            );
            long now = System.currentTimeMillis();
            PyronautCompiler.Builder builder = PyronautCompiler.builder()
                .annotationProcessorPath(toFiles(classpaths.processor()))
                .classpath(toFiles(classpaths.compile()))
                .runtimeClasspath(toFiles(classpaths.runtime()))
                .targetDir(pyronautDir.resolve("classes").toFile())
                .options(directSourceCompilerOptions(true))
                .parentClassLoader(runtimeClassLoader);
            if (invocation.setup() == null) {
                DirectSourceDeclarationsVisitor declarationsVisitor = new DirectSourceDeclarationsVisitor();
                builder.annotationProcessors(List.of(new DirectSourceDeclarationsProcessor()));
                registerDirectDeclarationVisitors(builder, invocation, declarationsVisitor);
            }

            configureDirectSource(builder, invocation, stagingRoot);
            builder.compilePythonBytecode("true".equals(invocation.properties().get(DIRECT_COMPILE_PYTHON_BYTECODE)));
            ClassLoader applicationClassLoader = compileDirectSources(builder, invocation);
            testContextClassLoader.generatedClassLoader(applicationClassLoader);
            if (invocation.verbose()) {
                System.out.println("Processing Time: " + (System.currentTimeMillis() - now) + "ms");
            }
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            defaultTestServerPort();
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            // Open the reporter before the Python runtime bootstraps and the test
            // classes load: its "Starting test runtime" row covers that wait too.
            try (TestProgressReporter reporter = TestProgressReporter.create(originalErr, invocation.verbose())) {
                if (pythonSource) {
                    GraalPyContextFactory.bootstrapReusableContext(applicationClassLoader, Map.of(), GraalPyContextFactory.APPLICATION_MAIN);
                }

                now = System.currentTimeMillis();
                LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
                List<String> testClassNames = testClassNames(invocation.testSources());
                if (testClassNames.isEmpty()) {
                    throw new IllegalArgumentException("No JUnit test classes were found in the supplied test sources");
                }
                int selectedTestClasses = 0;
                for (String testClassName : testClassNames) {
                    Class<?> testClass = loadTestClass(testClassName, applicationClassLoader);
                    if (testClass == null) {
                        // Source scanning is heuristic (comments, nested types); skip what cannot be loaded.
                        if (invocation.verbose()) {
                            System.out.println("Skipping test class candidate " + testClassName + ": class not found");
                        }
                        continue;
                    }
                    requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
                    selectedTestClasses++;
                }
                if (selectedTestClasses == 0) {
                    throw new IllegalArgumentException("No JUnit test classes could be loaded from the supplied test sources: " + testClassNames);
                }
                // Direct source execution currently supports compiled JUnit modules only.
                // Do not let the pytest engine bootstrap (and require pytest on disk).
                if (pythonSource) {
                    requestBuilder.filters(EngineFilter.includeEngines("junit-jupiter"));
                }
                LauncherDiscoveryRequest request = requestBuilder.build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                List<JUnitReportWriter.TestResult> reportResults = new ArrayList<>();
                launcher.registerTestExecutionListeners(listener);
                launcher.registerTestExecutionListeners(new TestExecutionListener() {
                    private ByteArrayOutputStream out;
                    private ByteArrayOutputStream err;
                    private long startNanos;

                    @Override
                    public void executionStarted(TestIdentifier identifier) {
                        if (identifier.isTest()) {
                            startNanos = System.nanoTime();
                            out = new ByteArrayOutputStream();
                            err = new ByteArrayOutputStream();
                            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
                            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                        if (identifier.isTest()) {
                            System.setOut(originalOut);
                            System.setErr(originalErr);
                            JUnitReportWriter.Status status = switch (result.getStatus()) {
                                case SUCCESSFUL -> JUnitReportWriter.Status.PASSED;
                                case FAILED -> JUnitReportWriter.Status.FAILED;
                                case ABORTED -> JUnitReportWriter.Status.SKIPPED;
                            };
                            reportResults.add(new JUnitReportWriter.TestResult(
                                identifier.getDisplayName(), status,
                                result.getThrowable().map(Throwable::toString).orElse(""),
                                out == null ? "" : out.toString(StandardCharsets.UTF_8),
                                err == null ? "" : err.toString(StandardCharsets.UTF_8),
                                System.nanoTime() - startNanos));
                        }
                    }
                });
                launcher.registerTestExecutionListeners(reporter);
                launcher.execute(request);
                TestExecutionSummary summary = listener.getSummary();
                if (invocation.verbose()) {
                    summary.printTo(new PrintWriter(System.out, true, StandardCharsets.UTF_8));
                }
                Path report = invocation.report() == null
                    ? pyronautDir.resolve("reports").resolve("tests")
                    : invocation.report();
                Path reportDirectory = writeReports(report, summary, reportResults);
                reporter.summary(reportDirectory);
                if (invocation.verbose()) {
                    System.out.println("Test Execution Time: " + (System.currentTimeMillis() - now) + "ms");
                }
                return summary.getFailures().isEmpty() ? SUCCESS : TESTS_FAILED;
            }
        } finally {
            if (pythonSource) {
                restorePythonContext(previousPythonContext);
            }
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            if (previousBeanIntrospectionsProvider != null) {
                BeanIntrospectionProviders.set(previousBeanIntrospectionsProvider);
            }
            DirectSourceApplicationContextConfigurer.clear();
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
            restoreSystemProperty(MICRONAUT_SERVER_PORT, previousServerPortProperty);
        }
    }

    private static Class<?> loadTestClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException e) {
            if (!className.startsWith("python.")) {
                return null;
            }
        }
        try {
            return Class.forName(className.substring("python.".length()), true, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static DirectPythonContextState detachPythonContext() {
        boolean initialized = PythonContextRuntime.isInitialized();
        Context context = initialized ? PythonContextRuntime.getContext() : null;
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        boolean reuse = PythonContextRuntime.isReuseContext();
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        if (context != null) {
            try {
                context.close(true);
            } catch (RuntimeException ignored) {
                // The context may already have been closed by its owning application.
            }
        }
        return new DirectPythonContextState(initialized, classLoader, reuse);
    }

    private static void restorePythonContext(DirectPythonContextState previous) throws IOException {
        if (PythonContextRuntime.isInitialized()) {
            Context directContext = PythonContextRuntime.getContext();
            PythonContextRuntime.setReuseContext(false);
            try {
                directContext.close(true);
            } catch (RuntimeException ignored) {
                // The Micronaut test context may already have closed it.
            } finally {
                PythonContextRuntime.resetContext();
            }
        }
        if (previous.initialized()) {
            ClassLoader classLoader = previous.classLoader() == null
                ? PyronautDevMain.class.getClassLoader()
                : previous.classLoader();
            GraalPyContextFactory.bootstrapReusableContext(
                classLoader,
                Map.of(),
                GraalPyContextFactory.APPLICATION_MAIN
            );
        }
        PythonContextRuntime.setReuseContext(previous.reuse());
    }

    private static Path writeReports(Path report, TestExecutionSummary summary, List<JUnitReportWriter.TestResult> results) throws IOException {
        if (report == null) {
            return null;
        }
        Path directory = report.toAbsolutePath().normalize();
        JUnitReportWriter.write(directory, summary, results);
        return directory;
    }

    private static List<String> testClassNames(List<Path> selectors) throws IOException {
        List<String> classNames = new ArrayList<>();
        for (Path file : sourceFiles(selectors)) {
            String source = Files.readString(file);
            if (file.getFileName().toString().endsWith(".java")) {
                source = stripJavaComments(source);
                Matcher packageMatcher = JAVA_PACKAGE_PATTERN.matcher(source);
                String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
                Matcher typeMatcher = JAVA_TYPE_PATTERN.matcher(source);
                while (typeMatcher.find()) {
                    String simpleName = typeMatcher.group(1);
                    String className = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
                    if (!classNames.contains(className)) {
                        classNames.add(className);
                    }
                }
            } else if (file.getFileName().toString().endsWith(".py")) {
                String packageName = pythonPackageName(file, selectors);
                Matcher junitModuleMatcher = PYTHON_JUNIT_MODULE_PATTERN.matcher(source);
                if (junitModuleMatcher.find()) {
                    Pattern callPattern = Pattern.compile("(?m)^\\s*(?:@?MicronautTest)\\s*(?:\\(|$)");
                    if (callPattern.matcher(source).find()) {
                        String moduleName = file.getFileName().toString().replaceFirst("\\.py$", "");
                        String className = packageName + "." + ScriptDef.toJavaClassName(moduleName);
                        if (!classNames.contains(className)) {
                            classNames.add(className);
                        }
                    }
                }
                Matcher typeMatcher = PYTHON_TYPE_PATTERN.matcher(source);
                while (typeMatcher.find()) {
                    String className = packageName + "." + typeMatcher.group(1);
                    if (!classNames.contains(className) && source.contains("@Test")) {
                        classNames.add(className);
                    }
                }
            }
        }
        return classNames;
    }

    static String stripJavaComments(String source) {
        String withoutBlockComments = JAVA_BLOCK_COMMENT_PATTERN.matcher(source).replaceAll("");
        return JAVA_LINE_COMMENT_PATTERN.matcher(withoutBlockComments).replaceAll("");
    }

    private static List<Path> sourceFiles(List<Path> selectors) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path selector : selectors) {
            Path resolved = selector.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved)) {
                try (var paths = Files.walk(resolved)) {
                    files.addAll(paths.filter(Files::isRegularFile)
                        .filter(path -> !isManagedSourcePath(resolved, path))
                        .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".py"))
                        .toList());
                }
            } else if (Files.isRegularFile(resolved)) {
                files.add(resolved);
            }
        }
        return files;
    }

    private static String pythonPackageName(Path file, List<Path> selectors) {
        for (Path selector : selectors) {
            Path directory = selector.toAbsolutePath().normalize();
            if (Files.isDirectory(directory) && file.toAbsolutePath().normalize().startsWith(directory)) {
                Path relativeParent = directory.relativize(file.getParent());
                if (relativeParent.toString().isEmpty()) {
                    return "python";
                }
                return relativeParent.toString().replace(File.separatorChar, '.');
            }
        }
        return "python";
    }

    private static void configureDirectSource(PyronautCompiler.Builder builder, DirectSourceInvocation invocation, Path stagingRoot) throws IOException {
        SourceType sourceType = sourceType(invocation.sources());
        if (sourceType == SourceType.JAVA) {
            builder.javaSrc(stagingRoot.resolve("src").toString());
        } else {
            builder.pythonSrc(stagingRoot.resolve("src").toString());
        }
    }

    static List<String> directSourceCompilerOptions(boolean testPass) {
        List<String> options = new ArrayList<>();
        if (testPass) {
            options.add("-Amicronaut.openapi.enabled=false");
        }
        System.getProperties().stringPropertyNames().stream()
            .filter(name -> name.startsWith("micronaut.processing."))
            .sorted()
            .forEach(name -> options.add("-A" + name.substring("micronaut.processing.".length()) + "=" + System.getProperty(name)));
        return options;
    }

    private static void registerDirectDeclarationVisitors(PyronautCompiler.Builder builder,
                                                           DirectSourceInvocation invocation,
                                                           DirectSourceDeclarationsVisitor visitor) throws IOException {
        SourceType sourceType = sourceType(invocation.sources());
        if (sourceType == SourceType.PYTHON) {
            builder.pythonSourceVisitors(List.of(visitor));
        }
    }

    static SourceType sourceType(List<Path> sources) throws IOException {
        boolean java = false;
        boolean python = false;
        for (Path source : sources) {
            Path resolved = source.toAbsolutePath().normalize();
            if (!Files.exists(resolved)) {
                throw new IllegalArgumentException("Source does not exist: " + source);
            }
            try (var paths = Files.walk(resolved)) {
                for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> !isManagedSourcePath(resolved, candidate))
                    .toList()) {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    java |= name.endsWith(".java");
                    python |= name.endsWith(".py");
                }
            }
        }
        if (java && python) {
            throw new IllegalArgumentException("Direct source execution cannot mix Java and Python sources");
        }
        if (!java && !python) {
            throw new IllegalArgumentException("Direct source must contain .java or .py files");
        }
        return java ? SourceType.JAVA : SourceType.PYTHON;
    }

    private static List<String> toConfigLocations(List<Path> configs) {
        List<String> locations = new ArrayList<>(configs.size());
        for (Path config : configs) {
            locations.add(config.toAbsolutePath().normalize().toUri().toString());
        }
        return locations;
    }

    private static Path resolveRunManifest(Path pyronautDir, boolean developmentMode) {
        if (developmentMode) {
            Path developmentManifest = pyronautDir.resolve(DEVELOPMENT_RUNTIME_DEPENDENCIES_MANIFEST);
            if (Files.exists(developmentManifest)) {
                return developmentManifest;
            }
        }
        return pyronautDir.resolve(RUNTIME_DEPENDENCIES_MANIFEST);
    }

    private static boolean isDevelopmentMode() {
        String environments = System.getProperty(MICRONAUT_ENVIRONMENTS, "");
        return Arrays.stream(environments.split(","))
            .map(String::trim)
            .anyMatch("dev"::equalsIgnoreCase);
    }

    private static Map<String, Object> directSourceApplicationProperties(DirectSourceInvocation invocation) throws IOException {
        Map<String, Object> properties = new LinkedHashMap<>(DirectSourceDeclarationState.runtimeProperties());
        if (sourceType(invocation.sources()) == SourceType.JAVA) {
            properties.putIfAbsent(MICRONAUT_PYTHON_ENABLED, false);
        }
        return Map.copyOf(properties);
    }

    private static ClassLoader directSourceLauncherClassLoader(DirectSourceInvocation invocation) {
        ClassLoader launcherClassLoader = PyronautDevMain.class.getClassLoader();
        if (controlPanelRequested(invocation)) {
            return launcherClassLoader;
        }
        return new DirectSourceLauncherClassLoader(new URL[0], launcherClassLoader);
    }

    private static URLClassLoader directSourceRuntimeClassLoader(List<URL> runtimeUrls,
                                                                 DirectSourceInvocation invocation,
                                                                 ClassLoader parent) {
        if (controlPanelRequested(invocation)) {
            return new URLClassLoader(runtimeUrls.toArray(URL[]::new), parent);
        }
        return new DirectSourceLauncherClassLoader(runtimeUrls.toArray(URL[]::new), parent);
    }

    private static BeanDefinitionsProvider directSourceBeanDefinitionsProvider(
        DirectSourceInvocation invocation,
        Supplier<ClassLoader> generatedClassLoaderSupplier,
        ClassLoader runtimeClassLoader
    ) {
        BeanDefinitionsProvider inMemoryProvider = new InMemoryBeanDefinitionsProvider();
        BeanDefinitionsProvider contextClassLoaderProvider = new ContextClassLoaderBeanDefinitionsProvider();
        return classLoader -> {
            Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
            ClassLoader generatedClassLoader = generatedClassLoaderSupplier.get();
            if (generatedClassLoader != null) {
                List<BeanDefinitionReference<?>> generated = inMemoryProvider.provide(generatedClassLoader);
                addBeanDefinitionReferences(references, generated, invocation);
                addBeanDefinitionReferences(
                    references,
                    loadGeneratedBeanDefinitionReferences(generatedClassLoader),
                    invocation
                );
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(runtimeClassLoader);
                addBeanDefinitionReferences(references, contextClassLoaderProvider.provide(runtimeClassLoader), invocation);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            return List.copyOf(references.values());
        };
    }

    private static List<BeanDefinitionReference<?>> loadGeneratedBeanDefinitionReferences(ClassLoader classLoader) {
        String resourceName = "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference";
        String resourcePrefix = "/CLASS_OUTPUT/" + resourceName + "/";
        List<BeanDefinitionReference<?>> references = new ArrayList<>();
        try {
            Enumeration<URL> resources = classLoader.getResources(resourceName);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String path = resource.getPath();
                int start = path.indexOf(resourcePrefix);
                if (start < 0) {
                    continue;
                }
                String className = URLDecoder.decode(
                    path.substring(start + resourcePrefix.length()),
                    StandardCharsets.UTF_8
                );
                try {
                    Object instance = Class.forName(className, true, classLoader)
                        .getDeclaredConstructor()
                        .newInstance();
                    if (instance instanceof BeanDefinitionReference<?> reference) {
                        references.add(reference);
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Ignore resources that do not represent a loadable generated bean definition.
                }
            }
        } catch (IOException ignored) {
            // No generated bean definitions found.
        }
        return references;
    }

    private static void addBeanDefinitionReferences(Map<String, BeanDefinitionReference<?>> references,
                                                    List<BeanDefinitionReference<?>> candidates,
                                                    DirectSourceInvocation invocation) {
        for (BeanDefinitionReference<?> reference : candidates) {
            if (invocation.setup() == null && isTestResourcesBeanDefinition(reference)) {
                continue;
            }
            if (isDirectJavaInvocation(invocation) && reference.getBeanDefinitionName().startsWith(MICRONAUT_PYTHON_PACKAGE)) {
                continue;
            }
            references.put(reference.getBeanDefinitionName(), reference);
        }
    }

    private static boolean isDirectJavaInvocation(DirectSourceInvocation invocation) {
        if (invocation.setup() != null) {
            return false;
        }
        try {
            return sourceType(invocation.sources()) == SourceType.JAVA;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isTestResourcesBeanDefinition(BeanDefinitionReference<?> reference) {
        String name = reference.getBeanDefinitionName();
        return name.startsWith(TEST_RESOURCES_PACKAGE)
            || name.startsWith(PYRONAUT_TEST_RESOURCES_PACKAGE);
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

    private static boolean isTestResourcesJar(Path path) {
        String name = path.getFileName().toString();
        return BUNDLED_TEST_RESOURCES_JARS.stream().anyMatch(name::startsWith);
    }

    private static boolean isControlPanelJar(Path path) {
        return path.getFileName().toString().startsWith("micronaut-control-panel-");
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

    private static void addDefaultOpenApiExposure(Map<String, String> properties, List<Path> configs) {
        if (properties.keySet().stream().anyMatch(PyronautDevMain::isOpenApiExposureProperty)) {
            return;
        }
        try {
            for (Path config : configs) {
                if (Files.isRegularFile(config)) {
                    String content = Files.readString(config);
                    if (content.contains("static-resources") && content.contains("swagger")) {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // A config read failure must not prevent dev from starting.
        }
        properties.putIfAbsent(OPENAPI_SWAGGER_PATHS, "classpath:META-INF/swagger");
        properties.putIfAbsent(OPENAPI_SWAGGER_MAPPING, "/swagger/**");
        properties.putIfAbsent(OPENAPI_SWAGGER_UI_PATHS, "classpath:META-INF/swagger/views/swagger-ui");
        properties.putIfAbsent(OPENAPI_SWAGGER_UI_MAPPING, "/swagger-ui/**");
        properties.putIfAbsent(OPENAPI_REDOC_PATHS, "classpath:META-INF/swagger/views/redoc");
        properties.putIfAbsent(OPENAPI_REDOC_MAPPING, "/redoc/**");
        properties.putIfAbsent(OPENAPI_VIEWS_SPEC, "swagger-ui.enabled=true,redoc.enabled=true");
    }

    private static boolean isOpenApiExposureProperty(String name) {
        return name.equals(OPENAPI_SWAGGER_PATHS)
            || name.equals(OPENAPI_SWAGGER_MAPPING)
            || name.equals(OPENAPI_SWAGGER_UI_PATHS)
            || name.equals(OPENAPI_SWAGGER_UI_MAPPING)
            || name.equals(OPENAPI_REDOC_PATHS)
            || name.equals(OPENAPI_REDOC_MAPPING);
    }

    private static void applyProperties(Map<String, String> properties, Map<String, String> previousProperties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            applyProperty(entry.getKey(), entry.getValue(), previousProperties);
        }
    }

    private static void applyProperty(String name, String value, Map<String, String> previousProperties) {
        previousProperties.putIfAbsent(name, System.getProperty(name));
        System.setProperty(name, value);
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

    private static List<Path> buildDependenciesForTests(List<Path> buildDependencies, List<Path> testDependencies) {
        List<Path> dependencies = new ArrayList<>(buildDependencies);
        for (Path dependency : testDependencies) {
            if (!dependencies.contains(dependency)) {
                dependencies.add(dependency);
            }
        }
        return dependencies;
    }

    private static DirectSourceClasspaths resolveDirectSourceClasspaths(DirectSourceInvocation invocation,
                                                                         Path pyronautDir) throws IOException {
        List<Path> build = readManifest(pyronautDir.resolve(BUILD_DEPENDENCIES_MANIFEST));
        boolean developmentMode = isDevelopmentMode();
        List<Path> runtime = new ArrayList<>(readManifest(resolveRunManifest(pyronautDir, developmentMode)));
        if (!controlPanelRequested(invocation)) {
            runtime.removeIf(PyronautDevMain::isControlPanelJar);
        }
        List<Path> test = invocation.test() ? readManifest(pyronautDir.resolve(TEST_DEPENDENCIES_MANIFEST)) : List.of();
        List<Path> compilerBase = new ArrayList<>(directCompilerClasspath(invocation));
        Optional<Path> buildAnnotationsJar = findPyronautBuildAnnotationsJar();
        if (buildAnnotationsJar.isPresent() && !compilerBase.contains(buildAnnotationsJar.get())) {
            compilerBase.add(buildAnnotationsJar.get());
        }
        List<Path> application = directApplicationClasspath();
        List<Path> compile = new ArrayList<>(compilerBase);
        compile.addAll(runtime);
        compile.addAll(test);
        compile.addAll(application);
        List<Path> processor = new ArrayList<>(compilerBase);
        processor.addAll(buildDependenciesForTests(build, test));
        List<Path> runtimeClasspath = new ArrayList<>(runtime);
        runtimeClasspath.addAll(test);
        runtimeClasspath.addAll(application);
        if (controlPanelRequested(invocation)) {
            String bundled = System.getProperty("pyronaut.dev.control.panel.class.path", "");
            if (!bundled.isBlank()) {
                for (String entry : bundled.split(Pattern.quote(File.pathSeparator))) {
                    if (!entry.isBlank()) {
                        runtimeClasspath.add(Path.of(entry));
                    }
                }
            }
        }
        return new DirectSourceClasspaths(
            filterDirectSourcePaths(processor, invocation, developmentMode, false),
            filterDirectSourcePaths(compile, invocation, developmentMode, false),
            filterDirectSourcePaths(runtimeClasspath, invocation, developmentMode, true)
        );
    }

    private static Optional<Path> findPyronautBuildAnnotationsJar() throws IOException {
        Path repository = Path.of(System.getProperty("user.home"), ".m2", "repository");
        if (!Files.isDirectory(repository)) {
            return Optional.empty();
        }
        try (var paths = Files.walk(repository, 8)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("micronaut-pyronaut-build-annotations-"))
                .filter(path -> path.toString().endsWith(".jar"))
                .filter(path -> !path.toString().endsWith("-sources.jar") && !path.toString().endsWith("-javadoc.jar"))
                .findFirst();
        }
    }

    static List<Path> filterDirectSourcePaths(List<Path> paths,
                                              DirectSourceInvocation invocation,
                                              boolean developmentMode,
                                              boolean filterNativeProvided) {
        Set<String> nativeArtifacts = filterNativeProvided ? nativeProvidedArtifacts() : Set.of();
        boolean excludeTestResources = !testResourcesEnabled(invocation, developmentMode);
        List<Path> filtered = new ArrayList<>();
        for (Path path : paths) {
            boolean controlPanel = controlPanelRequested(invocation)
                || "true".equalsIgnoreCase(System.getProperty("micronaut.control-panel.enabled"));
            if ((excludeTestResources && isTestResourcesJar(path))
                || (filterNativeProvided && isNativeProvidedArtifact(path, nativeArtifacts)
                && !controlPanel && !isPyronautBuildAnnotationsArtifact(path))) {
                continue;
            }
            if (!filtered.contains(path)) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    private static boolean controlPanelRequested(DirectSourceInvocation invocation) {
        return "true".equalsIgnoreCase(invocation.properties().get("micronaut.control-panel.enabled"))
            || "true".equalsIgnoreCase(System.getProperty("micronaut.control-panel.enabled"));
    }

    private static Set<String> nativeProvidedArtifacts() {
        return Set.copyOf(NativeProvidedJarResolver.providedArtifactCoordinates());
    }

    private static boolean isNativeProvidedArtifact(Path path, Set<String> nativeArtifacts) {
        String artifact = versionedJarArtifact(path.getFileName().toString());
        return artifact != null && nativeArtifacts.stream().anyMatch(coordinate -> coordinate.endsWith(":" + artifact));
    }

    private static boolean isPyronautBuildAnnotationsArtifact(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith("micronaut-pyronaut-build-annotations-") && fileName.endsWith(".jar")
            && !fileName.endsWith("-sources.jar") && !fileName.endsWith("-javadoc.jar");
    }

    private static String versionedJarArtifact(String fileName) {
        if (!fileName.endsWith(".jar")) {
            return null;
        }
        String baseName = fileName.substring(0, fileName.length() - 4);
        for (int i = 0; i < baseName.length() - 1; i++) {
            if (baseName.charAt(i) == '-' && Character.isDigit(baseName.charAt(i + 1))) {
                return baseName.substring(0, i);
            }
        }
        return null;
    }

    private static void logClasspaths(DirectSourceInvocation invocation, DirectSourceClasspaths classpaths) {
        if (!invocation.verbose()) {
            return;
        }
        logClasspath("annotation processor", classpaths.processor());
        logClasspath("compile", classpaths.compile());
        logClasspath("runtime", classpaths.runtime());
    }

    private static void logClasspath(String name, List<Path> paths) {
        System.err.println("pyronaut [" + name + "] classpath:");
        if (paths.isEmpty()) {
            System.err.println(" (empty)");
        } else {
            paths.forEach(path -> System.err.println("  " + path));
        }
    }

    private static List<Path> directCompilerClasspath(DirectSourceInvocation invocation) {
        String classpath = System.getProperty(DIRECT_COMPILER_CLASSPATH);
        if (classpath == null || classpath.isBlank()) {
            return List.of();
        }
        boolean excludeTestResources = !testResourcesEnabled(invocation, isDevelopmentMode());
        return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
            .filter(value -> !value.isBlank())
            .map(Path::of)
            .filter(path -> !excludeTestResources || !isTestResourcesJar(path))
            .toList();
    }

    private static List<Path> directApplicationClasspath() {
        String classpath = System.getProperty(DIRECT_APPLICATION_CLASSPATH);
        if (classpath == null || classpath.isBlank()) {
            return List.of();
        }
        return Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
            .filter(value -> !value.isBlank())
            .map(Path::of)
            .map(path -> path.toAbsolutePath().normalize())
            .toList();
    }

    private static boolean testResourcesDisabled(DirectSourceInvocation invocation) {
        return invocation.properties().entrySet().stream()
            .filter(entry -> entry.getKey().equals(MICRONAUT_TEST_RESOURCES_ENABLED)
                || entry.getKey().equals(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY))
            .anyMatch(entry -> "false".equalsIgnoreCase(entry.getValue()))
            || "false".equalsIgnoreCase(System.getProperty(MICRONAUT_TEST_RESOURCES_ENABLED))
            || "false".equalsIgnoreCase(System.getProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY));
    }

    private static boolean testResourcesEnabled(DirectSourceInvocation invocation, boolean developmentMode) {
        if (invocation.setup() == null) {
            return DirectSourceDeclarationState.testResourcesRequired() && !testResourcesDisabled(invocation);
        }
        return (invocation.test() || developmentMode) && !testResourcesDisabled(invocation);
    }

    private static void stageSetup(DirectSourceInvocation invocation, Path stagingRoot) throws IOException {
        if (invocation.setup() != null) {
            Path pyproject = stagingRoot.resolve("pyproject.toml");
            Files.copy(invocation.setup(), pyproject, StandardCopyOption.REPLACE_EXISTING);
        } else if (controlPanelRequested(invocation)) {
            Files.writeString(stagingRoot.resolve("pyproject.toml"), """
                [project]
                name = "pyronaut-direct-source"
                version = "0.0.0"

                [tool.pyronaut.toolchain]
                type = "native"

                [tool.pyronaut.control-panel]
                enabled = true
                path = "/control-panel"
                """, StandardCharsets.UTF_8);
        }
    }

    private static Path projectDirectory(Path stagingRoot) {
        String configured = System.getProperty(PROJECT_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return stagingRoot;
        }
        Path project = Path.of(configured).toAbsolutePath().normalize();
        return Files.isRegularFile(project.resolve("pyproject.toml")) || ExternalProjectLayout.isExternal(project) ? project : stagingRoot;
    }

    static Path projectCacheDirectory(DirectSourceInvocation invocation, Path stagingRoot) {
        if (invocation.setup() == null) {
            String configured = System.getProperty(PROJECT_DIR_PROPERTY);
            if (configured != null && !configured.isBlank()) {
                Path project = Path.of(configured).toAbsolutePath().normalize();
                return ExternalProjectLayout.isExternal(project) ? ExternalProjectLayout.outputDirectory(project) : project.resolve(DEFAULT_PYRONAUT_DIR);
            }
            Path source = invocation.sources().getFirst().toAbsolutePath().normalize();
            Path sourceDirectory = Files.isDirectory(source) ? source : source.getParent();
            return sourceDirectory.resolve(DEFAULT_PYRONAUT_DIR);
        }
        Path project = projectDirectory(stagingRoot);
        return ExternalProjectLayout.isExternal(project)
            ? ExternalProjectLayout.outputDirectory(project)
            : project.resolve(DEFAULT_PYRONAUT_DIR);
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

    private static void resetStagedSources(Path targetDir) throws IOException {
        if (Files.isDirectory(targetDir)) {
            try (var paths = Files.walk(targetDir)) {
                for (Path path : paths.sorted(Collections.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(targetDir);
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
            for (Path path : stream.filter(candidate -> !isManagedSourcePath(source, candidate)).toList()) {
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

    private static boolean isManagedSourcePath(Path sourceRoot, Path path) {
        Path relative = sourceRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
        for (Path element : relative) {
            if (DEFAULT_PYRONAUT_DIR.equals(element.toString())) {
                return true;
            }
        }
        return false;
    }

    private record DirectPythonContextState(boolean initialized, ClassLoader classLoader, boolean reuse) {
    }

    private static final class DeferredGeneratedClassLoader extends ClassLoader {
        private volatile ClassLoader generatedClassLoader;

        private DeferredGeneratedClassLoader(ClassLoader parent) {
            super(parent);
        }

        private ClassLoader generatedClassLoader() {
            return generatedClassLoader;
        }

        private void generatedClassLoader(ClassLoader generatedClassLoader) {
            this.generatedClassLoader = generatedClassLoader;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            ClassLoader generated = generatedClassLoader;
            if (generated == null) {
                throw new ClassNotFoundException(name);
            }
            return generated.loadClass(name);
        }

        @Override
        public URL getResource(String name) {
            ClassLoader generated = generatedClassLoader;
            return generated == null ? super.getResource(name) : generated.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            ClassLoader generated = generatedClassLoader;
            return generated == null ? super.getResources(name) : generated.getResources(name);
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

    enum SourceType {
        JAVA,
        PYTHON
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
                case PROCESS -> {
                    String previous = System.getProperty(PROCESSOR_DAEMON_COMMAND_PREFIX);
                    System.setProperty(PROCESSOR_DAEMON_COMMAND_PREFIX, "process");
                    try {
                        yield new CommandLine(new PyronautProcessorMain()).execute(args);
                    } finally {
                        if (previous == null) {
                            System.clearProperty(PROCESSOR_DAEMON_COMMAND_PREFIX);
                        } else {
                            System.setProperty(PROCESSOR_DAEMON_COMMAND_PREFIX, previous);
                        }
                    }
                }
                case RUN -> {
                    PyronautLauncherLogging.initializeApplicationDefaults(false);
                    yield new CommandLine(new PyronautDevRun()).execute(args);
                }
                case TEST -> {
                    PyronautLauncherLogging.initializeApplicationDefaults(false);
                    yield new CommandLine(new PyronautTestMain()).execute(args);
                }
                case VALIDATE_CONFIG -> new CommandLine(new PyronautValidateConfigMain()).execute(args);
                case TEST_RESOURCES_SERVER -> new CommandLine(new PyronautTestResourcesServerMain()).execute(args);
            };
        }
    }

    static final class DirectSourceLauncherClassLoader extends URLClassLoader {
        DirectSourceLauncherClassLoader(ClassLoader parent) {
            this(new URL[0], parent);
        }

        DirectSourceLauncherClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public URL getResource(String name) {
            URL resource = super.getResource(name);
            if (isFilteredResource(name, resource)) {
                return null;
            }
            return resource;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            List<URL> resources = Collections.list(super.getResources(name));
            if (isFilteredResourceName(name)) {
                resources = resources.stream()
                    .filter(resource -> !isBundledTestResourcesResource(resource))
                    .toList();
            }
            return Collections.enumeration(resources);
        }

        private static boolean isFilteredResource(String name, URL resource) {
            return isFilteredResourceName(name)
                && resource != null
                && isBundledTestResourcesResource(resource);
        }

        private static boolean isFilteredResourceName(String name) {
            return PROPERTY_SOURCE_LOADER_SERVICE.equals(name)
                || PROPERTY_EXPRESSION_RESOLVER_SERVICE.equals(name)
                || APPLICATION_CONTEXT_CONFIGURER_SERVICE.equals(name)
                || TEST_RESOURCES_RESOLVER_SERVICE.equals(name)
                || APPLICATION_VFS_FILESLIST_RESOURCE.equals(name)
                || name.startsWith(MICRONAUT_METADATA_PREFIX);
        }

        private static boolean isBundledTestResourcesResource(URL resource) {
            String resourceUrl = resource.toString();
            if (resourceUrl.contains(".jar!") && resourceUrl.contains(APPLICATION_VFS_FILESLIST_RESOURCE)) {
                return true;
            }
            return BUNDLED_TEST_RESOURCES_JARS.stream().anyMatch(resourceUrl::contains);
        }
    }

    record DirectSourceInvocation(boolean test,
                                  Path setup,
                                  Path report,
                                  List<Path> configs,
                                  List<Path> sources,
                                  List<Path> testSources,
                                  boolean testSourceSeparator,
                                  Map<String, String> properties,
                                  String verboseLogger) {
        DirectSourceInvocation {
            configs = List.copyOf(configs);
            sources = List.copyOf(sources);
            testSources = List.copyOf(testSources);
            properties = Map.copyOf(properties);
        }

        boolean verbose() {
            return verboseLogger != null;
        }

        DirectSourceInvocation withTestSources(List<Path> sources) {
            return new DirectSourceInvocation(test, setup, report, configs, this.sources, sources, testSourceSeparator, properties, verboseLogger);
        }
    }

    private record DirectSourceClasspaths(List<Path> processor, List<Path> compile, List<Path> runtime) {
    }

}
