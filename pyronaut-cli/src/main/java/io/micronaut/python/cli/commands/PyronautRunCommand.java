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
import io.micronaut.python.cli.util.PythonMavenRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "run", description = "Runs a Pyronaut application", mixinStandardHelpOptions = true)
public class PyronautRunCommand extends BaseSourceCommand {
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
        var compileDependencies = PythonMavenRepository.inspect(dependenciesDirForScope(scope()));
        if (compileDependencies.isEmpty()) {
            System.err.println("Pyronaut dependencies not found. Did you run `pyronaut install`?");
            return -1;
        }
        var annotationProcDependencies = PythonMavenRepository.inspect(dependenciesDirForScope(ANNOTATION_PROCESSOR_SCOPE));
        var rootDirectory = resolveRootDir();
        var watcher = new PyronautFileWatcher(
                rootDirectory,
                getSourceDirectories().stream().map(rootDirectory::resolve).toList(),
                annotationProcDependencies,
                compileDependencies,
                parameters,
                getApplicationManagerClassName());
        var watcherThread = new Thread(watcher);
        watcherThread.start();

        // UI using TamboUI Toolkit
        var uiThread = new Thread(() -> {
            try (var uiRunner = dev.tamboui.toolkit.app.ToolkitRunner.create()) {
                uiRunner.run(() ->
                    dev.tamboui.toolkit.Toolkit.panel("Pyronaut Run",
                        dev.tamboui.toolkit.Toolkit.text("Application is running..."))
                );
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });
        uiThread.start();

        // Handle shutdown gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop));

        try {
            watcherThread.join();
        } catch (InterruptedException e) {
            watcher.stop();
            Thread.currentThread().interrupt();
        }

        // Ensure UI thread stops after the application finishes
        uiThread.interrupt();

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
