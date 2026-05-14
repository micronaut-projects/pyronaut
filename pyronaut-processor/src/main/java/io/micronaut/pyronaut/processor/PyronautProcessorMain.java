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
import picocli.CommandLine;

import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
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
    private static final String DEFAULT_JAVA_SRC = "src-java";
    private static final String DEFAULT_TEST_PYTHON_SRC = "tests";
    private static final String DEFAULT_TEST_JAVA_SRC = "test-java";
    private static final String VERBOSE_HINT = "Re-run with --verbose for full diagnostics.";

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

    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Verbose output with stacktraces on failures")
    boolean verbose;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    private final PyprojectModelReader modelReader;
    private final PyronautCompilerExecutor compilerExecutor;

    public PyronautProcessorMain() {
        this(new PyprojectModelReader(), new PyronautCompilerExecutor.Default());
    }

    PyronautProcessorMain(PyprojectModelReader modelReader, PyronautCompilerExecutor compilerExecutor) {
        this.modelReader = modelReader;
        this.compilerExecutor = compilerExecutor;
    }

    @Override
    public Integer call() {
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
        try {
            PyprojectModel model = modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME));
            ProcessorProgressReporter.ProgressMode.fromCliValue(progress);

            List<Path> effectiveProcessorPath = annotationProcessorPath == null || annotationProcessorPath.isEmpty()
                ? ClasspathManifestReader.read(
                root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-build-dependencies"),
                "Missing build scope cache. Run pyronaut-install first"
            )
                : annotationProcessorPath;

            List<Path> effectiveClasspath = classpath == null || classpath.isEmpty()
                ? ClasspathManifestReader.read(
                root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-runtime-dependencies"),
                "Missing runtime scope cache. Run pyronaut-install first"
            )
                : classpath;

            List<Path> effectiveTestClasspath = testClasspath == null || testClasspath.isEmpty()
                ? ClasspathManifestReader.read(
                root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-test-dependencies"),
                "Missing test scope cache. Run pyronaut-install first"
            )
                : testClasspath;

            Path resolvedMainPythonSrc = resolveConfiguredPath(root, pythonSrc, DEFAULT_PYTHON_SRC, model.pyronaut().sources().python(), "--python-src");
            Path resolvedMainJavaSrc = resolveConfiguredPath(root, javaSrc, DEFAULT_JAVA_SRC, model.pyronaut().sources().java(), "--java-src");
            Path resolvedMainTargetDir = root.resolve(targetDir).normalize();

            Path resolvedTestPythonSrc = resolveConfiguredPath(root, testPythonSrc, DEFAULT_TEST_PYTHON_SRC, model.pyronaut().sources().pythonTest(), "--test-python-src");
            Path resolvedTestJavaSrc = resolveConfiguredPath(root, testJavaSrc, DEFAULT_TEST_JAVA_SRC, model.pyronaut().sources().javaTest(), "--test-java-src");
            Path resolvedTestTargetDir = root.resolve(testTargetDir).normalize();
            Path resolvedCacheDir = root.resolve(DEFAULT_PYRONAUT_DIR).normalize();

            String mainStatus;
            String testStatus;
            try (ProcessorProgressReporter progressReporter = ProcessorProgressReporter.create(progress)) {
                long mainSourceCount = ProcessorSourceCache.countSources(resolvedMainPythonSrc, ".py")
                    + ProcessorSourceCache.countSources(resolvedMainJavaSrc, ".java");
                progressReporter.startPass("main", mainSourceCount);

                String mainFingerprint = ProcessorSourceCache.fingerprint(
                    resolvedMainPythonSrc,
                    resolvedMainJavaSrc,
                    effectiveProcessorPath,
                    effectiveClasspath,
                    options
                );
                if (!noCache
                    && ProcessorSourceCache.cacheHit(resolvedCacheDir, ProcessorSourceCache.MAIN_HASH_FILE, mainFingerprint, resolvedMainTargetDir)) {
                    progressReporter.cacheHit("main", mainSourceCount);
                    mainStatus = "cache hit";
                } else {
                    if (noCache) {
                        progressReporter.cacheBypass("main", mainSourceCount);
                    }
                    compilerExecutor.compile(new PyronautCompilerExecutor.CompileRequest(
                        resolvedMainPythonSrc,
                        resolvedMainJavaSrc,
                        resolvedMainTargetDir,
                        effectiveProcessorPath,
                        effectiveClasspath,
                        options
                    ));
                    if (!noCache) {
                        ProcessorSourceCache.writeHash(resolvedCacheDir, ProcessorSourceCache.MAIN_HASH_FILE, mainFingerprint);
                    }
                    progressReporter.finishPass("main", mainSourceCount);
                    mainStatus = "processed";
                }

                mergedTestRoot = Files.createTempDirectory("pyronaut-test-sources-");
                Path mergedTestPythonSrc = mergedTestRoot.resolve("python");
                Path mergedTestJavaSrc = mergedTestRoot.resolve("java");
                mergeSourceTrees(resolvedMainPythonSrc, resolvedTestPythonSrc, mergedTestPythonSrc);
                mergeSourceTrees(resolvedMainJavaSrc, resolvedTestJavaSrc, mergedTestJavaSrc);

                long testSourceCount = ProcessorSourceCache.countSources(mergedTestPythonSrc, ".py")
                    + ProcessorSourceCache.countSources(mergedTestJavaSrc, ".java");
                progressReporter.startPass("test", testSourceCount);
                if (testSourceCount == 0L) {
                    Files.createDirectories(resolvedTestTargetDir);
                    progressReporter.noSources("test");
                    testStatus = "no sources";
                } else {
                    String testFingerprint = ProcessorSourceCache.fingerprint(
                        mergedTestPythonSrc,
                        mergedTestJavaSrc,
                        effectiveProcessorPath,
                        effectiveTestClasspath,
                        options
                    );
                    if (!noCache
                        && ProcessorSourceCache.cacheHit(resolvedCacheDir, ProcessorSourceCache.TEST_HASH_FILE, testFingerprint, resolvedTestTargetDir)) {
                        progressReporter.cacheHit("test", testSourceCount);
                        testStatus = "cache hit";
                    } else {
                        if (noCache) {
                            progressReporter.cacheBypass("test", testSourceCount);
                        }
                        compilerExecutor.compile(new PyronautCompilerExecutor.CompileRequest(
                            mergedTestPythonSrc,
                            mergedTestJavaSrc,
                            resolvedTestTargetDir,
                            effectiveProcessorPath,
                            effectiveTestClasspath,
                            options
                        ));
                        if (!noCache) {
                            ProcessorSourceCache.writeHash(resolvedCacheDir, ProcessorSourceCache.TEST_HASH_FILE, testFingerprint);
                        }
                        progressReporter.finishPass("test", testSourceCount);
                        testStatus = "processed";
                    }
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

    public static void main(String[] args) {
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
                        Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new PyronautProcessorException("Failed to merge source tree from: " + sourceDirectory, e);
                }
            });
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed walking source tree: " + sourceDirectory, e);
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
