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

        PipedInputStream eventPipeIn = new PipedInputStream(65536);
        PipedOutputStream eventPipeOut = new PipedOutputStream(eventPipeIn);
        DataInputStream eventIn = new DataInputStream(eventPipeIn);
        DataOutputStream eventOut = new DataOutputStream(eventPipeOut);

        PyronautWatcherFactory runFactory = (controller, out) -> new PyronautFileWatcher(
            rootDirectory,
            getSourceDirectories().stream().map(rootDirectory::resolve).collect(Collectors.toList()),
            annotationProcDependencies,
            compileDependencies,
            parameters,
            getApplicationManagerClassName(),
            controller,
            out
        );

        PyronautWatcherFactory testFactory = (controller, out) -> {
            var testDeps = PythonMavenRepository.inspect(dependenciesDirForScope("test"));
            if (testDeps.isEmpty()) {
                controller.notify("Pyronaut test dependencies not found. Did you run `pyronaut install --scope test`?", UiModel.Severity.WARNING);
                return null;
            }
            var testSources = List.of("src", "tests").stream().map(rootDirectory::resolve).toList();
            return new PyronautFileWatcher(
                    rootDirectory,
                    testSources,
                    annotationProcDependencies,
                    testDeps,
                    parameters,
                    "io.micronaut.python.cli.TestApplicationManager",
                    controller,
                    out
            );
        };

        return runUnified(runFactory, testFactory, Mode.RUN, eventIn, eventOut);
    }

    protected String getApplicationManagerClassName() {
        return "io.micronaut.python.cli.DefaultApplicationManager";
    }
}
