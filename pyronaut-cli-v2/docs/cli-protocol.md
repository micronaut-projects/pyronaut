# Pyronaut CLI v2 Protocol

This document defines the process contract between the Python `pyronaut` orchestrator and delegated executables:

- `pyronaut-install`
- `pyronaut-processor`
- `pyronaut-run`
- `pyronaut-test`

## Stream Contract

- Human-readable logs: `stderr`
- Machine-readable data: `stdout` with `--json`
- In `--json` mode, executables must emit exactly one JSON object to `stdout`.

## Shared JSON Result Contract

Schema: `schemas/command-result.schema.json`

Mandatory top-level fields:

- `protocolVersion` (string)
- `command` (string)
- `status` (`ok`|`error`)
- `exitCode` (integer)
- `durationMs` (integer)
- `artifacts` (object)

Error responses additionally include:

- `error.code` (string)
- `error.category` (string)
- `error.message` (string)

Examples:

- `examples/install-success.json`
- `examples/process-failure.json`

## Capabilities Contract

Each executable must support:

```bash
<command> --capabilities --json
```

Schema: `schemas/capabilities.schema.json`

This allows the orchestrator to verify compatibility without guessing flags/features.

## Exit Code Taxonomy

| Range | Category | Meaning |
|-------|----------|---------|
| 0 | SUCCESS | Command completed successfully |
| 2 | USAGE_ERROR | Invalid command line arguments |
| 3 | CONFIG_ERROR | Missing/invalid `pyproject.toml` or config model issue |
| 4 | RESOLUTION_ERROR | Dependency resolution or repository/auth failure |
| 5 | PROCESSING_ERROR | Python/Java processing or compile failure |
| 6 | RUNTIME_ERROR | Application start/run failure |
| 7 | TEST_ERROR | Test execution failure |
| 8 | PRECONDITION_FAILED | Required prior step (install/process) missing |
| 9 | PLATFORM_UNSUPPORTED | Platform outside phase-1 support matrix |
| 10 | INTERNAL_ERROR | Unexpected internal error |

Notes:

- `pyronaut-test` may map underlying pytest/JUnit details into `TEST_ERROR` while preserving raw details under `error.details`.
- Unknown command behavior in orchestrator should map to `USAGE_ERROR`.

## Versioning

- Protocol version string: `1.0`
- Breaking changes require protocol major increment.
- Orchestrator must fail fast if `protocolVersion` major is unsupported.
