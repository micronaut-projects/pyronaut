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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

final class RealTestResourcesServerFactory implements ServerFactory {

    private final PyronautTestResourcesServerMain.ServerStartRequest request;
    private final ProcessStarter processStarter;
    private Process process;

    RealTestResourcesServerFactory(PyronautTestResourcesServerMain.ServerStartRequest request) {
        this(request, new ProcessBuilderStarter());
    }

    RealTestResourcesServerFactory(PyronautTestResourcesServerMain.ServerStartRequest request, ProcessStarter processStarter) {
        this.request = request;
        this.processStarter = processStarter;
    }

    @Override
    public void startServer(ServerUtils.ProcessParameters processParameters) throws IOException {
        String javaExecutable = request.javaExecutable() == null || request.javaExecutable().isBlank()
            ? "java"
            : request.javaExecutable();

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.addAll(processParameters.getJvmArguments());
        if (request.debugServer()) {
            command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
        }

        Map<String, String> systemProperties = new LinkedHashMap<>(processParameters.getSystemProperties());
        Map<String, String> configuredSystemProperties = request.systemProperties();
        if (configuredSystemProperties != null) {
            systemProperties.putAll(configuredSystemProperties);
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
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return List.of();
        }
        return Stream.of(classpath.split(File.pathSeparator))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .filter(entry -> entry.contains("micronaut-pyronaut-test-resources-server"))
            .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
            .toList();
    }

    interface ProcessStarter {
        Process start(List<String> command, Map<String, String> environment) throws IOException;
    }

    private static final class ProcessBuilderStarter implements ProcessStarter {
        @Override
        public Process start(List<String> command, Map<String, String> environment) throws IOException {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().putAll(environment);
            processBuilder.inheritIO();
            return processBuilder.start();
        }
    }
}
