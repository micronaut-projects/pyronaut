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
package io.micronaut.pyronaut.nativebuild;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautNativeImageBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void onlyEmitsReportAndSbomWhenRequested() throws Exception {
        Path classpathEntry = Files.createFile(tempDir.resolve("runtime.jar"));
        List<String> defaultCommand = build(classpathEntry, false, false);
        List<String> reportingCommand = build(classpathEntry, true, true);

        assertFalse(defaultCommand.contains("--emit"));
        assertFalse(defaultCommand.contains("-H:IncludeSBOM=embed,export"));
        assertFalse(defaultCommand.contains("--no-fallback"));
        assertFalse(defaultCommand.contains("-H:Preserve=package=org.graalvm.*"));
        assertTrue(defaultCommand.contains("-H:Preserve=module=java.base,module=java.sql,module=java.xml,module=java.management,module=java.naming,module=java.rmi"));
        assertTrue(defaultCommand.contains("-H:Preserve=package=java.util.*"));
        assertFalse(defaultCommand.contains("-H:Preserve=package=java.applet.*"));
        assertTrue(defaultCommand.contains("-H:Preserve=package=org.xml.sax"));
        assertTrue(defaultCommand.contains("--initialize-at-build-time=io.micronaut.core.io"));
        assertTrue(reportingCommand.contains("--emit"));
        assertTrue(reportingCommand.contains("build-report"));
        assertTrue(reportingCommand.contains("-H:IncludeSBOM=embed,export"));
    }

    @Test
    void preservesOnlyPackagesPresentOnTheEffectiveClasspath() throws Exception {
        Path classpathEntry = tempDir.resolve("jackson-core.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(classpathEntry))) {
            output.putNextEntry(new ZipEntry("tools/jackson/core/JsonFactory.class"));
            output.write(0);
            output.closeEntry();
        }

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=tools.jackson.core.*"));
        assertFalse(command.contains("-H:Preserve=package=io.micronaut.expressions.*"));
    }

    @Test
    void addsPythonClasspathAndTruffleAccessOnlyWhenPythonIsEnabled() throws Exception {
        Path javaClasspathEntry = tempDir.resolve("runtime.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(javaClasspathEntry))) {
            output.putNextEntry(new ZipEntry("ch/qos/logback/classic/Logger.class"));
            output.write(0);
            output.closeEntry();
        }
        Path pythonClasspathEntry = Files.createFile(tempDir.resolve("python-runtime.jar"));
        List<String> command = new ArrayList<>();
        PyronautNativeImageBuilder builder = new PyronautNativeImageBuilder(
            tempDir.resolve("python-image"),
            (nativeImageCommand, workingDirectory) -> {
                command.addAll(nativeImageCommand);
                return 0;
            }
        );

        builder.addClasspath(javaClasspathEntry)
            .addPythonClasspath(pythonClasspathEntry)
            .includePython(true)
            .build();

        String classpath = command.get(command.indexOf("-cp") + 1);
        assertTrue(classpath.contains(pythonClasspathEntry.toString()));
        assertTrue(command.contains("--enable-native-access=org.graalvm.truffle"));
        assertFalse(command.contains("--initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm.CLibrary"));
        assertFalse(command.contains("--initialize-at-build-time=com.sun.tools.javac.api.JavacTool"));
        assertTrue(command.contains("-H:Preserve=package=ch.qos.logback.*"));
        assertTrue(command.contains("-Dmicronaut.graalvm.imagesingletons.enabled=false"));
        assertTrue(command.contains("-H:Preserve=package=org.graalvm.home.*"));
        assertTrue(command.contains("-H:Preserve=package=org.graalvm.nativeimage"));
        assertTrue(command.contains("-H:Preserve=package=org.graalvm.polyglot"));
    }

    private List<String> build(Path classpathEntry, boolean emitBuildReport, boolean includeSbom) throws Exception {
        List<String> command = new ArrayList<>();
        PyronautNativeImageBuilder builder = new PyronautNativeImageBuilder(
            tempDir.resolve("image"),
            (nativeImageCommand, workingDirectory) -> {
                command.addAll(nativeImageCommand);
                return 0;
            }
        );
        builder.addClasspath(classpathEntry)
            .emitBuildReport(emitBuildReport)
            .includeSbom(includeSbom)
            .build();
        return command;
    }
}
