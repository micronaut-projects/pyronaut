/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.python.cli.commands;

import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "test", description = "Executes tests", mixinStandardHelpOptions = true)
public class PyronautTestCommand extends PyronautRunCommand {
    private static final List<String> SOURCE_DIRECTORIES = List.of("src", "tests");

    @Override
    protected String scope() {
        return "test";
    }

    @Override
    protected List<String> getSourceDirectories() {
        return SOURCE_DIRECTORIES;
    }

    @Override
    protected String getApplicationManagerClassName() {
        return "io.micronaut.python.cli.TestApplicationManager";
    }
}
