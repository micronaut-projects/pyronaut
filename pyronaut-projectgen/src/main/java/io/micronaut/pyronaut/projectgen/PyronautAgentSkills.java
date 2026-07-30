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
class PyronautAgentSkills implements Feature {
    @Override
    public String getName() {
        return "pyronaut-agent-skills";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        ModuleContext module = generatorContext.getRootModule();
        module.addTemplate(".agents/skills/pyronaut-project/SKILL.md",
            new StringTemplate(".agents/skills/pyronaut-project/SKILL.md", pyronautProjectSkill()));
        module.addTemplate(".agents/skills/pyronaut-cli/SKILL.md",
            new StringTemplate(".agents/skills/pyronaut-cli/SKILL.md", pyronautCliSkill()));
        module.addTemplate(".agents/skills/pyronaut-coding/SKILL.md",
            new StringTemplate(".agents/skills/pyronaut-coding/SKILL.md", pyronautCodingSkill()));
    }

    private static String pyronautProjectSkill() {
        return """
            ---
            name: pyronaut-project
            description: Understand and modify Pyronaut project structure, pyproject.toml metadata, dependency scopes, resources, generated files, and IDE schema/stub support.
            ---

            # Pyronaut Project

            Use this skill when working in a Python project generated for Pyronaut, especially when editing `pyproject.toml`, changing project layout, adding resources, or interpreting generated files under `__pyronaut__/`.

            ## Project Shape

            A Pyronaut project is identified by `pyproject.toml` with `[tool.pyronaut]` sections. The normal generated layout is:

            - `src/`: application Python sources.
            - `tests/`: pytest tests and test support code.
            - `config/`: application resources, including `application.toml`.
            - `tests-config/`: test-only resources.
            - `__pyronaut__/`: generated dependency manifests, processed classes, IDE stubs, schemas, reports, and local caches. Do not edit this directory by hand.
            - `.micronaut/test-resources/`: test-resources client state when a reusable server is active.

            Pyronaut reads directory names from `[tool.pyronaut.sources]` in `pyproject.toml`. Directory values are relative to the project root. Keep generated projects on the default layout unless the user asks to reorganize them.

            ## pyproject.toml

            Keep the file structured around these sections:

            - `[project]`: Python package name, version, and metadata.
            - `[build-system]`: Python build backend metadata.
            - `[tool.pyronaut]`: repositories, core/platform version keys, and top-level Pyronaut options.
            - `[tool.pyronaut.core]`: Micronaut Core version override.
            - `[tool.pyronaut.platform]`: Micronaut platform version override.
            - `[tool.pyronaut.sources]`: source and resource directories.
            - `[tool.pyronaut.processor]`: processing mode.
            - `[tool.pyronaut.ide-stubs]`: Java-backed Python stub generation.
            - `[tool.pyronaut.validation]`: lifecycle configuration validation.
            - `[tool.pyronaut.test-resources]`: test resources support when enabled.
            - `[tool.pyronaut.dependencies]`: runtime, development-runtime, build, and test dependency coordinates.

            Prefer kebab-case option names, such as `python-test`, `test-resources`, and `additional-modules`.
            Use split managed versions only: `[tool.pyronaut.core] version = ...` and `[tool.pyronaut.platform] version = ...`. Do not add the legacy `tool.pyronaut.version` key.

            ## Dependencies

            Add JVM dependency coordinates in `[tool.pyronaut.dependencies]`:

            - `runtime`: application runtime libraries, such as HTTP server, serde, data, security, cloud, messaging, views, and logging modules.
            - `development-runtime`: dependencies available only to `pyronaut run` and development runtime launches.
            - `build`: annotation processors and compile-time support, such as `micronaut-serde-processor`, `micronaut-security-processor`, or `micronaut-data-processor`.
            - `test`: pytest integration, test clients, test-resources modules, and test-only libraries.

            Use managed coordinates without versions when the Micronaut platform manages them. Add explicit versions only for unmanaged third-party artifacts.

            Run `pyronaut install` after changing dependencies, repositories, source layout, or IDE stub settings. It refreshes scoped manifests and local schema/stub support.

            ## Configuration

            Application configuration belongs in `config/application.toml`. Test-only configuration belongs in `tests-config/` or test environment files. Use TOML tables rather than flattened dotted keys when practical:

            ```toml
            [micronaut.application]
            name = "demo"
            ```

            Avoid `bootstrap.properties` and `bootstrap.toml`; Pyronaut projects should use normal application configuration and exclude features that require bootstrap configuration.

            Do not migrate distributed-configuration, secrets-manager, or cloud-secret patterns into normal application configuration. Those guides require bootstrap-time configuration support and should stay out of generated projects until Pyronaut supports `bootstrap.toml`.

            ## Generated Files

            `pyronaut install` creates local JSON schemas and schema directives for `pyproject.toml` and `application.toml` when those files exist. It also writes IDE stubs under `__pyronaut__/ide-stubs` so imports such as `micronaut.http.annotation` and `jakarta.inject` resolve in editors. Treat those files as generated output and rerun `pyronaut install` instead of editing them.
            """;
    }

    private static String pyronautCliSkill() {
        return """
            ---
            name: pyronaut-cli
            description: Run, test, validate, install, process, and troubleshoot Pyronaut projects with the pyronaut CLI and its delegated toolchain.
            ---

            # Pyronaut CLI

            Use this skill when running Pyronaut commands, validating configuration, debugging generated manifests, or working with test resources.

            ## Command Model

            The `pyronaut` command is a Python orchestrator. It delegates to focused tools for install, processing, run, test, native build, config validation, and test-resources server management.

            From the project root, `--project-dir .` is unnecessary because the CLI defaults to the current directory.

            ## Common Workflow

            Use this sequence after creating or changing a project:

            ```bash
            pyronaut install
            pyronaut validate-config
            pyronaut process
            pyronaut test
            ```

            Use `pyronaut run` to launch the application.

            ## Commands

            - `pyronaut install`: resolves dependencies, writes scoped manifests under `__pyronaut__/`, materializes TOML schemas, and generates IDE stubs.
            - `pyronaut process`: processes `src/` and `tests/` sources into `__pyronaut__/classes` and `__pyronaut__/test-classes`.
            - `pyronaut run`: validates config for the `run` scenario, processes main sources, and starts the production application without automatic test-resources orchestration. Run `pyronaut install` explicitly after dependency or editor-support changes.
            - `pyronaut test`: validates config for the `test` scenario, processes test sources, manages test resources when enabled, runs pytest through Pyronaut, and writes reports under `__pyronaut__/reports/tests`. Run `pyronaut install` explicitly after dependency or editor-support changes.
            - `pyronaut validate-config --scenario dev|run|test|production`: validates lifecycle configuration for a specific scenario and writes reports under `__pyronaut__/reports/config-validation/<scenario>`.
            - `pyronaut test-resources-server start|status|stop`: manages a reusable test-resources server. `dev` and `test` can also start an owned server automatically when test resources are enabled.

            `pyronaut run` and `pyronaut test` validate by default. Use `--no-validate` only for short diagnostics where validation itself is the blocker.

            ## Troubleshooting

            - If imports from Micronaut or Jakarta packages fail in the editor, run `pyronaut install` so `__pyronaut__/ide-stubs` and editor settings are refreshed.
            - If dependency changes are not reflected, run `pyronaut install --refresh`.
            - If cache state looks corrupt, run `pyronaut install --no-cache`.
            - For delegation diagnostics, set `PYRONAUT_TRACE_DELEGATION=true`.
            - Do not edit generated manifests, processed classes, schemas, or reports under `__pyronaut__/`; change source files or `pyproject.toml` and rerun the relevant command.
            """;
    }

    private static String pyronautCodingSkill() {
        return """
            ---
            name: pyronaut-coding
            description: Write idiomatic Pyronaut application code with typed Python, dataclasses, classless controllers, Micronaut annotations, Java-backed imports, dependency injection, pytest tests, and reflection-free JSON/data patterns.
            ---

            # Pyronaut Coding

            Use this skill when adding or changing Python application code in a Pyronaut project.

            ## Source Style

            - Write typed Python. Add parameter and return type annotations for route handlers, services, clients, repositories, DTOs, entities, configuration objects, and tests.
            - Prefer Python dataclasses for structured data: request bodies, response models, DTOs, configuration properties, JSON schema inputs, and data entities.
            - Keep required values non-optional. Use `None` and `| None` only for values that are genuinely optional or framework-populated, such as generated IDs and timestamps.
            - Do not use direct `Nullable` or `NonNull` annotations in Python code. Express optionality with Python type syntax and leave required values non-optional.
            - Use `@Serdeable` on dataclasses that cross JSON or HTTP boundaries. Add `@JsonSchema` when schema generation is part of the feature.
            - Use `@MappedEntity` plus `typing.Annotated` metadata for Micronaut Data entities.
            - Keep module names snake_case under `src/<package>/`.
            - Do not copy JavaBean getters and setters into Python. If explicit accessors are useful, use idiomatic `@property`.

            ## Controllers And Beans

            Prefer classless route modules for simple HTTP routes:

            ```python
            from typing import Annotated

            from jakarta.inject import Inject
            from micronaut.http.annotation import Body, Get, Post

            from .services import MessageService, Person

            message_service: Annotated[MessageService, Inject]

            @Get(value="/hello/{name}", produces="application/json")
            def hello(name: str) -> dict[str, str]:
                return {"message": message_service.say_hello(name)}

            @Post(value="/hello")
            def create(person: Annotated[Person, Body]) -> Person:
                return person
            ```

            Move business logic into injected services. Use classes when the Micronaut feature requires a type, such as clients, filters, repositories, configuration properties, MCP tools, or framework interfaces.

            Constructor injection is preferred for class beans. Module-level injection with `Annotated[Type, Inject]` is idiomatic for classless route modules.

            Prefer annotation-based HTTP filters: use `@ServerFilter` or `@ClientFilter` on the bean with `@RequestFilter` or `@ResponseFilter` methods. Avoid subclassing `HttpServerFilter` or `HttpClientFilter` unless method-style filters cannot represent the behavior. Add `@Blocking` when a filter performs blocking work.

            ## Imports

            Import Java-backed Micronaut APIs through the generated Python package names:

            - Use `from micronaut.http.annotation import Get`, not `from io.micronaut.http.annotation import Get`.
            - Use `from micronaut.serde.annotation import Serdeable`.
            - Use `from jakarta.inject import Singleton` or `Inject`.
            - Use `import java` and `java.type("fully.qualified.JavaType")` when a Java class is not exposed as a Python stub or needs an exact JVM type.

            The Java `io.micronaut` package is exposed as the Python package `micronaut` because Python already has a standard `io` module. When a Java package segment conflicts with a Python keyword, use the generated trailing-underscore package segment, such as `from micronaut.core.async_.annotation import SingleResult`.

            ## Dependencies And Unsupported Patterns

            Add Micronaut and Java dependencies in `pyproject.toml`, not Gradle or Maven build files. Put annotation processors in the `build` dependency scope.

            When source code uses `@Serdeable` or other Micronaut Serialization annotations, ensure `micronaut-serde-processor` is in the `build` scope, not only available at runtime.

            Avoid Java reflection-dependent features in Pyronaut code. In particular, do not use `micronaut-jackson-databind`, `hibernate-jpa`, `data-jpa`, or `hibernate-validator` as implementation shortcuts. Prefer Micronaut Serialization, typed dataclasses, compile-time data/introspection support, and Pyronaut-compatible validation.

            Do not weaken typed dataclasses into untyped `dict` or `Map` workarounds just to make a feature pass.

            ## Tests

            Write normal pytest tests under `tests/`. Use `pyronaut.test` fixtures when a Micronaut application context is needed, and use the Pyronaut `requests` integration for HTTP requests when available.

            For tests with a Micronaut context, prefer a context-bound client fixture:

            ```python
            import pytest
            from pyronaut import requests

            @pytest.fixture
            def client(my_context):
                return requests.with_context(my_context)
            ```

            Avoid manually deriving URLs from `EmbeddedServer.getPort()` in new tests.

            ## Logging

            Prefer Python logging configuration in `src/main.py` backed by `io.micronaut.pyronaut:micronaut-pyronaut-logback`. Do not add guide-specific `logback.xml` files unless the Pyronaut logging module cannot represent the required setup.

            Run:

            ```bash
            pyronaut test
            ```

            For configuration-sensitive changes, also run:

            ```bash
            pyronaut validate-config --scenario test
            ```
            """;
    }
}
