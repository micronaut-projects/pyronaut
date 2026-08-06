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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Builds a Crema native image capable of loading an already compiled Pyronaut
 * application at runtime.
 *
 * <p>The builder deliberately receives its runtime classpath explicitly. This
 * prevents build-time tooling from accidentally becoming part of a production
 * launcher image.</p>
 */
public final class PyronautNativeImageBuilder {
    /** The fixed production launcher entry point. */
    public static final String DEFAULT_MAIN_CLASS = "io.micronaut.pyronaut.run.PyronautRunMain";

    private static final List<String> COMMON_ARGUMENTS = List.of(
        "--verbose",
        "-H:+UnlockExperimentalVMOptions",
        "-H:EnableURLProtocols=jar",
        "-H:+RuntimeClassLoading",
        "-H:+AllowJRTFileSystem",
        "-H:+SharedArenaSupport",
        "-H:-SupportCompileInIsolates",
        "--add-modules=java.net.http,java.naming,java.rmi,java.xml",
        "--enable-http",
        "--enable-https",
        // "-Os", optimize for size and quick build
        "-H:-PrintRestrictHeapAccessWarnings",
        "-H:IncludeResources=templates/.*",

        /*
         * -H:Preserve
         */

        /* Modules */
        "-H:Preserve=module=java.base,module=java.sql,module=java.xml,module=java.management",

        /* java.* */
        "-H:Preserve=package=java.applet.*",
        // "-H:Preserve=package=java.awt.*",
        "-H:Preserve=package=java.beans.*",
        "-H:Preserve=package=java.io.*",
        "-H:Preserve=package=java.lang.*",
        "-H:Preserve=package=java.math.*",
        "-H:Preserve=package=java.net.*",
        "-H:Preserve=package=java.nio.*",
        "-H:Preserve=package=java.rmi.*",
        "-H:Preserve=package=java.security.*",
        "-H:Preserve=package=java.sql.*",
        "-H:Preserve=package=java.text.*",
        "-H:Preserve=package=java.time.*",
        "-H:Preserve=package=java.util.*",

        /* sun.* */
        // "-H:Preserve=package=sun.awt.*",
        // "-H:Preserve=package=sun.datatransfer.*",
        // "-H:Preserve=package=sun.font.*",
        "-H:Preserve=package=sun.instrument.*",
        "-H:Preserve=package=sun.invoke.*",
        // "-H:Preserve=package=sun.java2d.*",
        // "-H:Preserve=package=sun.launcher.*",
        // "-H:Preserve=package=sun.lwawt.*",
        "-H:Preserve=package=sun.management.*",
        "-H:Preserve=package=sun.misc.*",
        "-H:Preserve=package=sun.net.*",
        "-H:Preserve=package=sun.nio.*",
        "-H:Preserve=package=sun.print.*",
        "-H:Preserve=package=sun.reflect.*",
        "-H:Preserve=package=sun.rmi.*",
        "-H:Preserve=package=sun.security.*",
        // "-H:Preserve=package=sun.swing.*",
        "-H:Preserve=package=sun.text.*",
        // "-H:Preserve=package=sun.tools.*",
        "-H:Preserve=package=sun.usagetracker.*",
        "-H:Preserve=package=sun.util.*",

        /* javax.* */
        "-H:Preserve=package=javax.management.*",
        "-H:Preserve=package=javax.sql",
        "-H:Preserve=package=javax.xml.parsers",
        "-H:Preserve=package=javax.xml.transform.dom",
        "-H:Preserve=package=javax.xml.transform.sax",
        "-H:Preserve=package=javax.xml.transform",
        "-H:Preserve=package=javax.xml.validation",
        "-H:Preserve=package=javax.xml.xpath",
        "-H:Preserve=package=javax.xml",

        /* jakarta.* */
        "-H:Preserve=package=jakarta.*",
        "-H:Preserve=package=jakarta.annotation,package=jakarta.annotation.*,package=jakarta.inject,package=jakarta.inject.*",

        /* jdk.internal.* */
        "-H:Preserve=package=jdk.internal.misc.*",
        "-H:Preserve=package=jdk.internal.access.*",
        
        /* io.micronaut.* */
        "-H:Preserve=package=io.micronaut.cache.*",
        "-H:Preserve=package=io.micronaut.data.*",
        "-H:Preserve=package=io.micronaut.discovery.*",
        "-H:Preserve=package=io.micronaut.transaction.*",
        "-H:Preserve=package=io.micronaut.jdbc.*",
        "-H:Preserve=package=io.micronaut.management.*",
        "-H:Preserve=package=io.micronaut.messaging.*",
        "-H:Preserve=package=io.micronaut.reactor.*",
        "-H:Preserve=package=io.micronaut.core.annotation.*",
        "-H:Preserve=package=io.micronaut.core.beans.*",
        "-H:Preserve=package=io.micronaut.expressions.*",
        "-H:Preserve=package=io.micronaut.context.visitor.*",
        "-H:Preserve=package=io.micronaut.validation.*",
        "-H:Preserve=package=io.micronaut.core.naming.*",
        "-H:Preserve=package=io.micronaut.core.reflect.*",
        "-H:Preserve=package=io.micronaut.core.type.*",
        "-H:Preserve=package=io.micronaut.core.util.*",
        "-H:Preserve=package=io.micronaut.core.io.service.*",
        "-H:Preserve=package=io.micronaut.buffer.netty.*",
        "-H:Preserve=package=io.micronaut.inject.*",
        "-H:Preserve=package=io.micronaut.context.*",
        "-H:Preserve=package=io.micronaut.scheduling.*",
        "-H:Preserve=package=io.micronaut.security.annotation.*",
        "-H:Preserve=package=io.micronaut.runtime.*",
        "-H:Preserve=package=io.micronaut.http.*",
        "-H:Preserve=package=io.micronaut.websocket.*",
        "-H:Preserve=package=io.micronaut.http.netty.*",
        "-H:Preserve=package=io.micronaut.aop.*",
        "-H:Preserve=package=io.micronaut.jackson.*",
        "-H:Preserve=package=io.micronaut.json.*",
        "-H:Preserve=package=io.micronaut.serde.*",
        "-H:Preserve=package=io.micronaut.toml.*",
        "-H:Preserve=package=io.micronaut.views.*",
        "-H:Preserve=package=io.micronaut.web.router.*",

        /* netty.* */
        "-H:Preserve=package=io.netty.channel.nio",
        "-H:Preserve=package=io.netty.channel",
        "-H:Preserve=package=io.netty.handler.codec.http.*",
        "-H:Preserve=package=io.netty.handler.ssl",
        "-H:Preserve=package=io.netty.resolver.*",
        "-H:Preserve=package=io.netty.util.concurrent",
        "-H:Preserve=package=io.netty.util", 

        /* other */
        "-H:Preserve=package=com.fasterxml.jackson.annotation.*",
        "-H:Preserve=package=org.slf4j.*",
        "-H:Preserve=package=org.w3c.dom.bootstrap",
        "-H:Preserve=package=org.w3c.dom.events",
        "-H:Preserve=package=org.w3c.dom.ls",
        "-H:Preserve=package=org.w3c.dom",
        "-H:Preserve=package=org.xml.sax.ext",
        "-H:Preserve=package=org.xml.sax.helpers",
        "-H:Preserve=package=org.xml.sax",
        "-H:Preserve=package=tools.jackson.core.*",

        /*
         * --initialize-at-(build|run)-time
         */

        "--initialize-at-build-time=jakarta.annotation,jakarta.inject",
        "--initialize-at-build-time=io.micronaut.core.io",
        "--initialize-at-build-time=io.micronaut.core.optim",
        "--initialize-at-build-time=io.micronaut.core.async.publisher.PublishersOptimizations",
        "--initialize-at-build-time=io.micronaut.core.util",
        "--initialize-at-build-time=io.micronaut.core.bind",
        "--initialize-at-build-time=io.micronaut.core.convert",
        "--initialize-at-build-time=io.micronaut.core.convert.ConversionContext",
        "--initialize-at-build-time=io.micronaut.core.convert.ImmutableArgumentConversionContext",
        "--initialize-at-build-time=io.micronaut.core.type",
        "--initialize-at-build-time=io.micronaut.core.annotation",
        "--initialize-at-build-time=io.micronaut.core.annotation.AnnotationValue",
        "--initialize-at-build-time=io.micronaut.core.annotation.AnnotationValueResolver",
        "--initialize-at-build-time=io.micronaut.core.reflect.ReflectionUtils",
        "--initialize-at-build-time=io.micronaut.core.reflect.ClassUtils$Optimizations",
        "--initialize-at-build-time=io.micronaut.scheduling.LoomSupport",
        "--initialize-at-build-time=io.micronaut.http.netty.channel.loom.PrivateLoomSupport",
        "--initialize-at-build-time=io.micronaut.http.netty.channel.loom.PrivateLoomSupport$PrivateLoomCondition",
        "--initialize-at-build-time=io.micronaut.http.MediaType",
        "--initialize-at-build-time=io.micronaut.http.annotation",
        "--initialize-at-build-time=io.micronaut.json",
        "--initialize-at-build-time=io.micronaut.json.bind",
        "--initialize-at-build-time=io.micronaut.json.body",
        "--initialize-at-build-time=io.micronaut.json.convert",
        "--initialize-at-build-time=io.micronaut.json.codec",
        "--initialize-at-build-time=io.micronaut.json.tree",
        "--initialize-at-build-time=io.micronaut.messaging",
        "--initialize-at-build-time=io.micronaut.messaging.annotation",
        "--initialize-at-build-time=io.micronaut.messaging.exceptions",
        "--initialize-at-build-time=io.micronaut.management.endpoint",
        "--initialize-at-build-time=io.micronaut.management.endpoint.annotation",
        "--initialize-at-build-time=io.micronaut.management.endpoint.health",
        "--initialize-at-build-time=io.micronaut.management.endpoint.indicator.annotation",
        "--initialize-at-build-time=io.micronaut.retry.annotation",
        "--initialize-at-build-time=io.micronaut.retry.event",
        "--initialize-at-build-time=io.micronaut.retry.exception",
        "--initialize-at-run-time=jdk.internal.loader.ClassLoaders",
        "--initialize-at-run-time=io.netty",
        "--initialize-at-run-time=io.micronaut.core.io.socket.SocketUtils",
        // GraalVM 25 can reach JShell's JLine/FFM code through JDK module
        // analysis even for production launchers; defer these classes to
        // runtime instead of including compiler initialization in the image.
        "--initialize-at-run-time=jdk.jshell",
        "--initialize-at-run-time=jdk.internal.jshell.tool",
        "--initialize-at-run-time=jdk.internal.shellsupport.doc",
        "--initialize-at-run-time=com.sun.tools.javac.api",
        "--initialize-at-run-time=jdk.internal.org.jline",

        /*
         * Debug
         */
        // "-H:AbortOnTypeReachable=java.awt.*",
        // "--debug-attach",

        "-H:-UnlockExperimentalVMOptions"
    );

    private static final List<String> PYTHON_ARGUMENTS = List.of(
        "--enable-native-access=org.graalvm.truffle",
        "-H:Preserve=package=ch.qos.logback.classic.*",
        "-H:Preserve=package=ch.qos.logback.core.*"
    );

    private final Path output;
    private final LinkedHashSet<Path> classpath = new LinkedHashSet<>();
    private final LinkedHashSet<Path> pythonClasspath = new LinkedHashSet<>();
    private final List<String> nativeImageArguments = new ArrayList<>();
    private final NativeImageCommandExecutor commandExecutor;
    private Path nativeImageExecutable = Path.of("native-image");
    private Path workingDirectory = Path.of(".");
    private String mainClass = DEFAULT_MAIN_CLASS;
    private boolean includePython;
    private boolean emitBuildReport;
    private boolean includeSbom;

    /**
     * @param output native executable output path
     */
    public PyronautNativeImageBuilder(Path output) {
        this(output, new ProcessNativeImageCommandExecutor());
    }

    PyronautNativeImageBuilder(Path output, NativeImageCommandExecutor commandExecutor) {
        this.output = Objects.requireNonNull(output, "output");
        this.commandExecutor = Objects.requireNonNull(commandExecutor, "commandExecutor");
    }

    /**
     * Set the native-image executable.
     *
     * @param executable native-image executable
     * @return this builder
     */
    public PyronautNativeImageBuilder nativeImageExecutable(Path executable) {
        nativeImageExecutable = Objects.requireNonNull(executable, "executable");
        return this;
    }

    /**
     * Set the directory in which native-image executes.
     *
     * @param directory build directory
     * @return this builder
     */
    public PyronautNativeImageBuilder workingDirectory(Path directory) {
        workingDirectory = Objects.requireNonNull(directory, "directory");
        return this;
    }

    /**
     * Add one Java runtime classpath entry.
     *
     * @param entry classpath entry
     * @return this builder
     */
    public PyronautNativeImageBuilder addClasspath(Path entry) {
        classpath.add(normalizeExisting(entry));
        return this;
    }

    /**
     * Add Java runtime classpath entries.
     *
     * @param entries classpath entries
     * @return this builder
     */
    public PyronautNativeImageBuilder addClasspath(Collection<Path> entries) {
        entries.forEach(this::addClasspath);
        return this;
    }

    /**
     * Add one Python/Truffle runtime classpath entry. Entries are included only
     * when {@link #includePython(boolean)} is enabled.
     *
     * @param entry Python classpath entry
     * @return this builder
     */
    public PyronautNativeImageBuilder addPythonClasspath(Path entry) {
        pythonClasspath.add(normalizeExisting(entry));
        return this;
    }

    /**
     * Add Python/Truffle runtime classpath entries.
     *
     * @param entries Python classpath entries
     * @return this builder
     */
    public PyronautNativeImageBuilder addPythonClasspath(Collection<Path> entries) {
        entries.forEach(this::addPythonClasspath);
        return this;
    }

    /**
     * Enable or disable Python and Truffle support.
     *
     * @param enabled whether Python dependencies and native-image options are included
     * @return this builder
     */
    public PyronautNativeImageBuilder includePython(boolean enabled) {
        includePython = enabled;
        return this;
    }

    /**
     * Configure whether native-image emits its build report.
     *
     * @param enabled whether to emit the report
     * @return this builder
     */
    public PyronautNativeImageBuilder emitBuildReport(boolean enabled) {
        emitBuildReport = enabled;
        return this;
    }

    /**
     * Configure whether the native executable embeds and exports an SBOM.
     *
     * @param enabled whether to include the SBOM
     * @return this builder
     */
    public PyronautNativeImageBuilder includeSbom(boolean enabled) {
        includeSbom = enabled;
        return this;
    }

    /**
     * Add a native-image argument after the curated production arguments.
     *
     * @param argument native-image argument
     * @return this builder
     */
    public PyronautNativeImageBuilder addNativeImageArgument(String argument) {
        nativeImageArguments.add(Objects.requireNonNull(argument, "argument"));
        return this;
    }

    /**
     * Preserve all classes in the supplied application package for reflective
     * access in a closed-world native image.
     *
     * @param packageName application package
     * @return this builder
     */
    public PyronautNativeImageBuilder preservePackage(String packageName) {
        String value = Objects.requireNonNull(packageName, "packageName").trim();
        if (!value.isEmpty()) {
            addNativeImageArgument("-H:Preserve=package=" + value + ".*");
        }
        return this;
    }

    /**
     * Preserve all classes in the supplied application packages.
     *
     * @param packageNames application packages
     * @return this builder
     */
    public PyronautNativeImageBuilder preservePackages(Collection<String> packageNames) {
        Objects.requireNonNull(packageNames, "packageNames").stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .forEach(this::preservePackage);
        return this;
    }

    /**
     * Add native-image arguments after the curated production arguments.
     *
     * @param arguments native-image arguments
     * @return this builder
     */
    public PyronautNativeImageBuilder addNativeImageArguments(Collection<String> arguments) {
        Objects.requireNonNull(arguments, "arguments").forEach(this::addNativeImageArgument);
        return this;
    }

    /**
     * Override the fixed launcher main class for a specialized image.
     *
     * @param value main class
     * @return this builder
     */
    public PyronautNativeImageBuilder mainClass(String value) {
        mainClass = Objects.requireNonNull(value, "value");
        return this;
    }

    /**
     * Execute native-image.
     *
     * @return result of the native-image invocation
     * @throws IOException when preparation or process execution fails
     * @throws InterruptedException if interrupted while native-image runs
     */
    public BuildResult build() throws IOException, InterruptedException {
        Path normalizedOutput = output.toAbsolutePath().normalize();
        Path outputParent = normalizedOutput.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        Path normalizedWorkingDirectory = workingDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedWorkingDirectory);

        LinkedHashSet<Path> effectiveClasspath = new LinkedHashSet<>(classpath);
        if (includePython) {
            effectiveClasspath.addAll(pythonClasspath);
        }
        if (effectiveClasspath.isEmpty()) {
            throw new IllegalStateException("A Crema image requires at least one runtime classpath entry");
        }

        List<String> command = new ArrayList<>();
        command.add(nativeImageExecutable.toString());
        command.add("-cp");
        command.add(effectiveClasspath.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
        command.addAll(COMMON_ARGUMENTS);
        if (emitBuildReport) {
            command.add("--emit");
            command.add("build-report");
        }
        if (includeSbom) {
            command.add("-H:IncludeSBOM=embed,export");
        }
        if (includePython) {
            command.addAll(PYTHON_ARGUMENTS);
        }
        command.addAll(nativeImageArguments);
        command.add(mainClass);
        command.add(normalizedOutput.toString());
        int exitCode = commandExecutor.execute(List.copyOf(command), normalizedWorkingDirectory);
        return new BuildResult(exitCode, normalizedOutput, normalizedWorkingDirectory);
    }

    private static Path normalizeExisting(Path entry) {
        Path normalized = Objects.requireNonNull(entry, "entry").toAbsolutePath().normalize();
        return normalized;
    }

    /**
     * Result returned after invoking native-image.
     *
     * @param exitCode native-image process exit code
     * @param executable generated native executable
     * @param outputDirectory native-image working directory
     */
    public record BuildResult(int exitCode, Path executable, Path outputDirectory) {
    }

    @FunctionalInterface
    interface NativeImageCommandExecutor {
        int execute(List<String> command, Path workingDirectory) throws IOException, InterruptedException;
    }

    private static final class ProcessNativeImageCommandExecutor implements NativeImageCommandExecutor {
        @Override
        public int execute(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .inheritIO();
            // The native-build distribution launcher exports its own
            // CLASSPATH (including both production runners). It must not leak
            // into an application image whose explicit -cp is language
            // specific.
            processBuilder.environment().remove("CLASSPATH");
            return processBuilder.start().waitFor();
        }
    }
}
