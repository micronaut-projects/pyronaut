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
package io.micronaut.pyronaut.testresources;

import io.micronaut.testresources.buildtools.ServerFactory;
import io.micronaut.testresources.buildtools.ServerUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

final class TestResourcesServerFactory implements ServerFactory {
    private static final String LOGS_DIR_SYSTEM_PROPERTY = "pyronaut.test-resources.logs-dir";
    private static final String LOGBACK_CONFIGURATION_SYSTEM_PROPERTY = "logback.configurationFile";
    private static final String JMX_REMOTE_SYSTEM_PROPERTY = "com.sun.management.jmxremote";
    private static final String STDIO_LOG_FILE = "launcher-stdio.log";
    private static final String LOGBACK_CONFIGURATION_FILE = "pyronaut-test-resources-logback.xml";
    private static final File NULL_DEVICE = new File(isWindows(System.getProperty("os.name", "")) ? "NUL" : "/dev/null");

    private final PyronautTestResourcesServerMain.ServerStartRequest request;
    private final ProcessStarter processStarter;
    private Process process;

    TestResourcesServerFactory(PyronautTestResourcesServerMain.ServerStartRequest request) {
        this(request, new ProcessBuilderStarter(request.logsDir()));
    }

    TestResourcesServerFactory(PyronautTestResourcesServerMain.ServerStartRequest request, ProcessStarter processStarter) {
        this.request = request;
        this.processStarter = processStarter;
    }

    @Override
    public void startServer(ServerUtils.ProcessParameters processParameters) throws IOException {
        String javaExecutable = request.javaExecutable() == null || request.javaExecutable().isBlank()
            ? "java"
            : request.javaExecutable();
        java.nio.file.Files.createDirectories(request.logsDir());

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(processParameters.getJvmArguments());
        if (request.debugServer()) {
            command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
        }
        command.add("-D" + LOGS_DIR_SYSTEM_PROPERTY + "=" + request.logsDir().toAbsolutePath().normalize());
        String logbackConfiguration = materializeLogbackConfiguration(request.logsDir());
        if (logbackConfiguration != null && !logbackConfiguration.isBlank()) {
            command.add("-D" + LOGBACK_CONFIGURATION_SYSTEM_PROPERTY + "=" + logbackConfiguration);
        }

        Map<String, String> systemProperties = new LinkedHashMap<>(processParameters.getSystemProperties());
        Map<String, String> configuredSystemProperties = request.systemProperties();
        if (configuredSystemProperties != null) {
            systemProperties.putAll(configuredSystemProperties);
        }
        if (processParameters.isCDSDumpInvocation()) {
            // JDK management agent startup conflicts with -Xshare:dump on recent JDKs.
            systemProperties.remove(JMX_REMOTE_SYSTEM_PROPERTY);
        }
        systemProperties.forEach((k, v) -> command.add(v == null ? "-D" + k : "-D" + k + "=" + v));

        command.add("-cp");
        command.add(processParameters.getClasspath().stream()
            .map(file -> file.toPath().toAbsolutePath().normalize().toString())
            .reduce((left, right) -> left + File.pathSeparator + right)
            .orElse(""));
        command.add(processParameters.getMainClass());
        command.addAll(processParameters.getArguments());

        Map<String, String> environment = request.environment() == null ? Map.of() : request.environment();
        process = processStarter.start(command, environment);
        if (!process.isAlive()) {
            throw new IllegalStateException("Test resources server process terminated immediately during startup.");
        }
    }

    @Override
    public void waitFor(Duration duration) throws InterruptedException {
        if (process == null) {
            Thread.sleep(duration.toMillis());
            return;
        }
        if (!process.isAlive()) {
            throw new IllegalStateException("Test resources server process terminated during startup wait.");
        }
        Thread.sleep(duration.toMillis());
    }

    static List<String> selfModuleClasspathEntries() {
        try {
            var codeSource = TestResourcesServerFactory.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path location = Path.of(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.exists(location)) {
                    return List.of(location.toString());
                }
            }
        } catch (Exception ignored) {
            // Native launchers do not necessarily expose a protection-domain code source.
        }
        String classpath = System.getProperty("java.class.path", "");
        String command = ProcessHandle.current().info().command().orElse("");
        return selfModuleClasspathEntries(classpath, command);
    }

    static List<String> selfModuleClasspathEntries(String classpath, String commandPath) {
        if (!classpath.isBlank()) {
            List<String> entries = Stream.of(classpath.split(File.pathSeparator))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> Path.of(entry).toAbsolutePath().normalize())
                .filter(TestResourcesServerFactory::isWrapperModuleEntry)
                .map(Path::toString)
                .toList();
            if (!entries.isEmpty()) {
                return entries;
            }
        }
        return discoverExecutableSiblingLibEntries(commandPath);
    }

    private static List<String> discoverExecutableSiblingLibEntries(String commandPath) {
        String command = commandPath == null ? "" : commandPath.trim();
        if (command.isEmpty()) {
            return List.of();
        }
        Path executable = Path.of(command).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        Path parent = executable.getParent();
        if (parent != null) {
            String parentName = parent.getFileName() == null ? "" : parent.getFileName().toString();
            if (("bin".equals(parentName) || "native".equals(parentName)) && parent.getParent() != null) {
                candidates.add(parent.getParent().resolve("lib").toAbsolutePath().normalize());
            }
            candidates.add(parent.resolve("lib").toAbsolutePath().normalize());
        }
        for (Path libDir : candidates) {
            List<String> entries = listWrapperJarEntries(libDir);
            if (!entries.isEmpty()) {
                return entries;
            }
        }
        return List.of();
    }

    private static boolean isWrapperModuleEntry(Path entry) {
        if (entry == null || entry.getFileName() == null) {
            return false;
        }
        String filename = entry.getFileName().toString();
        return filename.startsWith("micronaut-pyronaut-test-resources-server-")
            && filename.endsWith(".jar");
    }

    private static List<String> listWrapperJarEntries(Path libDir) {
        if (!Files.isDirectory(libDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                .filter(TestResourcesServerFactory::isWrapperModuleEntry)
                .sorted()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static String materializeLogbackConfiguration(Path logsDir) throws IOException {
        Files.createDirectories(logsDir);
        try (InputStream stream = PyronautTestResourcesServerMain.class.getClassLoader()
            .getResourceAsStream(LOGBACK_CONFIGURATION_FILE)) {
            if (stream == null) {
                return null;
            }
            Path target = logsDir.resolve(LOGBACK_CONFIGURATION_FILE).toAbsolutePath().normalize();
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        }
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    interface ProcessStarter {
        Process start(List<String> command, Map<String, String> environment) throws IOException;
    }

    private static final class ProcessBuilderStarter implements ProcessStarter {
        private final Path logsDir;

        private ProcessBuilderStarter(Path logsDir) {
            this.logsDir = logsDir;
        }

        @Override
        public Process start(List<String> command, Map<String, String> environment) throws IOException {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().putAll(environment);
            processBuilder.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE));
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                logsDir.resolve(STDIO_LOG_FILE).toFile()
            ));
            return processBuilder.start();
        }
    }
}
