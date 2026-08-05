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
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.python.compiler.PythonIncrementalMode;
import picocli.CommandLine;

import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Entry point for {@code pyronaut-processor}.
 */
@CommandLine.Command(name = "pyronaut-processor", mixinStandardHelpOptions = true, description = "Process Python sources and generate Micronaut metadata")
public final class PyronautProcessorMain implements Callable<Integer> {
    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }

    private static final String DEFAULT_PYTHON_SRC = "src";
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_TARGET_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_TEST_TARGET_DIR = "__pyronaut__/test-classes";
    private static final String DEFAULT_TEST_SOURCES_DIR = "__pyronaut__/test-sources";
    private static final String DEFAULT_INCREMENTAL_DIR = "__pyronaut__/incremental";
    private static final String APPLICATION_VFS_SRC = "META-INF/GRAALPY-VFS/micronaut-application/src";
    private static final String PYTHON_ENABLED_MARKER = "META-INF/pyronaut/python-enabled";
    private static final String APPLICATION_CONTEXT_CONFIGURER_SERVICE = "META-INF/services/io.micronaut.context.ApplicationContextConfigurer";
    private static final String EXTERNAL_CONTEXT_CONFIGURER_CLASS = "io.micronaut.pyronaut.generated.PyronautPythonContextConfigurer";
    private static final String DEFAULT_JAVA_SRC = "src-java";
    private static final String DEFAULT_TEST_PYTHON_SRC = "tests";
    private static final String DEFAULT_TEST_JAVA_SRC = "test-java";
    private static final String VERBOSE_HINT = "Re-run with --verbose for full diagnostics.";
    private static final String NATIVE_PROVIDED_ARTIFACTS = "pyronaut.dev.native.provided.artifacts";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory containing pyproject.toml")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--python-src", defaultValue = DEFAULT_PYTHON_SRC, description = "Python source directory")
    Path pythonSrc = Path.of(DEFAULT_PYTHON_SRC);

    @CommandLine.Option(names = "--target-dir", defaultValue = DEFAULT_TARGET_DIR, description = "Target output directory")
    Path targetDir = Path.of(DEFAULT_TARGET_DIR);

    @CommandLine.Option(names = "--java-src", defaultValue = DEFAULT_JAVA_SRC, description = "Java source directory")
    Path javaSrc = Path.of(DEFAULT_JAVA_SRC);

    @CommandLine.Option(names = "--test-python-src", defaultValue = DEFAULT_TEST_PYTHON_SRC, description = "Test Python source directory")
    Path testPythonSrc = Path.of(DEFAULT_TEST_PYTHON_SRC);

    @CommandLine.Option(names = "--test-java-src", defaultValue = DEFAULT_TEST_JAVA_SRC, description = "Test Java source directory")
    Path testJavaSrc = Path.of(DEFAULT_TEST_JAVA_SRC);

    @CommandLine.Option(names = "--test-target-dir", defaultValue = DEFAULT_TEST_TARGET_DIR, description = "Test target output directory")
    Path testTargetDir = Path.of(DEFAULT_TEST_TARGET_DIR);

    @CommandLine.Option(names = "--annotation-processor-path", split = "${sys:path.separator}", description = "Override annotation processor classpath")
    List<Path> annotationProcessorPath;

    @CommandLine.Option(names = "--classpath", split = "${sys:path.separator}", description = "Override runtime classpath")
    List<Path> classpath;

    @CommandLine.Option(names = "--test-classpath", split = "${sys:path.separator}", description = "Override test classpath")
    List<Path> testClasspath;

    @CommandLine.Option(names = "--option", description = "Additional compiler option")
    List<String> options = List.of();

    @CommandLine.Option(names = "--progress", defaultValue = "auto", description = "Progress output mode: auto|on|off")
    String progress = "auto";

    @CommandLine.Option(names = "--no-cache", description = "Bypass processor source cache reads/writes")
    boolean noCache;

    @CommandLine.Option(
        names = "--incremental",
        negatable = true,
        description = "Enable incremental Python and Java compilation"
    )
    Boolean incremental;

    @CommandLine.Option(
        names = "--daemon",
        negatable = true,
        description = "Use the persistent compiler daemon"
    )
    Boolean daemon;

    @CommandLine.Option(names = "--daemon-server", hidden = true)
    Path daemonServerDirectory;

    @CommandLine.Option(names = "--daemon-token", hidden = true)
    String daemonToken;

    @CommandLine.Option(names = "--pass", defaultValue = "all", description = "Processing pass to run: all|main|test")
    String pass = "all";

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Verbose output with stacktraces on failures")
    boolean verbose;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    private final PyprojectModelReader modelReader;
    private final PyronautCompilerExecutor compilerExecutor;
    private final boolean daemonRequest;

    public PyronautProcessorMain() {
        this(new PyprojectModelReader(), new PyronautCompilerExecutor.Default(), false);
    }

    PyronautProcessorMain(PyprojectModelReader modelReader, PyronautCompilerExecutor compilerExecutor) {
        this(modelReader, compilerExecutor, false);
    }

    PyronautProcessorMain(PyprojectModelReader modelReader,
                          PyronautCompilerExecutor compilerExecutor,
                          boolean daemonRequest) {
        this.modelReader = modelReader;
        this.compilerExecutor = compilerExecutor;
        this.daemonRequest = daemonRequest;
    }

    @Override
    public Integer call() {
        if (daemonServerDirectory != null) {
            return CompilerDaemon.runServer(daemonServerDirectory, daemonToken);
        }
        if (System.getProperty("java.home") == null) {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome == null) {
                System.err.println("Please specify JAVA_HOME environment variable");
                return PyronautProcessorExitCode.PRECONDITION_FAILED.code();
            }
            System.setProperty("java.home", javaHome);
        }
        Path root = projectDir.toAbsolutePath().normalize();
        Path mergedTestRoot = null;
        Path mergedExternalMainRoot = null;
        Path mergedExternalTestRoot = null;
        try {
            ExternalProjectLayout externalLayout = ExternalProjectLayout.isExternal(root)
                ? ExternalProjectLayout.read(root) : null;
            PyprojectModel model = externalLayout == null ? modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME)) : null;
            boolean daemonEnabled = daemon != null
                ? daemon
                : model != null && Boolean.TRUE.equals(model.pyronaut().processor().daemon());
            CommandLine.ParseResult parseResult = commandSpec == null
                ? null
                : commandSpec.commandLine().getParseResult();
            if (daemonEnabled && !daemonRequest && parseResult != null) {
                try {
                    return CompilerDaemon.execute(
                        root,
                        parseResult.originalArgs()
                    );
                } catch (CompilerDaemon.UnavailableException e) {
                    System.err.println("Compiler daemon unavailable; processing in the current process: " + e.getMessage());
                }
            }
            ProcessorProgressReporter.ProgressMode.fromCliValue(progress);
            ProcessingPass selectedPass = ProcessingPass.fromCliValue(pass);
            List<String> mainOptions = ProcessorOptions.resolve(root, model, options, false);
            List<String> testOptions = ProcessorOptions.resolve(root, model, options, true);
            List<Path> pyronautProcessorSupport = externalProcessorSupportClasspath();

            List<Path> effectiveProcessorPath = annotationProcessorPath == null || annotationProcessorPath.isEmpty()
                ? externalLayout != null ? (externalLayout.annotationProcessorClasspath().isEmpty() ? externalLayout.buildClasspath() : externalLayout.annotationProcessorClasspath()) : ClasspathManifestReader.read(
                root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-build-dependencies"),
                "Missing build scope cache. Run pyronaut-install first"
                )
                : annotationProcessorPath;
            if (externalLayout != null) {
                effectiveProcessorPath = appendDistinct(effectiveProcessorPath, pyronautProcessorSupport);
            }
            effectiveProcessorPath = filterNativeProvidedArtifacts(effectiveProcessorPath);

            Path resolvedMainPythonSrc = externalLayout == null
                ? resolveConfiguredPath(root, pythonSrc, DEFAULT_PYTHON_SRC, model.pyronaut().sources().python(), "--python-src")
                : root.resolve("__pyronaut__/external-python");
            if (externalLayout != null) {
                prepareExternalMergedSourceRoot(root, "external-python");
                mergePythonSourceTrees(List.of(root.resolve("src/main/python")), externalLayout.mainJavaSources(), resolvedMainPythonSrc);
            }
            Path resolvedMainJavaSrc = externalLayout == null
                ? resolveConfiguredPath(root, javaSrc, DEFAULT_JAVA_SRC, model.pyronaut().sources().java(), "--java-src")
                : firstOrEmpty(root, externalLayout.mainJavaSources());
            if (externalLayout != null) {
                mergedExternalMainRoot = prepareExternalMergedSourceRoot(root, "external-main-sources");
                resolvedMainJavaSrc = mergedExternalMainRoot;
                for (Path source : externalLayout.mainJavaSources()) {
                    mergeSourceTrees(source, mergedExternalMainRoot);
                }
                writeExternalContextConfigurer(resolvedMainJavaSrc);
            }
            Path resolvedMainTargetDir = root.resolve(targetDir).normalize();

            Path resolvedTestPythonSrc = externalLayout == null
                ? resolveConfiguredPath(root, testPythonSrc, DEFAULT_TEST_PYTHON_SRC, model.pyronaut().sources().pythonTest(), "--test-python-src")
                : root.resolve("__pyronaut__/external-test-python");
            if (externalLayout != null) {
                prepareExternalMergedSourceRoot(root, "external-test-python");
                mergePythonSourceTrees(List.of(root.resolve("src/test/python")), externalLayout.testJavaSources(), resolvedTestPythonSrc);
            }
            Path resolvedTestJavaSrc = externalLayout == null
                ? resolveConfiguredPath(root, testJavaSrc, DEFAULT_TEST_JAVA_SRC, model.pyronaut().sources().javaTest(), "--test-java-src")
                : firstOrEmpty(root, externalLayout.testJavaSources());
            if (externalLayout != null) {
                mergedExternalTestRoot = prepareExternalMergedSourceRoot(root, "external-test-sources");
                resolvedTestJavaSrc = mergedExternalTestRoot;
                for (Path source : externalLayout.testJavaSources()) {
                    mergeSourceTrees(source, mergedExternalTestRoot);
                }
            }
            Path resolvedTestTargetDir = root.resolve(testTargetDir).normalize();
            Path resolvedTestSourcesDir = root.resolve(DEFAULT_TEST_SOURCES_DIR).normalize();
            Path resolvedCacheDir = root.resolve(DEFAULT_PYRONAUT_DIR).normalize();
            boolean compilePythonBytecode = model != null
                && Boolean.TRUE.equals(model.pyronaut().build().pythonBytecodeEnabled());
            boolean incrementalCompilation = !noCache && (incremental != null
                ? incremental
                : model != null && Boolean.TRUE.equals(model.pyronaut().processor().incremental()));
            String configuredPythonIncrementalMode = model == null
                ? "conservative"
                : model.pyronaut().processor().pythonIncrementalMode();
            PythonIncrementalMode pythonIncrementalMode = PythonIncrementalMode.valueOf(
                configuredPythonIncrementalMode.toUpperCase(Locale.ROOT)
            );
            Path mainIncrementalCache = root.resolve(DEFAULT_INCREMENTAL_DIR).resolve("main").normalize();
            Path testIncrementalCache = root.resolve(DEFAULT_INCREMENTAL_DIR).resolve("test").normalize();

            String mainStatus;
            String testStatus;
            try (ProcessorProgressReporter progressReporter = ProcessorProgressReporter.create(progress)) {
                if (selectedPass.includesMain()) {
                    List<Path> effectiveClasspath = classpath == null || classpath.isEmpty()
                        ? externalLayout != null ? externalLayout.runtimeClasspath() : ClasspathManifestReader.read(
                        root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-runtime-dependencies"),
                        "Missing runtime scope cache. Run pyronaut-install first"
                        )
                        : classpath;
                    if (externalLayout != null) {
                        effectiveClasspath = appendDistinct(effectiveClasspath, pyronautProcessorSupport);
                    }

                    long mainSourceCount = ProcessorSourceCache.countSources(resolvedMainPythonSrc, ".py")
                        + ProcessorSourceCache.countSources(resolvedMainJavaSrc, ".java");
                    progressReporter.startPass("main", mainSourceCount);

                    String mainFingerprint = noCache ? null : ProcessorSourceCache.fingerprint(
                        resolvedMainPythonSrc,
                        resolvedMainJavaSrc,
                        effectiveProcessorPath,
                        effectiveClasspath,
                        compilePythonBytecode,
                        incrementalCompilation,
                        configuredPythonIncrementalMode,
                        mainOptions,
                        resolvedCacheDir.resolve("processor-main.inputs")
                    );
                    if (!noCache
                        && ProcessorSourceCache.cacheHit(
                            resolvedCacheDir,
                            ProcessorSourceCache.MAIN_HASH_FILE,
                            mainFingerprint,
                            resolvedMainTargetDir,
                            incrementalCompilation
                        )) {
                        progressReporter.cacheHit("main", mainSourceCount);
                        mainStatus = "cache hit";
                    } else {
                        if (noCache) {
                            progressReporter.cacheBypass("main", mainSourceCount);
                        }
                        ProcessorSourceCache.invalidate(
                            resolvedCacheDir,
                            ProcessorSourceCache.MAIN_HASH_FILE
                        );
                        if (!incrementalCompilation) {
                            prepareGeneratedOutputDirectory(resolvedMainTargetDir);
                            deleteTree(mainIncrementalCache);
                        }
                        compilerExecutor.compile(new PyronautCompilerExecutor.CompileRequest(
                            resolvedMainPythonSrc,
                            resolvedMainJavaSrc,
                            resolvedMainTargetDir,
                            effectiveProcessorPath,
                            effectiveClasspath,
                            compilePythonBytecode,
                            incrementalCompilation,
                            pythonIncrementalMode,
                            mainIncrementalCache,
                            mainOptions
                        ));
                        if (!noCache) {
                            ProcessorSourceCache.writeHash(
                                resolvedCacheDir,
                                ProcessorSourceCache.MAIN_HASH_FILE,
                                mainFingerprint,
                                incrementalCompilation ? resolvedMainTargetDir : null
                            );
                        }
                        progressReporter.finishPass("main", mainSourceCount);
                        mainStatus = "processed";
                    }
                    if (externalLayout != null) {
                        writeExternalRuntimeMetadata(resolvedMainTargetDir, ProcessorSourceCache.countSources(resolvedMainPythonSrc, ".py") > 0);
                    }
                } else {
                    mainStatus = "skipped";
                }

                if (selectedPass.includesTest()) {
                    List<Path> effectiveTestClasspath = testClasspath == null || testClasspath.isEmpty()
                        ? externalLayout != null ? externalTestClasspath(externalLayout, resolvedMainTargetDir) : ClasspathManifestReader.read(
                        root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-test-dependencies"),
                        "Missing test scope cache. Run pyronaut-install first"
                    )
                        : testClasspath;
                    if (externalLayout != null) {
                        // Test sources can contain the generated Pyronaut
                        // application entry point too. Keep the Python
                        // annotation types compiler-only, just as for the
                        // main pass, without persisting them in the runtime
                        // test classpath.
                        effectiveTestClasspath = appendDistinct(effectiveTestClasspath, pyronautProcessorSupport);
                    }

                    mergedTestRoot = externalLayout == null
                        ? prepareExternalMergedSourceRoot(root, "test-merged-sources")
                        : prepareExternalMergedSourceRoot(root, "external-test-merged-sources");
                    Path mergedTestPythonSrc = mergedTestRoot.resolve("python");
                    Path mergedTestJavaSrc = mergedTestRoot.resolve("java");
                    mergeSourceTrees(resolvedMainPythonSrc, resolvedTestPythonSrc, mergedTestPythonSrc);
                    if (externalLayout == null) {
                        mergeSourceTrees(resolvedMainJavaSrc, resolvedTestJavaSrc, mergedTestJavaSrc);
                    } else {
                        mergeSourceTrees(resolvedTestJavaSrc, mergedTestJavaSrc);
                        writeExternalContextConfigurer(mergedTestJavaSrc);
                    }

                    long testSourceCount = ProcessorSourceCache.countSources(mergedTestPythonSrc, ".py")
                        + ProcessorSourceCache.countSources(mergedTestJavaSrc, ".java");
                    progressReporter.startPass("test", testSourceCount);
                    if (testSourceCount == 0L) {
                        ProcessorSourceCache.invalidate(
                            resolvedCacheDir,
                            ProcessorSourceCache.TEST_HASH_FILE
                        );
                        prepareGeneratedOutputDirectory(resolvedTestTargetDir);
                        deleteTree(testIncrementalCache);
                        syncProcessedTestSources(resolvedTestTargetDir, resolvedTestSourcesDir, resolvedTestPythonSrc);
                        progressReporter.noSources("test");
                        testStatus = "no sources";
                    } else {
                        String testFingerprint = noCache ? null : ProcessorSourceCache.fingerprint(
                            mergedTestPythonSrc,
                            mergedTestJavaSrc,
                            effectiveProcessorPath,
                            effectiveTestClasspath,
                            compilePythonBytecode,
                            incrementalCompilation,
                            configuredPythonIncrementalMode,
                            testOptions,
                            resolvedCacheDir.resolve("processor-test.inputs")
                        );
                        if (!noCache
                            && ProcessorSourceCache.cacheHit(
                                resolvedCacheDir,
                                ProcessorSourceCache.TEST_HASH_FILE,
                                testFingerprint,
                                resolvedTestTargetDir,
                                incrementalCompilation
                            )) {
                            syncProcessedTestSources(resolvedTestTargetDir, resolvedTestSourcesDir, resolvedTestPythonSrc);
                            progressReporter.cacheHit("test", testSourceCount);
                            testStatus = "cache hit";
                        } else {
                            if (noCache) {
                                progressReporter.cacheBypass("test", testSourceCount);
                            }
                            ProcessorSourceCache.invalidate(
                                resolvedCacheDir,
                                ProcessorSourceCache.TEST_HASH_FILE
                            );
                            if (!incrementalCompilation) {
                                prepareGeneratedOutputDirectory(resolvedTestTargetDir);
                                deleteTree(testIncrementalCache);
                            }
                            compilerExecutor.compile(new PyronautCompilerExecutor.CompileRequest(
                                mergedTestPythonSrc,
                                mergedTestJavaSrc,
                                resolvedTestTargetDir,
                                effectiveProcessorPath,
                                effectiveTestClasspath,
                                compilePythonBytecode,
                                incrementalCompilation,
                                pythonIncrementalMode,
                                testIncrementalCache,
                                testOptions
                            ));
                            if (!noCache) {
                                ProcessorSourceCache.writeHash(
                                    resolvedCacheDir,
                                    ProcessorSourceCache.TEST_HASH_FILE,
                                    testFingerprint,
                                    incrementalCompilation ? resolvedTestTargetDir : null
                                );
                            }
                            syncProcessedTestSources(resolvedTestTargetDir, resolvedTestSourcesDir, resolvedTestPythonSrc);
                            progressReporter.finishPass("test", testSourceCount);
                            testStatus = "processed";
                        }
                    }
                    if (externalLayout != null) {
                        writeExternalRuntimeMetadata(resolvedTestTargetDir, ProcessorSourceCache.countSources(mergedTestPythonSrc, ".py") > 0);
                    }
                } else {
                    testStatus = "skipped";
                }

                progressReporter.complete(mainStatus, testStatus);
            }
            return PyronautProcessorExitCode.SUCCESS.code();
        } catch (PyronautProcessorException e) {
            System.err.println(e.getMessage());
            return PyronautProcessorExitCode.PRECONDITION_FAILED.code();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return PyronautProcessorExitCode.USAGE_ERROR.code();
        } catch (RuntimeException e) {
            System.err.println("Processing failed: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            } else {
                System.err.println(VERBOSE_HINT);
            }
            return PyronautProcessorExitCode.PROCESSING_ERROR.code();
        } catch (Exception e) {
            System.err.println("Unexpected processor failure: " + e.getMessage());
            if (verbose) {
                e.printStackTrace(System.err);
            } else {
                System.err.println(VERBOSE_HINT);
            }
            return PyronautProcessorExitCode.INTERNAL_ERROR.code();
        } finally {
            cleanupTemporaryDirectory(mergedTestRoot);
        }
    }

    static List<Path> filterNativeProvidedArtifacts(List<Path> paths) {
        String configured = System.getProperty(NATIVE_PROVIDED_ARTIFACTS, "");
        if (configured.isBlank()) {
            return paths;
        }
        Set<String> artifactIds = new HashSet<>();
        Arrays.stream(configured.split(Pattern.quote(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(value -> value.substring(value.lastIndexOf(':') + 1))
            .forEach(artifactIds::add);
        if (artifactIds.isEmpty()) {
            return paths;
        }
        return paths.stream()
            .filter(path -> artifactIds.stream().noneMatch(artifactId -> isArtifact(path, artifactId)))
            .toList();
    }

    private static boolean isArtifact(Path path, String artifactId) {
        // API jars are compile-time contracts for generated sources. Even if
        // a native launcher embeds an implementation, removing an API jar can
        // make ordinary source imports (for example jakarta.inject) vanish.
        if (artifactId.endsWith("-api")) {
            return false;
        }
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.startsWith(artifactId + "-") && name.endsWith(".jar");
    }

    public static void main(String[] args) {
        PyronautLauncherLogging.initialize();
        int exitCode = new CommandLine(new PyronautProcessorMain()).execute(args);
        System.exit(exitCode);
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

    private static Path firstOrEmpty(Path root, List<Path> paths) {
        return paths == null || paths.isEmpty() ? root.resolve("__pyronaut__/external-java") : paths.get(0);
    }

    private static List<Path> externalTestClasspath(ExternalProjectLayout layout, Path mainClasses) {
        List<Path> classpath = new ArrayList<>(layout.testClasspath());
        if (Files.exists(mainClasses)) {
            classpath.add(mainClasses);
        }
        return classpath;
    }

    private static List<Path> externalProcessorSupportClasspath() {
        String classpath = System.getProperty("pyronaut.dev.compiler.class.path", "")
            + java.io.File.pathSeparator
            + System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .map(Path::of)
            .filter(path -> {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                return name.startsWith("micronaut-context-python-")
                    || name.startsWith("micronaut-inject-python-")
                    || name.startsWith("python-language-")
                    || name.startsWith("micronaut-python-");
            })
            .toList();
    }

    private static List<Path> appendDistinct(List<Path> first, List<Path> second) {
        List<Path> result = new ArrayList<>(first);
        for (Path path : second) {
            if (!result.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private static Path prepareExternalMergedSourceRoot(Path root, String name) {
        Path directory = root.resolve(DEFAULT_PYRONAUT_DIR).resolve(name).normalize();
        deleteTree(directory);
        try {
            Files.createDirectories(directory);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to create external source directory: " + directory, e);
        }
        return directory;
    }

    private boolean isExplicitlyConfigured(String optionName) {
        return commandSpec != null
            && commandSpec.commandLine() != null
            && commandSpec.commandLine().getParseResult() != null
            && commandSpec.commandLine().getParseResult().hasMatchedOption(optionName);
    }

    private static void mergeSourceTrees(Path primarySource, Path overlaySource, Path targetDirectory) {
        copyTree(primarySource, targetDirectory);
        copyTree(overlaySource, targetDirectory);
    }

    private static void mergeSourceTrees(Path source, Path targetDirectory) {
        copyTree(source, targetDirectory);
    }

    private static void mergePythonSourceTrees(List<Path> conventionalSources, List<Path> externalSources, Path targetDirectory) {
        for (Path source : conventionalSources) {
            copyPythonFiles(source, targetDirectory);
        }
        for (Path source : externalSources) {
            copyPythonFiles(source, targetDirectory);
        }
    }

    private static void writeExternalContextConfigurer(Path javaSourceRoot) {
        Path sourceFile = javaSourceRoot.resolve("io/micronaut/pyronaut/generated/PyronautPythonContextConfigurer.java");
        try {
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, """
                package io.micronaut.pyronaut.generated;

                import io.micronaut.context.ApplicationContextBuilder;
                import io.micronaut.context.ApplicationContextConfigurer;
                import io.micronaut.context.annotation.ContextConfigurer;
                import io.micronaut.context.env.PropertySource;
                import io.micronaut.core.order.Ordered;
                import java.util.Map;

                @ContextConfigurer
                public final class PyronautPythonContextConfigurer implements ApplicationContextConfigurer {
                    private static final String PYTHON_ENABLED = \"micronaut.python.enabled\";
                    private static final String PYTHON_MARKER = \"META-INF/pyronaut/python-enabled\";

                    public PyronautPythonContextConfigurer() {
                    }

                    @Override
                    public void configure(ApplicationContextBuilder builder) {
                        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                        if (classLoader != null && classLoader.getResource(PYTHON_MARKER) != null) {
                            return;
                        }
                        if (System.getProperty(PYTHON_ENABLED) != null || System.getenv(\"MICRONAUT_PYTHON_ENABLED\") != null) {
                            return;
                        }
                        builder.propertySources(PropertySource.of(\"pyronaut-python-default\", Map.of(PYTHON_ENABLED, false), Ordered.HIGHEST_PRECEDENCE));
                    }
                }
                """);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to generate external Python context configurer", e);
        }
    }

    private static void writeExternalRuntimeMetadata(Path targetDirectory, boolean pythonEnabled) {
        try {
            Path service = targetDirectory.resolve(APPLICATION_CONTEXT_CONFIGURER_SERVICE);
            Files.createDirectories(service.getParent());
            Files.writeString(service, EXTERNAL_CONTEXT_CONFIGURER_CLASS + System.lineSeparator());
            Path marker = targetDirectory.resolve(PYTHON_ENABLED_MARKER);
            if (pythonEnabled) {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "");
            } else {
                Files.deleteIfExists(marker);
            }
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to write external Python runtime metadata", e);
        }
    }

    private static void prepareGeneratedOutputDirectory(Path targetDirectory) {
        deleteTree(targetDirectory);
        try {
            Files.createDirectories(targetDirectory);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to create generated output directory: " + targetDirectory, e);
        }
    }

    private static void syncProcessedTestSources(Path testTargetDir, Path testSourcesDir, Path originalTestSourceDir) {
        deleteTree(testSourcesDir);
        Path generatedSources = testTargetDir.resolve(APPLICATION_VFS_SRC).normalize();
        if (!Files.isDirectory(generatedSources) || !Files.isDirectory(originalTestSourceDir)) {
            try {
                Files.createDirectories(testSourcesDir);
            } catch (Exception e) {
                throw new PyronautProcessorException("Failed to create processed test sources directory: " + testSourcesDir, e);
            }
            return;
        }
        try (Stream<Path> paths = Files.walk(originalTestSourceDir)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".py"))
                .forEach(path -> {
                    Path relative = originalTestSourceDir.relativize(path);
                    Path generatedPath = generatedSources.resolve(relative).normalize();
                    if (Files.isRegularFile(generatedPath)) {
                        copyFile(generatedPath, testSourcesDir.resolve(relative));
                    }
                });
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to mirror processed test sources from: " + generatedSources, e);
        }
    }

    private static void copyTree(Path sourceDirectory, Path targetDirectory) {
        if (!Files.isDirectory(sourceDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            paths.forEach(path -> {
                try {
                    Path relative = sourceDirectory.relativize(path);
                    Path targetPath = targetDirectory.resolve(relative);
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Path parent = targetPath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        copyFile(path, targetPath);
                    }
                } catch (Exception e) {
                    throw new PyronautProcessorException("Failed to merge source tree from: " + sourceDirectory, e);
                }
            });
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed walking source tree: " + sourceDirectory, e);
        }
    }

    private static void copyPythonFiles(Path sourceDirectory, Path targetDirectory) {
        if (!Files.isDirectory(sourceDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".py"))
                .forEach(path -> {
                    Path relative = sourceDirectory.relativize(path);
                    copyFile(path, targetDirectory.resolve(relative));
                });
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to merge Python source tree from: " + sourceDirectory, e);
        }
    }

    private static void copyFile(Path source, Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed copying " + source + " to " + target, e);
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new PyronautProcessorException("Failed deleting path: " + path, e);
                }
            });
        } catch (PyronautProcessorException e) {
            throw e;
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed walking path: " + directory, e);
        }
    }

    private enum ProcessingPass {
        ALL,
        MAIN,
        TEST;

        static ProcessingPass fromCliValue(String value) {
            if (value == null || value.isBlank()) {
                return ALL;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "main" -> MAIN;
                case "test" -> TEST;
                default -> throw new IllegalArgumentException("Invalid value for --pass. Use all|main|test");
            };
        }

        boolean includesMain() {
            return this == ALL || this == MAIN;
        }

        boolean includesTest() {
            return this == ALL || this == TEST;
        }
    }

    private void cleanupTemporaryDirectory(Path temporaryDirectory) {
        if (temporaryDirectory == null || !Files.exists(temporaryDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(temporaryDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new PyronautProcessorException("Failed deleting temporary path: " + path, e);
                }
            });
        } catch (PyronautProcessorException e) {
            if (verbose) {
                e.printStackTrace(System.err);
            }
        } catch (Exception e) {
            if (verbose) {
                e.printStackTrace(System.err);
            }
        }
    }
}
