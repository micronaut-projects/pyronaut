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

Delegation tracing is disabled by default. To print delegated tool command lines for debugging:

```bash
PYRONAUT_TRACE_DELEGATION=true pyronaut install --project-dir /path/to/app
```

## Run real E2E tests

The E2E suite is guarded by `PYRONAUT_E2E=true` and executes real CLI apps:

- `pyronaut-install`
- `pyronaut-processor`
- `pyronaut-run`
- `pyronaut-test`

Run only E2E:

```bash
PYRONAUT_E2E=true ./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E
```

Run module checks with E2E included:

```bash
PYRONAUT_E2E=true ./gradlew :micronaut-pyronaut:check
```

The E2E test validates:

1. `install` writes all scoped cache manifests
2. `process` generates `__pyronaut__/classes`
3. `run` starts the real Micronaut server and serves HTTP on `localhost:8080`
4. `test` executes real pytest-backed tests through `micronaut-pytest-engine`
