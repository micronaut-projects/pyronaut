/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package io.micronaut.pyronaut.testresources;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class FallbackTestResourcesServerLauncher {
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
        processBuilder.inheritIO();
        processBuilder.start();
    }
}
