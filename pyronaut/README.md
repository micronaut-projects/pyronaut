# pyronaut

Python orchestrator module for CLI v2 packaging and verification.

## Run orchestrator unit tests

```bash
./gradlew :micronaut-pyronaut:testPythonOrchestrator
```

## Build and install the SDK wheel locally

Build the Python wheel that bundles the `pyronaut` orchestrator and delegated CLI tools:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel
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

Option behavior:

- `--dependencies`: renders dependency tree output instead of install-focused progress and cache writes.
- `--scope`: `build`, `runtime`, `test`, or `all` (`runtime` is the default in `--dependencies` mode).
- `--progress`: `auto`, `on`, or `off` (`auto` enables interactive rendering only on TTY).
- `--color`: `auto`, `always`, or `never` (`auto` only emits ANSI where supported).

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
