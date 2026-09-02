# pyronaut

Python orchestrator module for CLI v2 packaging and verification.

## Run orchestrator unit tests

```bash
./gradlew :micronaut-pyronaut:testPythonOrchestrator
```

## Build and install the SDK wheel locally

Build the Python wheel that bundles the `pyronaut` orchestrator and delegated JVM CLI tools:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel
```

Build and install the JVM wheel into the active pyenv Python:

```bash
./gradlew :micronaut-pyronaut:installSdkWheel
```

The wheel is written to:

```text
pyronaut/build/wheel/dist/
```

Create a local virtual environment and install the wheel:

```bash
python3 -m venv .venv-pyronaut-sdk
source .venv-pyronaut-sdk/bin/activate
python -m pip install --upgrade pip
python -m pip install pyronaut/build/wheel/dist/pyronaut-*.whl
```

Verify the installed CLI:

```bash
pyronaut --help
pyronaut --version
```

If you rebuild the wheel and want to retest with the latest local artifact:

```bash
python -m pip install --force-reinstall pyronaut/build/wheel/dist/pyronaut-*.whl
```

The SDK wheel delegates to JVM launchers. Native images are downloaded on demand for native-configured projects and direct-source execution, then cached under `~/.pyronaut/bin`. The default bundle base URL is `https://github.com/micronaut-projects/pyronaut/releases`. Use `[native-images]` in `~/.pyronaut/settings.toml` to select a release or another bundle source:

```toml
[native-images]
base-url = "https://github.com/micronaut-projects/pyronaut/releases"
version = "0.0.1"
# Optional: select an exact GitHub release tag.
release-tag = "v0.0.1"
```

When `release-tag` is omitted, the GitHub release tag defaults to
`v<version>`; for example, `version = "0.0.1-SNAPSHOT"` resolves
`v0.0.1-SNAPSHOT` while retaining the version in the asset filename and
cache path.

For a private repository or draft release, set a read-only
`PYRONAUT_RELEASE_TOKEN` (or `GH_TOKEN`, `GITHUB_TOKEN`, or
`GITHUB_API_TOKEN`) with repository contents read access. Use
`--allow-draft-release` when selecting a draft release. The Oracle GDS bundle
source remains available as an explicit override:

```toml
[native-images]
base-url = "https://gds.oracle.com/download/pyronaut/bundles/"
version = "0.0.1"
```

The CLI currently supports
Linux and macOS; Windows bundles may be published by CI but are not consumed
by this CLI yet. `pyronaut process` stays on JIT by default unless the project opts into:

```toml
[tool.pyronaut.processor]
mode = "native"
```

## Configurable source and resource directories

Project layout is configured in `pyproject.toml` under `[tool.pyronaut.sources]`. All directories are relative to the project root.

Defaults:

- `python = "src"`
- `python-test = "tests"`
- `java = "src-java"`
- `java-test = "test-java"`
- `resources = "config"`
- `test-resources = "tests-config"`

Example:

```toml
[tool.pyronaut.sources]
python = "app"
python-test = "test/python"
java = "src/main/java"
java-test = "src/test/java"
resources = "app-config"
test-resources = "tests-config"
```

These settings are honored by install/process/run/test, lifecycle validation defaults, auto-restart file watching, and build staging.

When running tests, Pyronaut normally bootstraps the processed application by evaluating `src/main.py`. If the configured Python test directory contains a root `tests.py` file, `pyronaut test` evaluates that file instead of `main.py` for the test process. This allows test-specific bootstrap such as alternate logging setup while leaving application runtime bootstrap unchanged.

## IDE stub configuration

`pyronaut install` generates Python IDE stubs for configured Micronaut and Jakarta Java APIs and writes them to `__pyronaut__/ide-stubs` by default. It also extracts Python sources embedded in resolved GraalPy virtual filesystem artifacts, such as `pyronaut.test` and `logback.config`, so editor imports match runtime imports. Generated editor support is cached under `~/.pyronaut/ide-stubs` and reused across projects when the resolved artifact set and stub configuration are unchanged.

Defaults:

- `enabled = true`
- `ide = "vscode"`
- `packages = ["io.micronaut", "jakarta"]`
- `exclude-patterns = ["*ModuleInfo"]`
- `destination-dir = "__pyronaut__/ide-stubs"`

Example:

```toml
[tool.pyronaut.ide-stubs]
enabled = true
ide = "vscode" # vscode|pycharm
packages = ["io.micronaut.http", "jakarta.inject"]
exclude-patterns = ["*ModuleInfo", "io.micronaut.http.internal.*"]
destination-dir = "__pyronaut__/ide-stubs"
```

For VS Code, Pyronaut updates `.vscode/settings.json` unless `pyrightconfig.json` or `[tool.pyright]` is already present. The settings include the generated stub directory and, when the CLI launches `pyronaut install`, the active Python interpreter and site-packages path so imports such as `pytest` and `pyronaut.test` resolve in Pylance.

## GraalVM toolchain configuration

The orchestrator can resolve a GraalVM JDK from local toolchains or download one on demand based on `pyproject.toml`:

```toml
[tool.pyronaut.toolchain]
distribution = "dev" # ce|ee|dev
version = "25.1.0-dev+10.1"
java-version = 25
release-tag = "jdk-25.1.0-dev-20260429_0111"
```

Discovery order:

1. `JAVA_HOME`
2. `~/.pyronaut/sdks`
3. legacy `~/.pyronaut/jdks`
4. SDKMAN (`~/.sdkman/candidates/java`)
5. jEnv (`~/.jenv/versions`)
6. Gradle toolchains (`~/.gradle/jdks`)
7. download into `~/.pyronaut/sdks`

Notes:

- `distribution = "ce"` resolves Community builds from `graalvm-ce-builds`.
- `distribution = "ee"` resolves Oracle GraalVM builds.
- `distribution = "dev"` resolves development builds from `graalvm-ce-dev-builds` and normally needs `release-tag`; Oracle EA tags such as `jdk-25e1-25.0.3-ea.32` resolve from `oracle-graalvm-ea-builds`.
- `download-url` can be used as an explicit archive override.
- On macOS, downloaded dev builds may need:

```bash
sudo xattr -r -d com.apple.quarantine /path/to/graalvm
```

## Install command usability flags

`pyronaut install` supports additional diagnostics and output controls:

```bash
# Default install flow (writes scoped cache manifests)
pyronaut install --project-dir /path/to/app

# Read-only dependency tree diagnostics (no manifest writes)
pyronaut install --dependencies --project-dir /path/to/app

# Show all scopes in dependency tree mode
pyronaut install --dependencies --scope all --project-dir /path/to/app

# Force plain, deterministic output for CI/non-interactive usage
pyronaut install --progress off --color never --project-dir /path/to/app
```

### Direct-source IDE setup

Projects that declare their build directly in Java or Python sources do not need
a `pyproject.toml` to install editor support:

```bash
pyronaut install App.java
pyronaut install '*.java'
pyronaut install app.py helpers.py
pyronaut install --project-dir /path/to/app src/
```

All selected files must use the same language. Relative source paths are
resolved from `--project-dir` (the current directory by default), which is also
where `__pyronaut__` and editor configuration are written. Shell-expanded and
quoted glob patterns are supported.

For Java sources, install merges Pyronaut-owned entries into `.classpath` for
Eclipse, writes `java.project.sourcePaths` and
`java.project.referencedLibraries` to `.vscode/settings.json`, and registers a
dedicated `.idea/pyronaut-direct-source.iml` module for IntelliJ. Java package
declarations are used to calculate the source roots. Existing unrelated
settings, classpath entries, and IntelliJ modules are preserved. The generated
classpath includes both dependencies declared with `@Dependency` and the
compile APIs supplied by the direct-source launcher.

Install also creates `Pyronaut: Run Direct Sources` and
`Pyronaut: Test Direct Sources` launch entries. VS Code stores these in
`.vscode/launch.json`; IntelliJ stores equivalent Shell Script configurations
under `.idea/runConfigurations`. The application entry executes `pyronaut dev`.
Sources whose names or directories follow standard test conventions are
excluded from that command and placed after `--` in the `pyronaut test`
command, for example `pyronaut test 'example/App.java' --
'example/AppTest.java'`. This avoids IDE-native JUnit classpath inference.

For Python sources, install uses the normal IDE-stub pipeline and updates
`.vscode/settings.json` for Pylance. Existing `pyrightconfig.json`,
`[tool.pyright]`, interpreter, and user setting safeguards still apply.

When neither a supported project descriptor nor positional sources are
present, install reports an error with a direct-source usage example.

Direct-source resolution uses the normal `__pyronaut__` manifests. Its cache
key includes the declarations, repository URLs, selected local repository,
source contents, selected source paths, and bundled launcher classpath
metadata. `--refresh`, `--no-cache`, `--offline`, and `--local-repository`
apply to direct-source installs in the same way as descriptor-based installs.

Option behavior:

- `--dependencies`: renders dependency tree output instead of install-focused progress and cache writes.
- `--scope`: `build`, `runtime`, `test`, or `all` (`runtime` is the default in `--dependencies` mode).
- `--progress`: `auto`, `on`, or `off` (`auto` enables interactive rendering only on TTY).
- `--color`: `auto`, `always`, or `never` (`auto` only emits ANSI where supported).

## Process preflight

`pyronaut process` processes both main and test sources by default. Use `--pass main` or `--pass test` to process only one output set:

```bash
pyronaut process --project-dir /path/to/app --pass main
pyronaut process --project-dir /path/to/app --pass test
```

`pyronaut run` uses the main pass and `pyronaut test` uses the test pass during their preflight. They do not regenerate IDE stubs or refresh dependency manifests; run `pyronaut install` explicitly after changing dependencies, repositories, source layout, or IDE stub settings.

### Proxy configuration precedence

`pyronaut install` can apply proxy settings for Maven artifact resolution with this precedence:

1. Environment (`HTTPS_PROXY`/`HTTP_PROXY`, optional `NO_PROXY`)
2. `~/.pyronaut/settings.toml`
3. `~/.m2/settings.xml`

`~/.pyronaut/settings.toml` supports a `[proxy]` table with either `url` or `host` + `port`, and optional `protocol`,
`username`, `password`, and `nonProxyHosts`.

When resolution fails, diagnostics include the selected proxy source and endpoint host/port without printing credentials.

Delegation tracing is disabled by default. To print delegated tool command lines for debugging:

```bash
PYRONAUT_TRACE_DELEGATION=true pyronaut install --project-dir /path/to/app
```

## Run orchestrator E2E tests

The orchestrator E2E suite is split into a **fast smoke gate** and a **full integration gate**.

### Fast smoke E2E (default in `check`)

This path is designed to be stable and quick for day-to-day development/CI.

```bash
./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E
./gradlew :micronaut-pyronaut:check
```

Behavior:

- Runs `e2e_flow_test.py` with `PYRONAUT_E2E=true`.
- Keeps heavy full-flow checks disabled (`PYRONAUT_E2E_FULL=false`).
- Does **not** require Docker/MySQL (`PYRONAUT_E2E_MYSQL=false`).

### Full E2E integration (opt-in)

Run this when validating end-to-end Docker-backed integration flows (for example, nightly or pre-release verification):

```bash
./gradlew :micronaut-pyronaut:testPythonOrchestratorE2EFull
```

Behavior:

- Enables full-flow tests (`PYRONAUT_E2E_FULL=true`).
- Enables Docker/MySQL-backed checks (`PYRONAUT_E2E_MYSQL=true`).
- Includes slow scenarios such as run/test lifecycle and MySQL test-resources integration.

### What E2E covers

Depending on mode, E2E validates real delegated CLI behavior across:

- `pyronaut-install`
- `pyronaut-processor`
- `pyronaut-run`
- `pyronaut-test`
