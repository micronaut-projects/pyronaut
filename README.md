<img src="https://raw.githubusercontent.com/micronaut-projects/pyronaut/0.0.x/media/pyronaut_logo.svg" alt="Pyronaut Logo" width="300">

# Pyronaut

Pyronaut, a high-performance Python application platform for building production-ready services built on GraalVM and Micronaut.

For Python developers, Pyronaut provides HTTP routing, dependency injection, configuration, validation, serialization, testing, and access to Java libraries through Python declarations.

For Java developers, Pyronaut adds Python support to a Micronaut application through GraalPy and the shared Micronaut application context.

The main user entry point is the `pyronaut` command. The command is a Python
orchestrator that delegates to focused JVM/native tools for dependency
resolution, source processing, application execution, tests, configuration
validation, native builds, and test resources.

## What is in this repository?

This repository contains the Pyronaut CLI and the Micronaut integration modules
that make Python applications work with Micronaut:

- `pyronaut`: the packaged Python CLI orchestrator and SDK wheel.
- `pyronaut-install`: resolves Maven dependencies, writes classpath manifests,
  generates configuration schemas, and creates IDE stubs.
- `pyronaut-processor`: processes Python and Java sources into Micronaut
  metadata/classes.
- `pyronaut-dev`: runs applications in development mode with automatic
  install/process preflight.
- `pyronaut-run` and `pyronaut-test`: run applications and pytest-backed tests.
- `pyronaut-validate-config`: validates Micronaut configuration for run, test,
  and production scenarios.
- `pyronaut-test-resources-server`: manages Micronaut Test Resources for local
  development and tests.
- `pyronaut-native-build`: builds native executables.
- `pyronaut-tui`: interactive terminal UI over the same CLI workflow.
- `pyronaut-pytest`, `pyronaut-requests`, `pyronaut-logging`, and
  `pyronaut-logback`: runtime and testing support libraries.

The full user guide lives in `src/main/docs/guide`.

## Installation and user guide

Install the CLI with Python 3.10 or later on Linux or macOS, then provision the SDK:

```bash
python3 -m pip install pyronaut
pyronaut setup
```

The [Pyronaut guide](https://pyronaut.io/docs/) contains the user-facing installation, setup, project,
testing, configuration, and packaging instructions.
Start with [Installing Pyronaut](https://pyronaut.io/docs/#installation), then
choose the [project workflow](https://pyronaut.io/docs/#gettingStarted) or the
[direct-source tutorial](https://pyronaut.io/docs/#fastApiTutorial).

This README focuses on building and contributing to the Pyronaut repository.

## Getting started from a source checkout

These instructions build the Pyronaut CLI from this repository. The tested
project setup targets JDK 25, GraalVM 25.4, and GraalPy `3.13.14`
(`graalpy3.13-25.4.4`). Use a GraalVM JDK 25 for native-image tasks; the
current CI setup uses GraalVM 25.4.

### 1. Install the prerequisites

Install a GraalVM JDK 25, such as GraalVM 25.4, from the
[GraalVM downloads](https://www.graalvm.org/downloads/). Choose the bundle
matching your machine's architecture. On macOS, set `JAVA_HOME` to the JDK's
`Contents/Home` directory:

```bash
export JAVA_HOME="/path/to/graalvm-jdk-25/Contents/Home"
java -version
```

Set `JAVA_HOME` in the same shell where you run Gradle. Verify that the Java
version is 25 and that the runtime identifies itself as GraalVM.

Install `pyenv` using its [installation instructions](https://github.com/pyenv/pyenv#installation)
if it is not already available. On macOS, the setup can be started with:

```bash
brew install pyenv

echo 'export PYENV_ROOT="$HOME/.pyenv"' >> ~/.zshrc
echo '[[ -d "$PYENV_ROOT/bin" ]] && export PATH="$PYENV_ROOT/bin:$PATH"' >> ~/.zshrc
echo 'eval "$(pyenv init - zsh)"' >> ~/.zshrc

source ~/.zshrc
```

Install GraalPy `3.13.14` (`graalpy3.13-25.4.4`) with `pyenv`:

```bash
pyenv install --list | grep graalpy
pyenv install --skip-existing graalpy3.13-25.4.4
pyenv shell graalpy3.13-25.4.4

python --version
```

The output should identify GraalPy `3.13.14` from Oracle GraalVM Native 25.4.4.1.1.
The pyenv distribution name is `graalpy3.13-25.4.4`; the embedded GraalPy
Maven artifacts and runtime report version `25.4.4.1.1`.

### 2. Build the Pyronaut CLI

Clone the repository and select the GraalPy environment used by the Gradle
wheel task:

```bash
git clone https://github.com/micronaut-projects/pyronaut.git
cd pyronaut
export PYENV_VERSION=graalpy3.13-25.4.4
```

Build the SDK wheel:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel \
  --stacktrace \
  --refresh-dependencies
```

The `--refresh-dependencies` option is intentional. It helps avoid
compilation errors caused by a version mismatch between a Micronaut Core
snapshot downloaded from the Maven snapshots repository and the checked-out
Pyronaut sources.

The `buildSdkWheel` task writes the wheel to:

```text
pyronaut/build/wheel/dist/
```

### 3. Install the CLI

Choose one of the following options.

#### Option 1: Install into the active pyenv environment

This uses the `PYENV_VERSION` selected above and installs the wheel into that
GraalPy environment:

```bash
./gradlew :micronaut-pyronaut:installSdkWheel
pyronaut --help
pyronaut --version
pyronaut setup
```

The help command should print the CLI usage. The version command reports the
installed Pyronaut version and its managed Micronaut, GraalPy, and JDK
versions. A wheel built from a snapshot checkout reports the `projectVersion`
from `gradle.properties` with `-SNAPSHOT` mapped to `.dev0`.

#### Option 2: Install into a project-local virtual environment

Use this option when you want the CLI isolated to a demo application. Run the
commands from the demo application's directory, outside the Pyronaut checkout:

```bash
python3 -m venv .venv-pyronaut-sdk
source .venv-pyronaut-sdk/bin/activate
python -m pip install --upgrade pip
python -m pip install /absolute/path/to/pyronaut/pyronaut/build/wheel/dist/pyronaut-*.whl
pyronaut --help
pyronaut --version
pyronaut setup
```

Replace `/absolute/path/to/pyronaut` with the full path to the checkout where
you built the wheel. For example, if the checkout is under `~/Code/pyronaut`,
use `/Users/your-user/Code/pyronaut` as the corresponding absolute path.

If you rebuild the wheel, reinstall the new wheel into the virtual environment:

```bash
python -m pip install --force-reinstall /absolute/path/to/pyronaut/pyronaut/build/wheel/dist/pyronaut-*.whl
```

### Test prerequisite: pytest in GraalPy

The pytest-backed hello-world workflow below requires `pytest` in the GraalPy
environment used by the project. Pyronaut does not install Python packages
automatically, and a CPython virtual environment cannot provide packages to
the embedded GraalPy runtime. Direct-source JUnit 5 tests do not require
`pytest`.

If the CLI is installed into the active GraalPy pyenv environment (Option 1)
and the project does not have a `.venv`, install `pytest` into that environment:

```bash
python -m pip install --upgrade pip pytest
python -m pytest --version
```

For a project-specific environment, create `.venv` with GraalPy from the
hello-world directory:

```bash
graalpy -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip pytest
python -m pytest --version
```

When a project `.venv` exists, Pyronaut uses it when launching the application
and tests. Create that environment with GraalPy even when the CLI itself is
installed in a separate CPython environment.

## Hello-world source-checkout example

Create the hello-world application in its own directory outside the Pyronaut
checkout. Run the following commands from the parent directory of the
checkout:

### 1. Create the application directory

```bash
mkdir -p hello-world
cd hello-world
```

All application, installation, run, and test commands in this section are run
from the `hello-world` directory.

### 2. Create `pyproject.toml`

Create `pyproject.toml` in the `hello-world` directory using the project
configuration shown in [Getting Started](https://pyronaut.io/docs/#gettingStarted).
The source checkout uses the Micronaut Core version declared by
`pyronaut.micronaut.core.version` in `gradle.properties`.

### 3. Add a controller

Create `src/controllers.py`:

```python
from pyronaut import http


@http.Get(value="/", produces=http.TEXT_PLAIN)
def index() -> str:
    return "Hello from Pyronaut"
```

### 4. Add application configuration

Create `config/application.toml`:

```toml
[micronaut.application]
name = "hello-pyronaut"

[micronaut.server]
port = 8080
```

### 5. Run the application

Run the application in development mode:

```bash
pyronaut install
```

After `pyronaut install` succeeds, process the sources and start development
mode:

```bash
pyronaut process
pyronaut dev
```

Wait for `pyronaut install` to resolve every active scope before running
`pyronaut process`. A successful install resolves build, runtime,
development-runtime, and test dependencies, then generates the application
schema and Python editor stubs. If Test Resources is enabled, it also resolves
the test-resources-server dependencies. Dependency counts vary, but the output
has this shape:

```text
Resolved build dependencies (... artifacts)
Resolved runtime dependencies (... artifacts)
Resolved development-runtime dependencies (... artifacts)
Resolved test dependencies (... artifacts)
Generated application schema from runtime classpath (... fragments)
Generated Python editor stubs (... packages, ... symbols)
```

The `SLF4J(W)` messages about no providers are warnings from the install
process and do not indicate a failed dependency resolution.

If any scope reports `Dependency resolution failed`, treat `pyronaut install`
as unsuccessful and do not continue to `pyronaut process`. Processing then
fails with `Missing build scope cache` because installation did not write the
required `__pyronaut__/resolved-build-dependencies` manifest. Fix the
dependency or repository configuration and rerun `pyronaut install`.

`pyronaut dev` performs install/process preflight automatically, so after the
first successful install this is normally enough:

```bash
pyronaut dev
curl http://localhost:8080/
```

### 6. Add and run a test

Create `tests/test_controller.py`:

This test uses the Pyronaut Requests integration. Its
`io.micronaut.pyronaut:micronaut-pyronaut-requests` dependency is included in
the `test` dependencies in the `pyproject.toml` example above.

```python
import pytest
import requests

from pyronaut.test import MicronautTest, micronaut_test_fixture


@pytest.fixture
def my_context(request):
    fixture = micronaut_test_fixture(request, MicronautTest())
    yield fixture
    fixture.stop()


@pytest.fixture
def client(my_context):
    return requests.with_context(my_context)


def test_index(client):
    response = client.get("/")
    assert response.status_code == 200
    assert response.text == "Hello from Pyronaut"
```

Run tests through Pyronaut so the Micronaut classpath and pytest engine are set
up consistently:

```bash
pyronaut test
```

Reports are written under `__pyronaut__/reports/tests`.

### Troubleshooting

If `pyronaut install` reports `Dependency resolution failed`, do not continue
to `pyronaut process`. Fix the dependency or repository configuration and run
`pyronaut install` again until every scope resolves successfully. Otherwise,
`pyronaut process` will report `Missing build scope cache` because installation
did not create the required build dependency manifest.

If `pyronaut test` reports that pytest is not installed, activate the GraalPy
environment where pytest was installed and verify it with
`python -m pytest --version` before rerunning the test.

## CLI reference

The [CLI reference](https://pyronaut.io/docs/#pyronautCliV2) is the source of
truth for command syntax, options, generated files, environment variables,
exit codes, and representative workflows.

## Building the CLI locally

For the complete source-checkout setup, dependency refresh guidance, and SDK
wheel installation options, follow [Getting started from a source checkout](#getting-started-from-a-source-checkout).

Build and test the repository:

```bash
./gradlew check
```

Build the JVM-based SDK wheel:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel
```

The wheel does not embed native executables. Run `pyronaut setup` after wheel
installation to download all three images into `~/.pyronaut/bin`, provision
GraalVM, resolve SDK dependencies, and publish the local setup manifest.
Configure a private bundle repository or pinned CI version in
`~/.pyronaut/settings.toml` under `[native-images]` before running setup.

Setup resolves from Maven Central by default and adds Sonatype Central
snapshots for snapshot SDK versions. Configure replacement repositories with:

```toml
[maven]
repositories = ["mavenCentral", "https://central.sonatype.com/repository/maven-snapshots/"]
```

During local development, `base-url` may instead point to this repository (or
use a `file://` URL). Build the bundles first, then select the exact project
version:

```toml
[native-images]
base-url = "/path/to/pyronaut"
version = "<version>"
```

For example, `./gradlew -PprojectVersion=<version> :micronaut-pyronaut-dev:assemble`
writes the bundle under `pyronaut-dev/build/distributions/`. The CLI looks
below each native module for a platform-specific archive such as
`pyronaut-dev-macos-aarch64-<version>.tar.gz` and unpacks it into the normal local
cache. Omit `-PprojectVersion` to build the snapshot version from
`gradle.properties` and select that `<version>-SNAPSHOT` string instead.

## Functional testing

Functional coverage is split into a Docker-free fixture and a Docker-backed
fixture:

- `functional-test/app` is a minimal HTTP application with no MySQL,
  Micronaut Data, Testcontainers, or Test Resources dependency. It validates
  installation, configuration validation, source processing, and the native
  CLI end to end:

  ```bash
  ./gradlew :micronaut-functional-test:test -Pnative=true
  ```

- `functional-test-docker/app` contains the MySQL/Micronaut Data scenarios and
  requires a running Docker engine. Run it explicitly with:

  ```bash
  ./gradlew :micronaut-functional-test-docker:test \
    -Pnative=true -Pdocker=true
  ```

  Without `-Pdocker=true`, its `test` task is skipped and does not start
  Micronaut Test Resources. This means the normal native check is safe to run
  while Docker is unavailable:

  ```bash
  ./gradlew check -Pnative=true
  ```

Both functional projects build the local Pyronaut launchers, create a fixture
virtual environment, resolve dependencies, validate configuration, process
sources, and run the pytest-backed flow. The Docker-free project runs its
pytest flow directly; the Docker-backed project starts and stops the Test
Resources server around its tests when Docker is enabled.

Requirements:

- An active GraalPy installation must be selected. The Gradle tasks require
  `PYENV_VERSION` to identify a GraalPy environment, and use its `python`
  executable to create the fixture virtual environment.
- A compatible JDK/GraalVM must be available. Native mode uses the configured
  GraalVM toolchain and requires the native-image toolchain to be installed.
- The Gradle tasks create `functional-test/build/venv` (or the corresponding
  `functional-test-docker/build/venv`) and install the pinned `pytest` version
  into it automatically. No project-local `.venv` is required.
- Docker is required only for `functional-test-docker` when invoked with
  `-Pdocker=true`; the Docker-free fixture and the default native check do not
  require a running container engine.

### PGO native image build

Oracle GraalVM can build all three native images (`pyronaut-dev`,
`pyronaut-run` and `pyronaut-run-python`) with profile-guided optimization.
On Linux, `pyronaut-run` also uses code compression. native-image does not
support code compression on macOS, or with the runtime compilation that the
GraalPy images need. Each image is built instrumented, trained with the
workload in `pgo-training`, and rebuilt with the collected profiles:

```bash
./gradlew -Ppyronaut.pgo=instrument :micronaut-pyronaut-run:nativeCompile
./gradlew :micronaut-pgo-training:trainPyronautRun
./gradlew -Ppyronaut.pgo=optimize :micronaut-pyronaut-run:nativeBundle
```

Use `trainPyronautRunPython` and `trainPyronautDev` for the other images. The
`trainJvm*` tasks run the same scenarios on the JVM launchers without building
native images, which is the quickest way to check changes to the workload.
Builds without `-Ppyronaut.pgo` are unchanged. See `TESTING.md` for the
release runbook, the training scenarios and the benchmark.

The functional tests can still contribute a `pyronaut-dev` profile: run them
with `-Pnative=true -PpyronautDevPgoCollect=true` against an instrumented
image. Each launcher process writes its own
`functional-test/build/pgo/pyronaut-dev-<n>.iprof`.

## Documentation

Build the guide:

```bash
./gradlew publishGuide
```

Build the guide and API docs:

```bash
./gradlew docs
```

Open `build/docs/index.html` after the guide build completes.

## More information

- `src/main/docs/guide/gettingStarted.adoc`: guided first application.
- `src/main/docs/guide/pyronautCliV2.adoc`: full CLI reference.
- `pyronaut/README.md`: SDK wheel and orchestrator development notes.
- `functional-test/README.md`: fixture application test workflow.
- `CONTRIBUTING.md`: maintainer setup, build, and contribution notes.
