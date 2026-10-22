# Functional Test Fixture

This module runs a Docker-free checked-in fixture application in [`app`](/Users/graemerocher/dev/pyronaut/functional-test/app) through the local Pyronaut toolchain.

It covers installation, configuration validation, processing, and an end-to-end HTTP/pytest run without MySQL, Micronaut Data, Testcontainers, or Test Resources. The Docker-backed fixture lives in [`functional-test-docker`](../functional-test-docker).

The main entry point is:

```bash
./gradlew :micronaut-functional-test:test
```

That task does all of the following against the fixture app:

- creates a dedicated virtual environment under `functional-test/build/venv`
- installs `pytest` into that virtual environment
- builds the local launcher distributions from this repository
- runs `pyronaut-install`
- runs `pyronaut-validate-config`
- runs `pyronaut-processor`
- runs the processed application tests directly
- runs `pyronaut-test`

## Native Tool Mode

The functional fixture can opt into native executables for the tools that currently support native execution:

```bash
./gradlew :micronaut-functional-test:test -Pnative=true
```

This mode builds and uses the combined `pyronaut-dev` native executable for `install`, `validate-config`, `process`, `test-resources-server`, and `test`.

The default command does not build native images:

```bash
./gradlew :micronaut-functional-test:test
```

## Prerequisites

The Gradle build assumes the following environment is already set up before you run the functional tests.

### 1. GraalPy via `pyenv`

The build requires the pyenv-selected Python version to start with `graalpy`. It resolves the version the same way pyenv does: `PYENV_VERSION`, then the nearest `.python-version` file in the checkout or a parent directory, then the global `$PYENV_ROOT/version` file (`~/.pyenv/version` by default). If the selected version is not GraalPy, the build fails early with:

```text
functional-test requires a GraalPy interpreter selected through pyenv (PYENV_VERSION, .python-version or the global pyenv version file). Current version='...'
```

Use a GraalPy interpreter installed through `pyenv`, and select it with `pyenv shell`, `pyenv local` or `pyenv global` before running Gradle:

```bash
pyenv global graalpy-<version>
python --version
pyenv version
```

This module does not create or manage the outer Python interpreter selection for you. It only creates the inner virtual environment in `functional-test/build/venv`.

To run the Docker-backed MySQL/Data/Test Resources fixture, use:

```bash
./gradlew :micronaut-functional-test-docker:test -Pdocker=true
```

The repository currently resolves GraalPy artifacts from the version catalog in [`gradle/libs.versions.toml`](/Users/graemerocher/dev/micronaut/pyronaut/gradle/libs.versions.toml), so in practice you should use the same GraalPy line as the rest of the project.

### 2. Java / GraalVM JDK

The fixture launchers use `JAVA_HOME` when present and otherwise fall back to `java` from `PATH`.

In practice you should set `JAVA_HOME` explicitly to the JDK you want the functional test to use:

```bash
export JAVA_HOME=/path/to/jdk
"$JAVA_HOME/bin/java" -version
```

Use the same JDK line that you use for normal development in this repository. A GraalVM JDK is the safest choice because the functional flow exercises GraalPy embedding and the same launchers used by the CLI.

### 3. Docker / Testcontainers

The fixture test run starts Micronaut Test Resources and provisions containers for the application under test. Docker must be installed and running.

Quick check:

```bash
docker ps
```

If Docker is unavailable, the functional test task may fail while starting test resources.

## Recommended Shell Setup

Example:

```bash
pyenv shell graalpy-<version>
export JAVA_HOME=/path/to/graalvm-jdk
./gradlew :micronaut-functional-test:test
```

## Notes

- The fixture virtual environment is created in `functional-test/build/venv`, not in `functional-test/app/.venv`.
- The fixture app cache and generated outputs live under `functional-test/app/__pyronaut__`.
- Test reports are written under `functional-test/app/__pyronaut__/reports/tests`.
- If you change the active Python runtime, remove `functional-test/build/venv` or run `./gradlew :micronaut-functional-test:clean` before rerunning.
