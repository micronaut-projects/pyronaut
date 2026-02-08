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

import io.micronaut.python.cli.ui.Mode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * CLI command that runs the application in test/watch mode.
 *
 * <p>Starts the watch-mode test runner which monitors sources and re-runs tests on changes.</p>
 */
@Command(name = "test", description = "Executes tests", mixinStandardHelpOptions = true)
public final class PyronautTestCommand extends WatchModeCommand {

    @Parameters(index = "0..*", description = "Application parameters")
    private String[] parameters;

    /**
     * Execute the test command in watch mode.
     *
     * @return exit code returned by the shared run routine
     * @throws Exception on errors while preparing or running tests
     */
    @Override
    public Integer call() throws Exception {
        return runShared(Mode.TEST, parameters);
    }
}
