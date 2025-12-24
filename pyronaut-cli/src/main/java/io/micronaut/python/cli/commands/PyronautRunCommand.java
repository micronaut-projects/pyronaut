/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.python.cli.commands;

import io.micronaut.python.cli.PyronautFileWatcher;
import io.micronaut.python.cli.ui.PyronautTui;
import io.micronaut.python.cli.ui.StreamsCapture;
import io.micronaut.python.cli.util.PythonMavenRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.stream.Collectors;

@Command(name = "run", description = "Runs a Pyronaut application", mixinStandardHelpOptions = true)
public class PyronautRunCommand extends BaseSourceCommand {
    static {
        try {
            Class.forName("io.micronaut.python.cli.ui.StreamsCapture")
                .getMethod("installGlobal")
                .invoke(null);
        } catch (Throwable t) {
            // fail fast on capture install problem
            System.err.println("Failed to install StreamsCapture globally: " + t);
        }
    }
    private static final String ANNOTATION_PROCESSOR_SCOPE = "annotationProcessor";
    private static final List<String> SOURCE_DIRECTORIES = List.of("src");

    @Parameters(index = "0..*", description = "Application parameters")
    private String[] parameters;

    protected String scope() {
        return "compile";
    }

    protected List<String> getSourceDirectories() {
        return SOURCE_DIRECTORIES;
    }

    @Override
    public Integer call() throws Exception {
        // Install stream capture very early to prevent any early stdout/err output
        StreamsCapture.installGlobal();

        var compileDependencies = PythonMavenRepository.inspect(dependenciesDirForScope(scope()));
        if (compileDependencies.isEmpty()) {
            System.err.println("Pyronaut dependencies not found. Did you run `pyronaut install`?");
            return -1;
        }
        var annotationProcDependencies = PythonMavenRepository.inspect(dependenciesDirForScope(ANNOTATION_PROCESSOR_SCOPE));
        var rootDirectory = resolveRootDir();

        // Create in-process event bridge using Piped streams
        PipedInputStream eventPipeIn = new PipedInputStream(65536);
        PipedOutputStream eventPipeOut = new PipedOutputStream(eventPipeIn);
        DataInputStream eventIn = new DataInputStream(eventPipeIn);
        DataOutputStream eventOut = new DataOutputStream(eventPipeOut);

        var tui = new PyronautTui();
        tui.setEventInputStream(eventIn);
        var controller = tui.getController();
        var watcher = new PyronautFileWatcher(
                rootDirectory,
                getSourceDirectories().stream().map(rootDirectory::resolve).collect(Collectors.toList()),
                annotationProcDependencies,
                compileDependencies,
                parameters,
                getApplicationManagerClassName(),
                controller,
                eventOut); // Use DataOutputStream for event protocol


        var watcherThread = new Thread(watcher, "PyronautWatcher");
        var tuiThread = new Thread(() -> {
            try {
                tui.run();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, "PyronautTUI");
        watcherThread.start();
        tuiThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(tui::stop));

        try {
            watcherThread.join();
            tuiThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tui.stop();
        return 0;
    }

    /**
     * Returns the name of the application manager that is responsible
     * for starting the application and/or tests.
     *
     * @return the class name
     */
    protected String getApplicationManagerClassName() {
        return "io.micronaut.python.cli.DefaultApplicationManager";
    }
}
