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

import java.util.List;

@Command(name = "run", description = "Runs a Pyronaut application", mixinStandardHelpOptions = true)
public class PyronautRunCommand extends WatchModeCommand {
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
        return runShared(Mode.RUN, parameters);
    }


}
