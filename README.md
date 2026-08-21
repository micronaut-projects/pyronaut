![Pyronaut Logo](media/pyronaut.png)

# Pyronaut

Pyronaut brings Micronaut application development to Python code running on
GraalPy. It combines Micronaut dependency injection, configuration, HTTP,
testing, build tooling, and test resources with Python source files and pytest
workflows.

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
- `pyronaut-create`: generates new Pyronaut applications from project
  templates.
- `pyronaut-validate-config`: validates Micronaut configuration for run, test,
  and production scenarios.
- `pyronaut-test-resources-server`: manages Micronaut Test Resources for local
  development and tests.
- `pyronaut-native-build`: builds native executables.
- `pyronaut-tui`: interactive terminal UI over the same CLI workflow.
- `pyronaut-projectgen` and `pyronaut-projectgen-app`: project generation
  support.
- `pyronaut-pytest`, `pyronaut-requests`, `pyronaut-logging`, and
  `pyronaut-logback`: runtime and testing support libraries.

The full user guide lives in `src/main/docs/guide`.

## CLI at a glance

```bash
pyronaut [--version] [--tui [--smoke|--non-interactive]] \
  <install|process|dev|run|test|build|create|validate-config|test-resources-server> [args...]
```

Current platform support is macOS and Linux. Commands that delegate to the JVM
require a compatible GraalVM JDK; the CLI can discover local GraalVM
installations or provision configured toolchains.

## Typical project layout

Pyronaut reads project settings from `pyproject.toml`.

Default directories are:

```text
src            Python application sources
tests          Python tests
src-java       optional Java application sources
test-java      optional Java test sources
config         Micronaut application resources
tests-config   Micronaut test resources
__pyronaut__   generated Pyronaut state, reports, caches, and manifests
```

A minimal `pyproject.toml` looks like this:

```toml
[project]
name = "hello-pyronaut"
version = "0.1.0"
dynamic = ["scripts"]

[build-system]
requires = ["setuptools", "wheel", "tomli"]
build-backend = "setuptools.build_meta"

[tool.pyronaut]
repositories = ["mavenCentral"]

[tool.pyronaut.dependencies]
runtime = [
  "io.micronaut:micronaut-http-server-netty",
  "io.micronaut:micronaut-json-core",
  "io.micronaut:micronaut-jackson-databind",
  "ch.qos.logback:logback-classic"
]
build = []
test = [
  "io.micronaut.pyronaut:micronaut-pyronaut-pytest",
  "io.micronaut.test:micronaut-test-junit5",
  "io.micronaut.pyronaut:micronaut-pyronaut-requests"
]
```

Custom source directories can be configured with:

```toml
[tool.pyronaut.sources]
python = "app"
python-test = "test/python"
java = "src/main/java"
java-test = "src/test/java"
resources = "app-config"
test-resources = "tests-config"
additional-resources = ["views", "assets"]
additional-test-resources = ["test-fixtures"]
```

## Testing example

Create `tests/test_controller.py`:

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

## Common CLI workflows

Resolve dependencies and generate editor support:

```bash
pyronaut install --project-dir /path/to/app
```

Inspect dependency trees without rewriting install manifests:

```bash
pyronaut install --dependencies --scope all --project-dir /path/to/app
```

Process sources:

```bash
pyronaut process --project-dir /path/to/app
pyronaut process --no-cache --project-dir /path/to/app
```

Run or test with lifecycle validation:

```bash
pyronaut dev --project-dir /path/to/app
pyronaut test --project-dir /path/to/app
pyronaut test --tests test_controller.py::test_index --project-dir /path/to/app
```

Skip lifecycle configuration validation explicitly:

```bash
pyronaut dev --no-validate
pyronaut test --no-validate
```

Validate configuration directly:

```bash
pyronaut validate-config --scenario production --format both
pyronaut validate-config --scenario test --env test --validate-dependency-injection
```

Manage a reusable Test Resources server:

```bash
pyronaut test-resources-server start --project-dir /path/to/app
pyronaut test-resources-server status --project-dir /path/to/app
pyronaut test-resources-server stop --project-dir /path/to/app
```

Build deployable artifacts:

```bash
pyronaut build --jvm
pyronaut build --native --main-class example.Application
pyronaut build --docker
pyronaut build --native --docker
pyronaut build --native --docker --static
pyronaut build --native --base-image=default
pyronaut build App.java --native --name hello-java --version 1.0.0
```

Launch the terminal UI:

```bash
pyronaut --tui --project-dir /path/to/app
pyronaut --tui --test --project-dir /path/to/app
```

Trace delegated tool invocations while debugging:

```bash
PYRONAUT_TRACE_DELEGATION=true pyronaut dev --project-dir /path/to/app
```

Disable automatic Test Resources orchestration for `dev` and `test`:

```bash
PYRONAUT_TEST_RESOURCES_DISABLED=true pyronaut test
```

## Building the CLI locally

The following sequence starts from a fresh checkout and supports either a
global CLI installation or a project-local virtual environment. Complete this
section before creating a hello-world application.

### Prerequisites

Install GraalVM Community Edition 25.1.3. GraalVM Enterprise Edition should
also work. The documented setup uses Community Edition because it is the setup
used for Pyronaut development and verification.

Install `pyenv` if it is not already available, then install GraalPy
`3.12.8` (`graalpy3.12-25.1.3`):

```bash
pyenv install --list | grep graalpy
pyenv install graalpy3.12-25.1.3
pyenv shell graalpy3.12-25.1.3
python --version
```

On macOS, check the registered JDKs with:

```bash
/usr/libexec/java_home -V
```

Set `JAVA_HOME` to the installed GraalVM JDK 25. If the JDK was downloaded
rather than installed as a macOS bundle, use its full path instead of
`java_home`:

```bash
export PYENV_VERSION=graalpy3.12-25.1.3
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java" -version
```

The build requires Java 25 or newer. Do not leave a placeholder such as
`/path/to/graalvm` in `JAVA_HOME`.

### Build the CLI

Clone the repository and enter it:

```bash
git clone https://github.com/micronaut-projects/pyronaut.git
cd pyronaut
```

Build the SDK wheel. `--refresh-dependencies` is useful when working with the
Micronaut Core snapshot used by the current source checkout:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel \
  --stacktrace \
  --refresh-dependencies
```

The wheel is written to:

```text
pyronaut/build/wheel/dist/
```

### Option 1: Install the CLI into the active pyenv environment

From the Pyronaut repository root:

```bash
./gradlew :micronaut-pyronaut:installSdkWheel
pyronaut --help
pyronaut --version
```

`installSdkWheel` builds the wheel if necessary, publishes the local Pyronaut
artifacts used by snapshot projects, and installs the wheel into the selected
`PYENV_VERSION` environment.

### Option 2: Install the CLI into a demo-app virtual environment

Use this option when the CLI should be isolated to one demo application. Run
the following from the Pyronaut repository first:

```bash
./gradlew :micronaut-pyronaut:prepareSdkMavenLocalEnvironment
```

Then, from a demo-app directory outside the Pyronaut repository, create and
activate a virtual environment and install the wheel using its absolute path:

```bash
python3 -m venv .venv-pyronaut-sdk
source .venv-pyronaut-sdk/bin/activate
python -m pip install --upgrade pip
python -m pip install \
  /absolute/path/to/pyronaut/pyronaut/build/wheel/dist/pyronaut-*.whl
pyronaut --help
pyronaut --version
```

Replace `/absolute/path/to/pyronaut` with the full path to the checkout. Do
not use `~/Code/pyronaut` unless that is actually where the repository lives.

### Create and run hello-world

After completing either Option 1 or Option 2, create the application from its
parent directory:

```bash
pyronaut create hello-world
cd hello-world
pyronaut install
pyronaut dev
```

The generated project creates `pyproject.toml`, application configuration, the
controller, and tests. Snapshot projects include the local and Micronaut
snapshot repositories needed by the source checkout.

To build and test the repository separately:

```bash
./gradlew check
```

Build the native SDK wheel:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel -Pnative=true
```

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

Oracle GraalVM can build `pyronaut-dev` with profile-guided optimization.
The profile is collected by first building an instrumented native executable,
then running the functional-test workload through that executable, and finally
rebuilding with the generated `.iprof` file.

Build the instrumented native executable:

```bash
./gradlew :micronaut-pyronaut-dev:nativeCompile \
  -PpyronautDevPgoInstrument=true \
  --console=plain
```

Collect the profile by running the native functional tests:

```bash
./gradlew :micronaut-functional-test:test \
  -Pnative=true \
  -PpyronautDevPgoCollect=true \
  --console=plain
```

By default, the profile is written to
`functional-test/build/pgo/pyronaut-dev.iprof`. To use a different location,
set `-PpyronautDevPgoProfileFile=/path/to/pyronaut-dev.iprof` on the functional
test command.

Rebuild the optimized native executable with the collected profile:

```bash
./gradlew :micronaut-pyronaut-dev:nativeCompile \
  -PpyronautDevPgoProfile="$PWD/functional-test/build/pgo/pyronaut-dev.iprof" \
  --console=plain
```

The PGO profile is generated by the instrumented native executable. The regular
JVM functional-test mode does not emit a Native Image `.iprof` profile.

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
- `pyronaut-cli-v2/docs/cli-protocol.md`: orchestrator/delegate protocol.
- `CONTRIBUTING.md`: maintainer setup, build, and contribution notes.
