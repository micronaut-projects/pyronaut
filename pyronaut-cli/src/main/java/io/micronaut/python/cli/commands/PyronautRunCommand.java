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

@Command(name = "run", description = "Runs a Pyronaut application", mixinStandardHelpOptions = true)
public class PyronautRunCommand extends BaseSourceCommand {
    private static final String ANNOTATION_PROCESSOR_SCOPE = "annotationProcessor";

    @Parameters(index = "0..*", description = "Application parameters")
    private String[] parameters;

    protected String scope() {
        return "compile";
    }

    @Override
    public Integer call() throws Exception {
        var sourceDirectory = resolveRootDir();
        var compileDependencies = PythonMavenRepository.inspect(dependenciesDirForScope(scope()));
        if (compileDependencies.isEmpty()) {
            System.err.println("Pyronaut dependencies not found. Did you run `pyronaut install`?");
            return -1;
        }
        var annotationProcDependencies = PythonMavenRepository.inspect(dependenciesDirForScope(ANNOTATION_PROCESSOR_SCOPE));
        var watcher = new PyronautFileWatcher(sourceDirectory,
            annotationProcDependencies,
            compileDependencies,
            parameters,
                getApplicationManagerClassName());
        var watcherThread = new Thread(watcher);
        watcherThread.start();

        // Handle shutdown gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(watcher::stop));

        try {
            watcherThread.join();
        } catch (InterruptedException e) {
            watcher.stop();
            Thread.currentThread().interrupt();
        }

        return 0;
    }

    /**
     * Returns the name of the application manager that is responsible
     * for starting the application and/or tests.
     * @return the class name
     */
    protected String getApplicationManagerClassName() {
        return "io.micronaut.python.cli.DefaultApplicationManager";
    }
}
