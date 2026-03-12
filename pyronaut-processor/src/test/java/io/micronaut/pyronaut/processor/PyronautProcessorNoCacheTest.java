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

        class CountingExecutor implements PyronautCompilerExecutor {
            int calls;

            @Override
            public void compile(CompileRequest request) {
                calls++;
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
        assertFalse(ProcessorSourceCache.cacheHit(cacheDir, ProcessorSourceCache.MAIN_HASH_FILE, ProcessorSourceCache.fingerprint(
            projectDir.resolve("src"),
            projectDir.resolve("src-java"),
            List.of(),
            List.of(),
            List.of()
        ), mainTarget));
    }
}
