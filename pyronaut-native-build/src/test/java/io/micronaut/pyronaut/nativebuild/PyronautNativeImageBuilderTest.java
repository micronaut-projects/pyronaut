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
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(defaultCommand.contains("-H:-PreserveIncludesJNI"));
        assertFalse(defaultCommand.contains("-H:Preserve=package=org.graalvm.*"));
        assertTrue(defaultCommand.contains("-H:Preserve=module=java.base,module=java.sql,module=java.xml,module=java.management,module=java.naming,module=java.rmi,module=java.logging"));
        assertTrue(defaultCommand.contains("-H:Preserve=package=java.util.*"));
        assertTrue(defaultCommand.contains("-H:Preserve=package=java.util.logging"));
        assertFalse(defaultCommand.contains("-H:Preserve=package=java.util.logging.*"));
        assertFalse(defaultCommand.contains("-H:Preserve=package=java.applet.*"));
        assertTrue(defaultCommand.contains("-H:Preserve=package=org.xml.sax"));
        assertTrue(defaultCommand.contains("--initialize-at-build-time=io.micronaut.core.io"));
        assertTrue(defaultCommand.indexOf("--initialize-at-build-time=io.micronaut.http.server.cors.CorsOriginConverter")
            > defaultCommand.indexOf("--initialize-at-run-time=io.micronaut"));
        assertTrue(reportingCommand.contains("--emit"));
        assertTrue(reportingCommand.contains("build-report"));
        assertTrue(reportingCommand.contains("-H:IncludeSBOM=embed,export"));
    }

    @Test
    void preservesGsonForJdkHttpClientJsonPayloads() throws Exception {
        Path classpathEntry = tempDir.resolve("gson-runtime");
        Files.createDirectories(classpathEntry.resolve("com/google/gson"));
        Files.createFile(classpathEntry.resolve("com/google/gson/Gson.class"));

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=com.google.gson.*"));
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
        assertFalse(command.contains("-H:Preserve=package=com.google.gson.*"));
        assertFalse(command.contains("-H:Preserve=package=io.micronaut.expressions.*"));
        assertFalse(command.contains("-H:Preserve=package=io.micronaut.core.io.*"));
    }

    @Test
    void preservesDirectNettyInternalClasses() throws Exception {
        Path classpathEntry = tempDir.resolve("netty-common");
        Files.createDirectories(classpathEntry.resolve("io/netty/util/internal"));
        Files.createFile(classpathEntry.resolve("io/netty/util/internal/PlatformDependent.class"));

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=io.netty.util.internal"));
    }

    @Test
    void preservesDirectNettyCodecClasses() throws Exception {
        Path classpathEntry = tempDir.resolve("netty-handler");
        Files.createDirectories(classpathEntry.resolve("io/netty/handler/codec"));
        Files.createFile(classpathEntry.resolve("io/netty/handler/codec/ByteToMessageDecoder.class"));

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=io.netty.handler.codec"));
    }

    @Test
    void preservesMicronautPackagesInstantiatedByRuntimeLoadedBeanDefinitions() throws Exception {
        Path classpathEntry = tempDir.resolve("micronaut-runtime.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(classpathEntry))) {
            for (String entry : List.of(
                "io/micronaut/logging/impl/LogbackLoggingSystem.class",
                "io/micronaut/retry/intercept/DefaultRetryInterceptor.class",
                "io/micronaut/health/HeartbeatTask.class"
            )) {
                output.putNextEntry(new ZipEntry(entry));
                output.write(0);
                output.closeEntry();
            }
        }

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=io.micronaut.logging.*"));
        assertTrue(command.contains("-H:Preserve=package=io.micronaut.retry.*"));
        assertTrue(command.contains("-H:Preserve=package=io.micronaut.health.*"));
    }

    @Test
    void preservesMicronautCoreApisCalledFromPython() throws Exception {
        Path classpathEntry = tempDir.resolve("micronaut-core.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(classpathEntry))) {
            for (String entry : List.of(
                "io/micronaut/core/io/ResourceResolver.class",
                "io/micronaut/core/io/scan/ClassPathResourceLoader.class",
                "io/micronaut/core/convert/ConversionService.class",
                "io/micronaut/core/value/PropertyResolver.class",
                "io/micronaut/core/execution/ExecutionFlow.class"
            )) {
                output.putNextEntry(new ZipEntry(entry));
                output.write(0);
                output.closeEntry();
            }
        }

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=io.micronaut.core.io.*"));
        assertTrue(command.contains("-H:Preserve=package=io.micronaut.core.convert.*"));
        assertTrue(command.contains("-H:Preserve=package=io.micronaut.core.value.*"));
        assertTrue(command.contains("-H:Preserve=package=io.micronaut.core.execution.*"));
    }

    @Test
    void preservesMicronautCoreBufferApisFromJarClasspath() throws Exception {
        Path classpathEntry = tempDir.resolve("micronaut-core-buffer.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(classpathEntry))) {
            for (String entry : List.of(
                "io/micronaut/core/io/buffer/ReferenceCounted.class",
                "io/micronaut/core/io/buffer/ByteBuffer.class",
                "io/micronaut/core/io/buffer/ByteBufferFactory.class"
            )) {
                output.putNextEntry(new ZipEntry(entry));
                output.write(0);
                output.closeEntry();
            }
        }

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=io.micronaut.core.io.*"));
    }

    @Test
    void preservesMicronautCoreBufferApisFromDirectoryClasspath() throws Exception {
        Path classpathEntry = tempDir.resolve("micronaut-core-buffer");
        Path packageDirectory = Files.createDirectories(classpathEntry.resolve("io/micronaut/core/io/buffer"));
        for (String className : List.of("ReferenceCounted", "ByteBuffer", "ByteBufferFactory")) {
            Files.createFile(packageDirectory.resolve(className + ".class"));
        }

        List<String> command = build(classpathEntry, false, false);

        assertTrue(command.contains("-H:Preserve=package=io.micronaut.core.io.*"));
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
                Files.createDirectories(tempDir.resolve("resources"));
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
        assertTrue(command.contains("-H:+CopyLanguageResources"));
        assertTrue(command.contains("-H:-PreserveIncludesJNI"));
        assertFalse(command.contains("--initialize-at-run-time=jdk.internal.org.jline.terminal.impl.ffm.CLibrary"));
        assertFalse(command.contains("--initialize-at-build-time=com.sun.tools.javac.api.JavacTool"));
        assertTrue(command.contains("-H:Preserve=package=ch.qos.logback.*"));
        assertTrue(command.contains("-Dmicronaut.graalvm.imagesingletons.enabled=false"));
        assertTrue(command.contains("-H:Preserve=package=org.graalvm.home.*"));
        assertTrue(command.contains("-H:Preserve=package=org.graalvm.polyglot"));
    }

    @Test
    void requiresCopiedLanguageResourcesForSuccessfulPythonImages() throws Exception {
        Path classpathEntry = Files.createFile(tempDir.resolve("runtime.jar"));
        PyronautNativeImageBuilder builder = new PyronautNativeImageBuilder(
            tempDir.resolve("python-image"),
            (nativeImageCommand, workingDirectory) -> 0
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> builder.addClasspath(classpathEntry).includePython(true).build()
        );

        assertTrue(exception.getMessage().contains(tempDir.resolve("resources").toString()));
    }

    @Test
    void addsNoOptimizationArgumentsByDefault() throws Exception {
        Path classpathEntry = Files.createFile(tempDir.resolve("runtime.jar"));
        List<String> command = build(classpathEntry, false, false);

        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("--pgo")));
        assertFalse(command.contains("-H:+EnableCodeCompression"));
    }

    @Test
    void instrumentsForProfileGuidedOptimization() throws Exception {
        Path classpathEntry = Files.createFile(tempDir.resolve("runtime.jar"));
        List<String> command = build(classpathEntry, builder -> builder.pgoInstrument(true));

        assertTrue(command.contains("--pgo-instrument"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("--pgo=")));
    }

    @Test
    void mergesEveryProfileIntoOnePgoOptionWithCodeCompression() throws Exception {
        Path classpathEntry = Files.createFile(tempDir.resolve("runtime.jar"));
        Path startup = tempDir.resolve("startup.iprof");
        Path requests = tempDir.resolve("requests.iprof");
        List<String> command = build(classpathEntry, builder -> builder
            .pgoProfiles(List.of(startup, requests))
            .codeCompression(true)
            .addNativeImageArgument("--parallelism=1"));

        assertTrue(command.contains("--pgo=" + startup + "," + requests));
        assertFalse(command.contains("--pgo-instrument"));
        int compression = command.indexOf("-H:+EnableCodeCompression");
        int lastLock = command.lastIndexOf("-H:-UnlockExperimentalVMOptions");
        assertTrue(compression > 0);
        assertEquals("-H:+UnlockExperimentalVMOptions", command.get(compression - 1));
        assertEquals(compression + 1, lastLock);
        assertTrue(command.indexOf("--parallelism=1") > compression);
    }

    @Test
    void rejectsInstrumentingAndOptimizingTogether() throws Exception {
        Path classpathEntry = Files.createFile(tempDir.resolve("runtime.jar"));

        assertThrows(IllegalStateException.class, () -> build(classpathEntry, builder -> builder
            .pgoInstrument(true)
            .pgoProfiles(List.of(tempDir.resolve("default.iprof")))));
    }

    private List<String> build(Path classpathEntry, Consumer<PyronautNativeImageBuilder> customizer) throws Exception {
        List<String> command = new ArrayList<>();
        PyronautNativeImageBuilder builder = new PyronautNativeImageBuilder(
            tempDir.resolve("image"),
            (nativeImageCommand, workingDirectory) -> {
                command.addAll(nativeImageCommand);
                return 0;
            }
        );
        builder.addClasspath(classpathEntry);
        customizer.accept(builder);
        builder.build();
        return command;
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
        assertFalse(command.contains("-H:+CopyLanguageResources"));
        return command;
    }
}
