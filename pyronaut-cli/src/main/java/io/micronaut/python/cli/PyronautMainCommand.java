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
package io.micronaut.python.cli;

import io.micronaut.python.cli.commands.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "pyronaut", description = "The Pyronaut CLI", subcommands = {
        PyronautCleanCommand.class,
        PyronautRunCommand.class,
        PyronautInstallCommand.class,
        PyronautNativeCompileCommand.class,
        PyronautTestCommand.class
}, mixinStandardHelpOptions = true)
public class PyronautMainCommand implements Callable<Void> {

    @Spec
    CommandSpec spec;

    @Option(names = "-D")
    public void setSystemProperties(Map<String, String> props) {
        props.forEach((k, v) -> System.setProperty(k, v == null ? "" : v));
    }

    @Option(names = {"--plain"}, description = "Disable TUI and use plain console output")
    boolean plain;

    @Override
    public Void call() throws Exception {
        // Default behavior: `pyronaut` == `pyronaut run`
        new PyronautRunCommand().call();
        return null;
    }

    public static void main(String[] args) {
        var cmd = new CommandLine(new PyronautMainCommand());
        cmd.setExecutionStrategy((ParseResult parseResult) -> {
            if (parseResult.hasMatchedOption("--plain")) {
                CliMode.setPlain(true);
            }
            return new CommandLine.RunLast().execute(parseResult);
        });
        var exitCode = cmd.execute(args);
        System.exit(exitCode);
    }
}
