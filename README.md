<img src="media/pyronaut_logo.svg" alt="Pyronaut Logo" width="300">

# Pyronaut

Pyronaut is a polyglot runtime for running Python and Java code built on the Micronaut programming model. Python and Java code can combine seamlessly and utilize Micronaut features like dependency injection, AOP, configuration properties, serialization and so on.

For Python developers Pyronaut is a viable alternative to frameworks like FastAPI built on one of the most popular and mature frameworks in the Java ecosystem and highly scalable thanks to Netty.

For Java developers Pyronaut provides a faster GraalVM Crema-based development model that allows easy incorporation of Python code using GraalPy.

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

## Installation

Install the latest published Pyronaut CLI from PyPI with Python 3.10 or later:

```bash
python3 -m pip install --upgrade pyronaut
pyronaut --version
```

If PyPI is unavailable, download the `pyronaut-<version>-py3-none-any.whl`
file from the Assets section of a [GitHub Release](https://github.com/micronaut-projects/pyronaut/releases),
then install the downloaded wheel manually:

```bash
python3 -m pip install --upgrade /path/to/downloaded/pyronaut-<version>-py3-none-any.whl
pyronaut --version
```

To keep the CLI isolated from other Python packages, install it in a virtual
environment:

```bash
python3 -m venv .venv-pyronaut-cli
source .venv-pyronaut-cli/bin/activate
python -m pip install --upgrade pip pyronaut
pyronaut --help
```

After installing or upgrading the wheel, provision the local Pyronaut SDK:

```bash
pyronaut setup
```

Setup is idempotent. It provisions GraalVM, resolves the SDK dependencies, and
downloads the native launchers required by the CLI. The validated setup state
and downloaded tools are cached under `~/.pyronaut`; run `pyronaut setup`
again after changing the wheel or use `pyronaut setup --refresh` to re-resolve
the setup.

If setup or any later command fails, `pyronaut doctor` checks the local
environment (Python, setup state, GraalVM, GraalPy, native launchers, proxy,
Docker) and the current project (stale generated state, threading
configuration, declared packages imported on GraalPy under the configured
context pool, and GraalPy's published package compatibility) and prints a fix
for every failing check:

```bash
pyronaut doctor
```

Create a Python application directly with Micronaut Launch:

```bash
pyronaut create demo
pyronaut create demo --features data-jdbc,mysql
```

The command fixes the language, build tool, and test framework to Python,
Pyronaut, and pytest. It installs the matching Micronaut Launch CLI on demand
under `~/.pyronaut/sdks` (or reuses an exact SDKMAN Micronaut candidate) and
uses the configured proxy and download progress reporting.

The package does not require cloning this repository or running Gradle. Pyronaut
uses an embedded GraalPy runtime for application code. Commands that need Java
use a compatible GraalVM JDK 25; Pyronaut discovers local installations and can
provision one under `~/.pyronaut/sdks` when necessary. Native launcher bundles
are downloaded on demand and cached under `~/.pyronaut/bin`. Pytest-backed tests
also require `pytest` in the project's GraalPy environment; see [Test
prerequisite: pytest in GraalPy](#test-prerequisite-pytest-in-graalpy).

## Getting started from a source checkout

These instructions build the Pyronaut CLI from this repository. The tested
project setup targets JDK 25, GraalVM 25.4, and GraalPy `3.13.14`
(`graalpy3.13-25.4.4.1.1`). Use a GraalVM JDK 25 for native-image tasks; the
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

Install GraalPy `3.13.14` (`graalpy3.13-25.4.4.1.1`) with `pyenv`:

```bash
pyenv install --list | grep graalpy
pyenv install --skip-existing graalpy3.13-25.4.4.1.1
pyenv shell graalpy3.13-25.4.4.1.1

python --version
```

The output should identify GraalPy `3.13.14` from Oracle GraalVM Native 25.4.4.1.1.

### 2. Build the Pyronaut CLI

Clone the repository and select the GraalPy environment used by the Gradle
wheel task:

```bash
git clone https://github.com/micronaut-projects/pyronaut.git
cd pyronaut
export PYENV_VERSION=graalpy3.13-25.4.4.1.1
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

The wheel is written to:

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

The help command should print the CLI usage. The released 0.0.3 wheel reports:

```text
Pyronaut: 0.0.3
Micronaut Core: 5.2.3
Micronaut Platform: 5.1.0
GraalPy: 25.4.4.1.1
Native Image JDK: 25
```

A wheel built from a snapshot checkout reports the `projectVersion` from
`gradle.properties` with `-SNAPSHOT` mapped to `.dev0` instead. The Pyronaut,
Micronaut Core, and platform versions may change as the repository evolves.

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

`pyronaut test` requires `pytest` to be installed in the GraalPy environment
used by the project. Pyronaut does not install Python packages automatically,
and a CPython virtual environment cannot provide packages to the embedded
GraalPy runtime.

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

## CLI at a glance

```bash
pyronaut [--version] [--allow-draft-release] [--tui [--smoke|--non-interactive]] \
  <setup|doctor|install|process|dev|run|test|build|create|validate-config|test-resources-server> [args...]
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
repositories = [
  "mavenCentral",
  "https://central.sonatype.com/repository/maven-snapshots/"
]

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

## Hello-world application

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

Create `pyproject.toml` in the `hello-world` directory using the minimal
example shown in [Typical project layout](#typical-project-layout) above.
The source checkout uses Micronaut Core `5.2.3`, which is published to Maven
Central.

### 3. Add a controller

Create `src/controllers.py`:

```python
from micronaut.http.annotation import Get


@Get(value="/", produces="text/plain")
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
pyronaut build --native
pyronaut build --docker
pyronaut build --native --docker
pyronaut build --native --docker --static
pyronaut build --native-base=default
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
version = "0.0.3"
```

For example, `./gradlew -PprojectVersion=0.0.3 :micronaut-pyronaut-dev:assemble`
writes the bundle under `pyronaut-dev/build/distributions/`. The CLI looks
below each native module for a platform-specific archive such as
`pyronaut-dev-macos-aarch64-0.0.3.tar.gz` and unpacks it into the normal local
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
- `CONTRIBUTING.md`: maintainer setup, build, and contribution notes.
