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
package io.micronaut.pyronaut.projectgen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.projectgen.core.feature.Feature;
import io.micronaut.projectgen.core.generator.GeneratorContext;
import io.micronaut.projectgen.core.generator.ModuleContext;
import io.micronaut.projectgen.core.template.StringTemplate;
import jakarta.inject.Singleton;

@Internal
@Singleton
class PyronautGeneratedFiles implements Feature {
    @Override
    public String getName() {
        return "pyronaut-generated-files";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        String pythonModule = generatorContext.getOptions().packageName();
        String modulePath = pythonModule.replace('.', '/');
        module.addTemplate("src/" + modulePath + "/controllers.py", new StringTemplate("src/" + modulePath + "/controllers.py", controller()));
        module.addTemplate("src/main.py", new StringTemplate("src/main.py", main(pythonModule)));
        module.addTemplate("tests/test_" + pythonModule.replace('.', '_') + ".py",
            new StringTemplate("tests/test_" + pythonModule.replace('.', '_') + ".py", test()));
        module.addTemplate(".gitignore", new StringTemplate(".gitignore", gitignore()));
    }

    private static String controller() {
        return """
            from micronaut.http.annotation import Get


            @Get(value="/", produces="text/plain")
            def index() -> str:
                return "Hello World"
            """;
    }

    private static String main(String pythonModule) {
        return """
            from logback.config import dictConfig

            import %s.controllers


            LOGGING = {
                "version": 1,
                "disable_existing_loggers": False,
                "formatters": {
                    "color": {
                        "format": "%%cyan(%%d{HH:mm:ss.SSS}) %%gray([%%thread]) %%highlight(%%-5level) %%magenta(%%logger{36}) - %%msg%%n"
                    }
                },
                "handlers": {
                    "console": {
                        "class": "ch.qos.logback.core.ConsoleAppender",
                        "level": "INFO",
                        "formatter": "color"
                    }
                },
                "root": {
                    "level": "INFO",
                    "handlers": ["console"]
                }
            }

            dictConfig(LOGGING)
            """.formatted(pythonModule);
    }

    private static String test() {
        return """
            from typing import Any

            import pytest

            from micronaut.runtime.server import EmbeddedServer
            from pyronaut import requests
            from pyronaut.test import MicronautTest, micronaut_test_fixture


            @pytest.fixture
            def application_context(request: Any) -> Any:
                fixture = micronaut_test_fixture(
                    request,
                    MicronautTest(environments=["test"], transactional=False)
                )
                yield fixture
                fixture.stop()


            @pytest.fixture
            def client(application_context: Any) -> requests.Session:
                session = requests.with_context(application_context)
                yield session
                session.close()


            def test_application_starts(application_context: Any) -> None:
                server = application_context[EmbeddedServer]
                assert server.isRunning()


            def test_index(client: requests.Session) -> None:
                response = client.get("/")

                assert response.status_code == 200
                assert response.text == "Hello World"
                assert response.headers["Content-Type"].startswith("text/plain")
            """;
    }

    private static String gitignore() {
        return """
            __pyronaut__/
            *.py[cod]
            __pycache__/
            .pytest_cache/
            .venv/
            venv/
            build/
            dist/
            *.egg-info/
            """;
    }
}
