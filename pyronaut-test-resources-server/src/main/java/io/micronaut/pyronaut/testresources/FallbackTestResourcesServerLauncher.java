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

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FallbackTestResourcesServerLauncher {
    private static final String LOGS_DIR_SYSTEM_PROPERTY = "pyronaut.test-resources.logs-dir";
    private static final String LOGBACK_CONFIGURATION_SYSTEM_PROPERTY = "logback.configurationFile";
    private static final String STDIO_LOG_FILE = "launcher-stdio.log";
    private static final File NULL_DEVICE = new File(isWindows() ? "NUL" : "/dev/null");

    private FallbackTestResourcesServerLauncher() {
    }

    static void launch(PyronautTestResourcesServerMain.ServerStartRequest request, int port) throws IOException {
        String javaExecutable = request.javaExecutable() == null || request.javaExecutable().isBlank() ? "java" : request.javaExecutable();
        String classpath = System.getProperty("java.class.path", "");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        if (request.debugServer()) {
            command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005");
        }
        java.nio.file.Files.createDirectories(request.logsDir());
        command.add("-D" + LOGS_DIR_SYSTEM_PROPERTY + "=" + request.logsDir().toAbsolutePath().normalize());
        String logbackConfiguration = PyronautTestResourcesServerMain.resolveLogbackConfigurationLocation();
        if (logbackConfiguration != null && !logbackConfiguration.isBlank()) {
            command.add("-D" + LOGBACK_CONFIGURATION_SYSTEM_PROPERTY + "=" + logbackConfiguration);
        }
        request.systemProperties().forEach((k, v) -> command.add("-D" + k + "=" + v));
        command.add("-cp");
        command.add(classpath);
        command.add(FallbackTestResourcesServerMain.class.getName());
        command.add("--port=" + port);
        command.add("--port-file=" + request.portFile().toAbsolutePath());
        if (request.accessToken() != null && !request.accessToken().isBlank()) {
            command.add("--access-token=" + request.accessToken());
        }
        if (request.idleTimeoutMinutes() != null) {
            command.add("--idle-timeout-minutes=" + request.idleTimeoutMinutes());
        }
        if (request.cdsDir() != null) {
            Path cdsDir = request.cdsDir();
            command.add("-Dpyronaut.testresources.cds-dir=" + cdsDir.toAbsolutePath());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(request.environment());
        processBuilder.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE));
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(
            request.logsDir().resolve(STDIO_LOG_FILE).toFile()
        ));
        processBuilder.start();
    }
}
