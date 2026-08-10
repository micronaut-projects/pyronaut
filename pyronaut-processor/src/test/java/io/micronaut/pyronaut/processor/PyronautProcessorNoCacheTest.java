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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PyronautProcessorNoCacheTest {

    @Test
    void sourceSnapshotCountsSourcesWithoutASecondTraversal(@TempDir Path tempDir) throws Exception {
        Path python = Files.createDirectories(tempDir.resolve("src"));
        Path java = Files.createDirectories(tempDir.resolve("src-java"));
        Files.writeString(python.resolve("one.py"), "VALUE = 1\n");
        Files.writeString(python.resolve("two.py"), "VALUE = 2\n");
        Files.writeString(java.resolve("One.java"), "class One {}\n");

        ProcessorSourceCache.InputSnapshot snapshot = ProcessorSourceCache.snapshot(
            python,
            java,
            List.of(),
            List.of(),
            false,
            true,
            "conservative",
            List.of(),
            tempDir.resolve("processor.inputs")
        );

        assertTrue(snapshot.sourceCount() == 3);
        assertTrue(snapshot.fingerprint().equals(ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(),
            List.of(),
            false,
            true,
            "conservative",
            List.of(),
            tempDir.resolve("processor.inputs")
        )));
    }

    @Test
    void bytecodeSettingParticipatesInCacheFingerprint(@TempDir Path tempDir) throws Exception {
        Path python = tempDir.resolve("src");
        Path java = tempDir.resolve("src-java");
        Files.createDirectories(python);
        Files.createDirectories(java);
        Files.writeString(python.resolve("controller.py"), "print('hello')\n", StandardCharsets.UTF_8);

        String sourceOnly = ProcessorSourceCache.fingerprint(python, java, List.of(), List.of(), false, List.of());
        String bytecode = ProcessorSourceCache.fingerprint(python, java, List.of(), List.of(), true, List.of());

        assertFalse(sourceOnly.equals(bytecode));
    }

    @Test
    void incrementalModeAndDependencyContentsParticipateInCacheFingerprint(
        @TempDir Path tempDir
    ) throws Exception {
        Path python = Files.createDirectories(tempDir.resolve("src"));
        Path java = Files.createDirectories(tempDir.resolve("src-java"));
        Path processorPath = Files.createDirectories(tempDir.resolve("processor-path"));
        Path classpath = Files.createDirectories(tempDir.resolve("classpath"));
        Path processorMarker = processorPath.resolve("processor.version");
        Path classpathMarker = classpath.resolve("dependency.version");
        Files.writeString(python.resolve("controller.py"), "VALUE = 1\n");
        Files.writeString(processorMarker, "one");
        Files.writeString(classpathMarker, "one");

        String baseline = ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(processorPath),
            List.of(classpath),
            false,
            false,
            List.of()
        );
        String incremental = ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(processorPath),
            List.of(classpath),
            false,
            true,
            List.of()
        );
        assertFalse(baseline.equals(incremental));
        String optimistic = ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(processorPath),
            List.of(classpath),
            false,
            true,
            "optimistic",
            List.of()
        );
        assertFalse(incremental.equals(optimistic));

        Files.writeString(classpathMarker, "two");
        String changedClasspath = ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(processorPath),
            List.of(classpath),
            false,
            false,
            List.of()
        );
        assertFalse(baseline.equals(changedClasspath));

        Files.writeString(classpathMarker, "one");
        Files.writeString(processorMarker, "two");
        String changedProcessorPath = ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(processorPath),
            List.of(classpath),
            false,
            false,
            List.of()
        );
        assertFalse(baseline.equals(changedProcessorPath));
    }

    @Test
    void persistsContentDigestsForUnchangedInputs(@TempDir Path tempDir) throws Exception {
        Path python = Files.createDirectories(tempDir.resolve("src"));
        Path java = Files.createDirectories(tempDir.resolve("src-java"));
        Path classpath = Files.createDirectories(tempDir.resolve("classpath"));
        Files.writeString(python.resolve("controller.py"), "VALUE = 1\n");
        Path dependency = classpath.resolve("dependency.version");
        Files.writeString(dependency, "one");
        Path contentCache = tempDir.resolve("processor.inputs");

        String first = ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(),
            List.of(classpath),
            false,
            true,
            "conservative",
            List.of(),
            contentCache
        );
        assertTrue(Files.isRegularFile(contentCache));
        assertTrue(first.equals(ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(),
            List.of(classpath),
            false,
            true,
            "conservative",
            List.of(),
            contentCache
        )));

        Files.writeString(dependency, "two");
        assertFalse(first.equals(ProcessorSourceCache.fingerprint(
            python,
            java,
            List.of(),
            List.of(classpath),
            false,
            true,
            "conservative",
            List.of(),
            contentCache
        )));
    }

    @Test
    void noCacheSkipsCacheWrite(@TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("app");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve(PyprojectModelReader.FILE_NAME), "[project]\nname='demo'\nversion='0.1.0'\n", StandardCharsets.UTF_8);

        Path cacheDir = projectDir.resolve("__pyronaut__");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("resolved-build-dependencies"), "", StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve("resolved-runtime-dependencies"), "", StandardCharsets.UTF_8);
        Files.writeString(cacheDir.resolve("resolved-test-dependencies"), "", StandardCharsets.UTF_8);

        Path pythonSrc = projectDir.resolve("src");
        Files.createDirectories(pythonSrc);
        Files.writeString(pythonSrc.resolve("controller.py"), "print('hello')\n", StandardCharsets.UTF_8);

        Path mainTarget = projectDir.resolve("__pyronaut__/classes");
        Files.createDirectories(mainTarget);
        Files.writeString(mainTarget.resolve("marker.txt"), "marker", StandardCharsets.UTF_8);

        Path testTarget = projectDir.resolve("__pyronaut__/test-classes");
        Files.createDirectories(testTarget);
        Files.writeString(testTarget.resolve("marker.txt"), "marker", StandardCharsets.UTF_8);

        ProcessorSourceCache.writeHash(cacheDir, ProcessorSourceCache.MAIN_HASH_FILE, "stale");
        ProcessorSourceCache.writeHash(cacheDir, ProcessorSourceCache.TEST_HASH_FILE, "stale");
        Path mainIncrementalState = cacheDir.resolve("incremental/main/state.properties");
        Path testIncrementalState = cacheDir.resolve("incremental/test/state.properties");
        Files.createDirectories(mainIncrementalState.getParent());
        Files.createDirectories(testIncrementalState.getParent());
        Files.writeString(mainIncrementalState, "stale", StandardCharsets.UTF_8);
        Files.writeString(testIncrementalState, "stale", StandardCharsets.UTF_8);

        class CountingExecutor implements PyronautCompilerExecutor {
            int calls;
            boolean incremental;

            @Override
            public void compile(CompileRequest request) {
                calls++;
                incremental |= request.incremental();
            }
        }

        CountingExecutor executor = new CountingExecutor();
        PyronautProcessorMain main = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        main.projectDir = projectDir;
        main.progress = "off";
        main.noCache = true;
        main.annotationProcessorPath = List.of();
        main.classpath = List.of();
        main.testClasspath = List.of();

        int code = main.call();
        assertTrue(code == PyronautProcessorExitCode.SUCCESS.code());
        assertTrue(executor.calls >= 1);
        assertFalse(executor.incremental);
        assertFalse(Files.exists(mainIncrementalState));
        assertFalse(Files.exists(testIncrementalState));
        assertFalse(Files.exists(mainTarget.resolve("marker.txt")));
        assertFalse(Files.exists(testTarget.resolve("marker.txt")));
        assertFalse(Files.exists(cacheDir.resolve(ProcessorSourceCache.MAIN_HASH_FILE)));
        assertFalse(Files.exists(cacheDir.resolve(ProcessorSourceCache.TEST_HASH_FILE)));
        assertFalse(ProcessorSourceCache.cacheHit(cacheDir, ProcessorSourceCache.MAIN_HASH_FILE, ProcessorSourceCache.fingerprint(
            projectDir.resolve("src"),
            projectDir.resolve("src-java"),
            List.of(),
            List.of(),
            List.of()
        ), mainTarget));
    }
}
