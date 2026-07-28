/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.pyronaut.install;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes IntelliJ shell run configurations that delegate Java execution to Pyronaut.
 */
final class IntellijRunConfigurationWriter {
    static final String RUN_FILE = "Pyronaut_Run_Direct_Sources.xml";
    static final String TEST_FILE = "Pyronaut_Test_Direct_Sources.xml";
    static final String RUN_NAME = "Pyronaut: Run Direct Sources";
    static final String TEST_NAME = "Pyronaut: Test Direct Sources";

    void ensureWritten(Path projectDir, List<Path> sourceFiles) throws Exception {
        Path configurationsDir = projectDir.resolve(IntellijDirectSourceWriter.IDEA_DIR).resolve("runConfigurations");
        Files.createDirectories(configurationsDir);
        DirectSourceLaunchCommand.Commands commands = DirectSourceLaunchCommand.build(projectDir, sourceFiles);
        write(
            configurationsDir.resolve(RUN_FILE),
            RUN_NAME,
            commands.development()
        );
        Path testFile = configurationsDir.resolve(TEST_FILE);
        if (commands.test() == null) {
            Files.deleteIfExists(testFile);
        } else {
            write(testFile, TEST_NAME, commands.test());
        }
    }

    private static void write(Path file, String name, String command) throws Exception {
        Document document = IntellijDirectSourceWriter.newDocument();
        Element component = document.createElement("component");
        component.setAttribute("name", "ProjectRunConfigurationManager");
        document.appendChild(component);
        Element configuration = document.createElement("configuration");
        configuration.setAttribute("default", "false");
        configuration.setAttribute("name", name);
        configuration.setAttribute("type", "ShConfigurationType");
        component.appendChild(configuration);
        option(document, configuration, "SCRIPT_TEXT", command);
        option(document, configuration, "INDEPENDENT_SCRIPT_PATH", "true");
        option(document, configuration, "SCRIPT_PATH", "");
        option(document, configuration, "SCRIPT_OPTIONS", "");
        option(document, configuration, "INDEPENDENT_SCRIPT_WORKING_DIRECTORY", "true");
        option(document, configuration, "SCRIPT_WORKING_DIRECTORY", "$PROJECT_DIR$");
        option(document, configuration, "INDEPENDENT_INTERPRETER_PATH", "true");
        option(document, configuration, "INTERPRETER_PATH", "");
        option(document, configuration, "INTERPRETER_OPTIONS", "");
        option(document, configuration, "EXECUTE_IN_TERMINAL", "true");
        option(document, configuration, "EXECUTE_SCRIPT_FILE", "false");
        configuration.appendChild(document.createElement("envs"));
        Element method = document.createElement("method");
        method.setAttribute("v", "2");
        configuration.appendChild(method);
        IntellijDirectSourceWriter.writeIfChanged(file, document);
    }

    private static void option(Document document, Element configuration, String name, String value) {
        Element option = document.createElement("option");
        option.setAttribute("name", name);
        option.setAttribute("value", value);
        configuration.appendChild(option);
    }
}
