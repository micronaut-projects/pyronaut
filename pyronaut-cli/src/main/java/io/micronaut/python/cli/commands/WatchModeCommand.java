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
import io.micronaut.python.cli.util.PythonMavenRepository;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

abstract class WatchModeCommand extends BaseSourceCommand {
 
    private PyronautWatcherFactory createWatcherFactory(
        Mode initial,
        Path rootDirectory,
        List<Path> runSources,
        List<Path> testSources,
        PythonMavenRepository annotationProcDependencies,
        PythonMavenRepository runDependencies,
        PythonMavenRepository testDependencies,
        String[] parameters
    ) {
        return (controller, out) -> new PyronautFileWatcher(
            rootDirectory,
            runSources,
            testSources,
            annotationProcDependencies,
            runDependencies,
            testDependencies,
            parameters,
            initial,
            controller,
            out
        );
    }
 
    protected Integer runShared(Mode initial, String[] parameters) throws Exception {
        var runDependencies = PythonMavenRepository.inspect(dependenciesDirForScope("compile"));
        var testDependencies = PythonMavenRepository.inspect(dependenciesDirForScope("test"));
        if (runDependencies.isEmpty() || testDependencies.isEmpty()) {
            System.err.println("Pyronaut dependencies not found. Did you run `pyronaut install`?");
            return -1;
        }
        var annotationProcDependencies = PythonMavenRepository.inspect(dependenciesDirForScope("annotationProcessor"));
        var rootDirectory = resolveRootDir();

        var eventPipeIn = new PipedInputStream(65536);
        var eventPipeOut = new PipedOutputStream(eventPipeIn);
        var eventIn = new DataInputStream(eventPipeIn);
        var eventOut = new DataOutputStream(eventPipeOut);

        var runSources = List.of(rootDirectory.resolve("src"));
        var testSources = Stream.of("src", "tests").map(rootDirectory::resolve).toList();

        var runFactory = createWatcherFactory(Mode.RUN, rootDirectory, runSources, testSources, annotationProcDependencies, runDependencies, testDependencies, parameters);
        var testFactory = createWatcherFactory(Mode.TEST, rootDirectory, runSources, testSources, annotationProcDependencies, runDependencies, testDependencies, parameters);

        return runUnified(runFactory, testFactory, initial, eventIn, eventOut);
    }
}
