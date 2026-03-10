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

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Entry point for {@code pyronaut-processor}.
 */
@CommandLine.Command(name = "pyronaut-processor", mixinStandardHelpOptions = true, description = "Process Python sources and generate Micronaut metadata")
public final class PyronautProcessorMain implements Callable<Integer> {
    private static final String DEFAULT_PYTHON_SRC = "src";
    private static final String DEFAULT_TARGET_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_JAVA_SRC = "src-java";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory containing pyproject.toml")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--python-src", defaultValue = DEFAULT_PYTHON_SRC, description = "Python source directory")
    Path pythonSrc = Path.of(DEFAULT_PYTHON_SRC);

    @CommandLine.Option(names = "--target-dir", defaultValue = DEFAULT_TARGET_DIR, description = "Target output directory")
    Path targetDir = Path.of(DEFAULT_TARGET_DIR);

    @CommandLine.Option(names = "--java-src", defaultValue = DEFAULT_JAVA_SRC, description = "Java source directory")
    Path javaSrc = Path.of(DEFAULT_JAVA_SRC);

    @CommandLine.Option(names = "--annotation-processor-path", split = "${sys:path.separator}", description = "Override annotation processor classpath")
    List<Path> annotationProcessorPath;

    @CommandLine.Option(names = "--classpath", split = "${sys:path.separator}", description = "Override runtime classpath")
    List<Path> classpath;

    @CommandLine.Option(names = "--option", description = "Additional compiler option")
    List<String> options = List.of();

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
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME));

            List<Path> effectiveProcessorPath = annotationProcessorPath == null || annotationProcessorPath.isEmpty()
                ? ClasspathManifestReader.read(
                root.resolve(".pytest_cache").resolve("resolved-build-dependencies"),
                "Missing build scope cache. Run pyronaut-install first"
            )
                : annotationProcessorPath;

            List<Path> effectiveClasspath = classpath == null || classpath.isEmpty()
                ? ClasspathManifestReader.read(
                root.resolve(".pytest_cache").resolve("resolved-runtime-dependencies"),
                "Missing runtime scope cache. Run pyronaut-install first"
            )
                : classpath;

            compilerExecutor.compile(new PyronautCompilerExecutor.CompileRequest(
                root.resolve(pythonSrc).normalize(),
                root.resolve(javaSrc).normalize(),
                root.resolve(targetDir).normalize(),
                effectiveProcessorPath,
                effectiveClasspath,
                options
            ));
            return PyronautProcessorExitCode.SUCCESS.code();
        } catch (PyronautProcessorException e) {
            System.err.println(e.getMessage());
            return PyronautProcessorExitCode.PRECONDITION_FAILED.code();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return PyronautProcessorExitCode.USAGE_ERROR.code();
        } catch (RuntimeException e) {
            System.err.println("Processing failed: " + e.getMessage());
            return PyronautProcessorExitCode.PROCESSING_ERROR.code();
        } catch (Exception e) {
            System.err.println("Unexpected processor failure: " + e.getMessage());
            return PyronautProcessorExitCode.INTERNAL_ERROR.code();
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautProcessorMain()).execute(args);
        System.exit(exitCode);
    }
}
