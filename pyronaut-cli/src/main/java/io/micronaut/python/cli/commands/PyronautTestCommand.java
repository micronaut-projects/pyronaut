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
import io.micronaut.python.cli.PyronautWatcherFactory;
import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.Mode;
import io.micronaut.python.cli.ui.UiModel;
import io.micronaut.python.cli.util.PythonMavenRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.stream.Collectors;

@Command(name = "test", description = "Executes tests", mixinStandardHelpOptions = true)
public class PyronautTestCommand extends BaseSourceCommand {
    private static final String ANNOTATION_PROCESSOR_SCOPE = "annotationProcessor";
    private static final List<String> SOURCE_DIRECTORIES = List.of("src", "tests");

    @Parameters(index = "0..*", description = "Application parameters")
    private String[] parameters;

    protected String scope() {
        return "test";
    }

    protected List<String> getSourceDirectories() {
        return SOURCE_DIRECTORIES;
    }

    protected String getApplicationManagerClassName() {
        return "io.micronaut.python.cli.TestApplicationManager";
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

        PipedInputStream eventPipeIn = new PipedInputStream(65536);
        PipedOutputStream eventPipeOut = new PipedOutputStream(eventPipeIn);
        DataInputStream eventIn = new DataInputStream(eventPipeIn);
        DataOutputStream eventOut = new DataOutputStream(eventPipeOut);

        PyronautWatcherFactory testFactory = (controller, out) -> new PyronautFileWatcher(
            rootDirectory,
            getSourceDirectories().stream().map(rootDirectory::resolve).collect(Collectors.toList()),
            annotationProcDependencies,
            compileDependencies,
            parameters,
            getApplicationManagerClassName(),
            controller,
            out
        );

        PyronautWatcherFactory runFactory = (controller, out) -> {
            var runDeps = PythonMavenRepository.inspect(dependenciesDirForScope("compile"));
            if (runDeps.isEmpty()) {
                controller.notify("Pyronaut compile dependencies not found. Did you run `pyronaut install`?", UiModel.Severity.WARNING);
                return null;
            }
            var runSources = List.of("src").stream().map(rootDirectory::resolve).toList();
            return new PyronautFileWatcher(
                    rootDirectory,
                    runSources,
                    annotationProcDependencies,
                    runDeps,
                    parameters,
                    "io.micronaut.python.cli.DefaultApplicationManager",
                    controller,
                    out
            );
        };

        return runUnified(runFactory, testFactory, Mode.TEST, eventIn, eventOut);
    }
}
