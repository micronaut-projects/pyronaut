# Functional Test Fixture

This module runs the Docker-backed MySQL/Micronaut Data fixture in [`app`](app) through the local Pyronaut toolchain.

Run it with `-Pdocker=true`; the task is skipped otherwise because it starts Test Resources and requires a Docker engine.

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
- starts the test-resources server
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

`functional-test/build.gradle.kts` requires `PYENV_VERSION` to start with `graalpy`. If it does not, the build fails early with:

```text
functional-test requires a GraalPy interpreter. Current PYENV_VERSION='...'
```

Use a GraalPy interpreter installed through `pyenv`, and make it active in the current shell before running Gradle:

```bash
pyenv shell graalpy-<version>
python --version
echo "$PYENV_VERSION"
```

This module does not create or manage the outer Python interpreter selection for you. It only creates the inner virtual environment in `functional-test/build/venv`.

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
