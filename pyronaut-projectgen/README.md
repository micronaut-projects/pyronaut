# Pyronaut Project Generator

`micronaut-pyronaut-projectgen` provides the generator used by `pyronaut create`.

## CLI Usage

```bash
pyronaut create demo
pyronaut create demo --features data-jdbc,mysql,json-schema,test-resources
pyronaut create --list-features
```

The command writes to `<cwd>/<name>` by default. Use `--output <dir>` to choose a parent directory, or `--inplace` to write directly into the selected output directory.

```bash
pyronaut create demo --output /tmp/apps
pyronaut create demo --inplace --output /tmp/demo
```

Useful options:

- `--features f1,f2`: add Pyronaut-compatible starter features.
- `--package <module>`: choose the Python package/module name.
- `--version <version>`: set `[project].version`.
- `--micronaut-version <version>`: override the generated `tool.pyronaut.platform.version`.
- `--repository <repo>`: override generated repositories. Values may be `mavenCentral`, `mavenLocal`, URLs, or local paths.

## Generated Layout

```text
pyproject.toml
.gitignore
config/application.toml
src/main.py
src/<module>/controller.py
tests/test_<module>.py
tests-config/application-test.toml
.agents/skills/pyronaut-project/SKILL.md
.agents/skills/pyronaut-cli/SKILL.md
.agents/skills/pyronaut-coding/SKILL.md
```

The default app includes a simple HTTP controller, Logback-backed Python logging setup, a pytest/Micronaut test fixture, and local Codex-style agent skills that describe the generated project layout, CLI workflow, and Pyronaut coding patterns.

## Dependency And Configuration Contributions

Pyronaut reuses compatible Micronaut ProjectGen starter features when possible. Upstream
features often describe their dependency and configuration changes as OpenRewrite recipes;
Pyronaut translates those recipe contributions into `pyproject.toml` dependency arrays and
TOML application configuration instead of generating Gradle or Maven build files.

- `compile` and `runtime` dependencies render to `tool.pyronaut.dependencies.runtime`.
- `annotationProcessor` and `testAnnotationProcessor` dependencies render to `tool.pyronaut.dependencies.build`.
- `test` dependencies render to `tool.pyronaut.dependencies.test`.
- Main configuration renders to `config/application.toml`.
- Test configuration renders to `tests-config/application-test.toml`.
- Other environment configuration renders to `config/application-<env>.toml`.

The default repository list is `["mavenCentral"]`. Snapshot Micronaut versions add `mavenLocal` first so local Pyronaut artifacts can be resolved during development.

## Feature Compatibility

Visible features are limited to behavior that makes sense for Python projects. Current compatible features include:

- `http-server-netty`
- `serde-jackson`
- `pyronaut-logback`
- `pyronaut-pytest`
- `data-jdbc`
- `mysql`
- `json-schema`
- `test-resources`

`data-jdbc`, `mysql`, `json-schema`, and the underlying serialization/server features come from
`micronaut-projectgen-micronaut`; Pyronaut keeps local features only for Pyronaut-specific runtime,
test, logging, and pyproject-only behavior. Build-tool, JVM-language, generated-source, CI/IaC,
JVM app-type, and Java reflection-dependent features are intentionally hidden. That includes
`jackson-databind`, `data-jpa`, `hibernate-jpa`, and `hibernate-validator`. If such a feature is
explicitly requested, generation fails early with a message that the feature is not supported for
Pyronaut/Python projects.
