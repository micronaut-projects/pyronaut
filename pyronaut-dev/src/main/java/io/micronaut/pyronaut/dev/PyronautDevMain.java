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
import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.pyronaut.install.PyronautInstallMain;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderApplicationContextConfigurers;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanDefinitionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.dev.runtime.PyronautDevTestResourcesPropertySourceLoader;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.pyronaut.processor.PyronautProcessorMain;
import io.micronaut.pyronaut.run.PyronautRunMain;
import io.micronaut.pyronaut.test.PyronautTestMain;
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
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.junit.platform.engine.TestExecutionResult;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
    private static final String MICRONAUT_TEST_RESOURCES_ENABLED = "micronaut.test.resources.enabled";
    private static final String DIRECT_COMPILER_CLASSPATH = "pyronaut.dev.compiler.class.path";
    private static final String DIRECT_APPLICATION_CLASSPATH = "pyronaut.dev.application.class.path";
    private static final String NATIVE_PROVIDED_ARTIFACTS = "pyronaut.dev.native.provided.artifacts";
    private static final String DEFAULT_TEST_SERVER_PORT = "0";
    private static final String NETTY_NO_UNSAFE = "io.netty.noUnsafe";
    private static final String SUN_MISC_UNSAFE_MEMORY_ACCESS = "sun.misc.unsafe.memory.access";
    private static final String VERIFY_SYSTEM_RESOURCE = "pyronaut.dev.verify-system-resource";
    private static final String VERIFY_SYSTEM_CLASS = "pyronaut.dev.verify-system-class";
    private static final String VERIFY_SYSTEM_CLASS_RESOURCE = "pyronaut.dev.verify-system-class-resource";
    private static final String PROPERTY_SOURCE_LOADER_SERVICE = "META-INF/services/io.micronaut.context.env.PropertySourceLoader";
    private static final String PROPERTY_EXPRESSION_RESOLVER_SERVICE = "META-INF/services/io.micronaut.context.env.PropertyExpressionResolver";
    private static final String APPLICATION_CONTEXT_CONFIGURER_SERVICE = "META-INF/services/io.micronaut.context.ApplicationContextConfigurer";
    private static final String TEST_RESOURCES_RESOLVER_SERVICE = "META-INF/services/io.micronaut.testresources.core.TestResourcesResolver";
    private static final String APPLICATION_VFS_FILESLIST_RESOURCE = "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt";
    private static final String MICRONAUT_METADATA_PREFIX = "META-INF/micronaut/";
    private static final String TEST_RESOURCES_PACKAGE = "io.micronaut.testresources.";
    private static final String PYRONAUT_TEST_RESOURCES_PACKAGE = "io.micronaut.pyronaut.testresources.";
    private static final String MICRONAUT_PYTHON_ENABLED = "micronaut.python.enabled";
    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern JAVA_TYPE_PATTERN = Pattern.compile("\\b(?:class|interface|record|enum)\\s+([A-Za-z_][\\w]*)");
    private static final Pattern PYTHON_TYPE_PATTERN = Pattern.compile("(?m)^\\s*class\\s+([A-Za-z_][\\w]*)\\s*[:(]");
    private static final List<String> BUNDLED_TEST_RESOURCES_JARS = List.of(
        "micronaut-test-resources-",
        "micronaut-pyronaut-test-resources-server-"
    );
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
    private final boolean directTest;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    @CommandLine.Unmatched
    List<String> directArgs = new ArrayList<>();

    PyronautDevMain(DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner) {
        this(delegateInvoker, directSourceRunner, false);
    }

    private PyronautDevMain(DelegateInvoker delegateInvoker, DirectSourceRunner directSourceRunner, boolean directTest) {
        this.delegateInvoker = delegateInvoker;
        this.directSourceRunner = directSourceRunner;
        this.directTest = directTest;
    }

    static void main(String[] args) {
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
            while (input.read() != -1) {
                // Fully consume the stream to verify the resource is readable.
            }
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
                        while (input.read() != -1) {
                            // Fully consume the stream to verify the URL is readable.
                        }
                    }
                }
                try (InputStream input = loadedClass.getResourceAsStream(resource)) {
                    if (input == null) {
                        System.err.println("System class resource not found: " + className + " " + resource);
                        return PRECONDITION_FAILED;
                    }
                    while (input.read() != -1) {
                        // Fully consume the stream to verify the resource is readable.
                    }
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
            commandSpec.commandLine().usage(System.err);
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

        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if ("--".equals(token)) {
                afterSeparator = true;
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
                        report = i + 1 < args.size()
                                && !args.get(i + 1).startsWith("-")
                                && !isSourceSelector(args.get(i + 1))
                            ? Path.of(args.get(++i)) : Path.of("__pyronaut__", "reports", "tests");
                        continue;
                    }
                    case "--disable-test-resources" -> {
                        properties.put(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY, "false");
                        properties.put(MICRONAUT_TEST_RESOURCES_ENABLED, "false");
                        continue;
                    }
                    case "--verbose" -> {
                        verboseLogger = "";
                        if (token.startsWith("--verbose=")) {
                            verboseLogger = token.substring("--verbose=".length());
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
        return new DirectSourceInvocation(test, setup, report, List.copyOf(configs), List.copyOf(sources), List.copyOf(testSources), Map.copyOf(properties), verboseLogger);
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
            if ("--project-dir".equals(argument)) {
                return false;
            }
            if ("--".equals(argument) || argument.endsWith(".java") || argument.endsWith(".py")) {
                return true;
            }
        }
        return false;
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

    private Integer runDirectSources(DirectSourceInvocation invocation) {
        Path stagingRoot = null;
        Map<String, String> previousProperties = new LinkedHashMap<>();
        try {
            SourceType sourceType = sourceType(invocation.sources());
            if (invocation.test()) {
                if (invocation.testSources().isEmpty()) {
                    throw new IllegalArgumentException("Direct tests require test sources after '--'");
                }
                SourceType testSourceType = sourceType(invocation.testSources());
                if (sourceType != testSourceType) {
                    throw new IllegalArgumentException("Application and test sources must use the same language");
                }
            }
            stagingRoot = Files.createTempDirectory("pyronaut-dev-source-");
            stageSetup(invocation, stagingRoot);
            stageSources(invocation.sources(), stagingRoot.resolve("src"));
            if (!invocation.testSources().isEmpty()) {
                stageSources(invocation.testSources(), stagingRoot.resolve("src"));
            }
            stageConfig(invocation.configs(), stagingRoot.resolve("config"));
            applyProperties(invocation.properties(), previousProperties);
            if (invocation.setup() == null) {
                previousProperties.put(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY,
                    System.getProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY));
                System.setProperty(PyronautDevTestResourcesPropertySourceLoader.ENABLED_PROPERTY, "false");
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
            return directSourceRunner.run(invocation, stagingRoot);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (Exception e) {
            System.err.println("Direct source launch failed: " + e.getMessage());
            return INTERNAL_ERROR;
        } finally {
            restoreProperties(previousProperties, previousProperties.keySet());
            if (stagingRoot != null) {
                deleteDirectoryBestEffort(stagingRoot);
            }
        }
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
        try (URLClassLoader runtimeClassLoader = new URLClassLoader(runtimeUrls.toArray(URL[]::new), launcherClassLoader)) {
            long now = System.currentTimeMillis();
            PyronautCompiler.Builder builder = PyronautCompiler.builder()
                .annotationProcessorPath(toFiles(classpaths.processor()))
                .classpath(toFiles(classpaths.compile()))
                .runtimeClasspath(toFiles(classpaths.runtime()))
                .parentClassLoader(runtimeClassLoader);
            configureDirectSource(builder, invocation, stagingRoot);
            ClassLoader applicationClassLoader = builder.build().buildClassLoader();
            if (invocation.verbose()) {
                System.out.println("Processing Time: " + (System.currentTimeMillis() - now) + "ms");
            }
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            PyronautDevLogging.initializeApplicationLogging(invocation.verboseLogger());
            now = System.currentTimeMillis();
            ApplicationContextBuilder micronaut = Micronaut.build(new String[0])
                .classLoader(applicationClassLoader)
                .beanResolutionTrace(invocation.verbose() ? BeanResolutionTraceMode.STANDARD_OUT : BeanResolutionTraceMode.NONE)
                .beanDefinitionsProvider(directSourceBeanDefinitionsProvider(invocation))
                .deducePackage(false)
                .deduceCloudEnvironment(false)
                .deduceEnvironment(false);
            ContextClassLoaderApplicationContextConfigurers.configure(micronaut, applicationClassLoader);
            List<String> configLocations = toConfigLocations(invocation.configs());
            if (!configLocations.isEmpty()) {
                micronaut.overrideConfigLocations(configLocations.toArray(String[]::new));
            }
            micronaut.start();
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
        try (URLClassLoader runtimeClassLoader = new URLClassLoader(runtimeUrls.toArray(URL[]::new), launcherClassLoader)) {
            long now = System.currentTimeMillis();
            PyronautCompiler.Builder builder = PyronautCompiler.builder()
                .annotationProcessorPath(toFiles(classpaths.processor()))
                .classpath(toFiles(classpaths.compile()))
                .runtimeClasspath(toFiles(classpaths.runtime()))
                .parentClassLoader(runtimeClassLoader);

            configureDirectSource(builder, invocation, stagingRoot);
            ClassLoader applicationClassLoader = builder.build().buildClassLoader();
            if (invocation.verbose()) {
                System.out.println("Processing Time: " + (System.currentTimeMillis() - now) + "ms");
            }
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            DirectSourceApplicationContextConfigurer.set(directSourceBeanDefinitionsProvider(invocation));
            defaultTestServerPort();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            if (sourceType(invocation.sources()) == SourceType.PYTHON) {
                GraalPyContextFactory.bootstrapReusableContext(applicationClassLoader, Map.of(), GraalPyContextFactory.APPLICATION_MAIN);
            }

            now = System.currentTimeMillis();
            LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
            List<String> testClassNames = testClassNames(invocation.testSources());
            if (testClassNames.isEmpty()) {
                throw new IllegalArgumentException("No JUnit test classes were found in the supplied test sources");
            }
            for (String testClassName : testClassNames) {
                Class<?> testClass = Class.forName(testClassName, true, applicationClassLoader);
                requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
            }
            LauncherDiscoveryRequest request = requestBuilder.build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            List<JUnitReportWriter.TestResult> reportResults = new ArrayList<>();
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            launcher.registerTestExecutionListeners(listener);
            launcher.registerTestExecutionListeners(new TestExecutionListener() {
                private ByteArrayOutputStream out;
                private ByteArrayOutputStream err;

                @Override
                public void executionStarted(TestIdentifier identifier) {
                    if (identifier.isTest()) {
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
                            err == null ? "" : err.toString(StandardCharsets.UTF_8)));
                    }
                }
            });
            launcher.registerTestExecutionListeners(new ConsoleTestExecutionListener(originalOut));
            launcher.execute(request);
            TestExecutionSummary summary = listener.getSummary();
            summary.printTo(new PrintWriter(System.out, true, StandardCharsets.UTF_8));
            Path reportDirectory = writeReports(invocation.report(), summary, reportResults);
            if (reportDirectory != null) {
                System.out.println("Test report: " + terminalLink(reportDirectory.resolve("index.html")));
            }
            if (invocation.verbose()) {
                System.out.println("Test Execution Time: " + (System.currentTimeMillis() - now) + "ms");
            }
            return summary.getTotalFailureCount() == 0 ? SUCCESS : TESTS_FAILED;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            if (previousBeanIntrospectionsProvider != null) {
                BeanIntrospectionProviders.set(previousBeanIntrospectionsProvider);
            }
            DirectSourceApplicationContextConfigurer.clear();
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
            restoreSystemProperty(MICRONAUT_SERVER_PORT, previousServerPortProperty);
        }
    }

    private static Path writeReports(Path report, TestExecutionSummary summary, List<JUnitReportWriter.TestResult> results) throws IOException {
        if (report == null) {
            return null;
        }
        Path directory = report.toAbsolutePath().normalize();
        JUnitReportWriter.write(directory, summary, results);
        return directory;
    }

    private static String terminalLink(Path path) {
        String uri = path.toUri().toString();
        return "\033]8;;" + uri + "\033\\Open test report\033]8;;\033\\ (" + path + ")";
    }

    private static List<String> testClassNames(List<Path> selectors) throws IOException {
        List<String> classNames = new ArrayList<>();
        for (Path file : sourceFiles(selectors)) {
            String source = Files.readString(file);
            if (file.getFileName().toString().endsWith(".java")) {
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

    private static List<Path> sourceFiles(List<Path> selectors) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path selector : selectors) {
            Path resolved = selector.toAbsolutePath().normalize();
            if (Files.isDirectory(resolved)) {
                try (var paths = Files.walk(resolved)) {
                    files.addAll(paths.filter(Files::isRegularFile)
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

    private static final class ConsoleTestExecutionListener implements TestExecutionListener {
        private final PrintStream output;

        private ConsoleTestExecutionListener(PrintStream output) {
            this.output = output;
        }

        @Override
        public void executionSkipped(TestIdentifier testIdentifier, String reason) {
            if (testIdentifier.isTest()) {
                output.println("  skipped: " + (reason == null ? "no reason supplied" : reason));
            }
        }

        @Override
        public void executionStarted(TestIdentifier testIdentifier) {
            if (testIdentifier.isTest()) {
                output.println("> " + testIdentifier.getDisplayName());
            }
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (testIdentifier.isTest()) {
                String status = testExecutionResult.getStatus().name();
                String highlightedStatus = switch (status) {
                    case "SUCCESSFUL" -> "\u001B[32mSUCCESSFUL\u001B[0m";
                    case "FAILED" -> "\u001B[31mFAILED\u001B[0m";
                    default -> status;
                };
                output.println("  " + highlightedStatus);
                testExecutionResult.getThrowable().ifPresent(throwable -> throwable.printStackTrace(output));
            }
        }
    }

    private static void configureDirectSource(PyronautCompiler.Builder builder, DirectSourceInvocation invocation, Path stagingRoot) throws IOException {
        SourceType sourceType = sourceType(invocation.sources());
        if (invocation.sources().size() == 1) {
            Path source = invocation.sources().getFirst().toAbsolutePath().normalize();
            if (Files.isDirectory(source)) {
                if (sourceType == SourceType.JAVA) {
                    builder.javaSrc(source.toString());
                } else {
                    builder.pythonSrc(source.toString());
                }
                return;
            }
        }
        if (sourceType == SourceType.JAVA) {
            builder.javaSrc(stagingRoot.resolve("src").toString());
        } else {
            builder.pythonSrc(stagingRoot.resolve("src").toString());
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
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
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

    private static ClassLoader directSourceLauncherClassLoader(DirectSourceInvocation invocation) {
        ClassLoader launcherClassLoader = PyronautDevMain.class.getClassLoader();
        if (invocation.setup() != null) {
            return launcherClassLoader;
        }
        return new DirectSourceLauncherClassLoader(launcherClassLoader);
    }

    private static BeanDefinitionsProvider directSourceBeanDefinitionsProvider(DirectSourceInvocation invocation) {
        BeanDefinitionsProvider inMemoryProvider = new InMemoryBeanDefinitionsProvider();
        BeanDefinitionsProvider contextClassLoaderProvider = new ContextClassLoaderBeanDefinitionsProvider();
        return classLoader -> {
            Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
            ClassLoader effectiveClassLoader = Thread.currentThread().getContextClassLoader();
            if (effectiveClassLoader == null) {
                effectiveClassLoader = classLoader;
            }
            List<BeanDefinitionReference<?>> generated = inMemoryProvider.provide(effectiveClassLoader);
            addBeanDefinitionReferences(references, generated, invocation);
            addBeanDefinitionReferences(references, contextClassLoaderProvider.provide(classLoader), invocation);
            return List.copyOf(references.values());
        };
    }

    private static void addBeanDefinitionReferences(Map<String, BeanDefinitionReference<?>> references,
                                                    List<BeanDefinitionReference<?>> candidates,
                                                    DirectSourceInvocation invocation) {
        for (BeanDefinitionReference<?> reference : candidates) {
            if (invocation.setup() == null && isTestResourcesBeanDefinition(reference)) {
                continue;
            }
            references.put(reference.getBeanDefinitionName(), reference);
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
        List<Path> runtime = readManifest(resolveRunManifest(pyronautDir, developmentMode));
        List<Path> test = invocation.test() ? readManifest(pyronautDir.resolve(TEST_DEPENDENCIES_MANIFEST)) : List.of();
        List<Path> compilerBase = directCompilerClasspath(invocation);
        List<Path> application = directApplicationClasspath();
        List<Path> processor = new ArrayList<>(compilerBase);
        processor.addAll(buildDependenciesForTests(build, test));
        List<Path> compile = new ArrayList<>(compilerBase);
        compile.addAll(runtime);
        compile.addAll(test);
        compile.addAll(application);
        List<Path> runtimeClasspath = new ArrayList<>(runtime);
        runtimeClasspath.addAll(test);
        runtimeClasspath.addAll(application);
        return new DirectSourceClasspaths(
            filterDirectSourcePaths(processor, invocation, developmentMode),
            filterDirectSourcePaths(compile, invocation, developmentMode),
            filterDirectSourcePaths(runtimeClasspath, invocation, developmentMode)
        );
    }

    private static List<Path> filterDirectSourcePaths(List<Path> paths,
                                                       DirectSourceInvocation invocation,
                                                       boolean developmentMode) {
        Set<String> nativeArtifacts = nativeProvidedArtifacts();
        boolean excludeTestResources = !testResourcesEnabled(invocation, developmentMode);
        List<Path> filtered = new ArrayList<>();
        for (Path path : paths) {
            if ((excludeTestResources && isTestResourcesJar(path))
                || isNativeProvidedArtifact(path, nativeArtifacts)) {
                continue;
            }
            if (!filtered.contains(path)) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    private static Set<String> nativeProvidedArtifacts() {
        String configured = System.getProperty(NATIVE_PROVIDED_ARTIFACTS, "");
        return Arrays.stream(configured.split(Pattern.quote(File.pathSeparator)))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean isNativeProvidedArtifact(Path path, Set<String> nativeArtifacts) {
        String artifact = versionedJarArtifact(path.getFileName().toString());
        return artifact != null && nativeArtifacts.stream().anyMatch(coordinate -> coordinate.endsWith(":" + artifact));
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
        return (invocation.test() || developmentMode) && !testResourcesDisabled(invocation);
    }

    private static void stageSetup(DirectSourceInvocation invocation, Path stagingRoot) throws IOException {
        if (invocation.setup() != null) {
            Path pyproject = stagingRoot.resolve("pyproject.toml");
            Files.copy(invocation.setup(), pyproject, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path projectDirectory(Path stagingRoot) {
        String configured = System.getProperty(PROJECT_DIR_PROPERTY);
        return configured == null || configured.isBlank()
            ? stagingRoot
            : Path.of(configured).toAbsolutePath().normalize();
    }

    private static Path projectCacheDirectory(DirectSourceInvocation invocation, Path stagingRoot) {
        return (invocation.setup() == null ? stagingRoot : projectDirectory(stagingRoot)).resolve(DEFAULT_PYRONAUT_DIR);
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
                case PROCESS -> new CommandLine(new PyronautProcessorMain()).execute(args);
                case RUN -> {
                    PyronautLauncherLogging.initializeApplicationDefaults(false);
                    yield new CommandLine(new PyronautRunMain()).execute(args);
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

    static final class DirectSourceLauncherClassLoader extends ClassLoader {
        DirectSourceLauncherClassLoader(ClassLoader parent) {
            super(parent);
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
    }

    private record DirectSourceClasspaths(List<Path> processor, List<Path> compile, List<Path> runtime) {
    }

}
