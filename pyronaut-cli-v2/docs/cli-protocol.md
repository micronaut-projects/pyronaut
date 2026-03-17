# Pyronaut CLI v2 Protocol

This document defines the process contract between the Python `pyronaut` orchestrator and delegated executables.

Current orchestrator command surface:

- `pyronaut install`
- `pyronaut process`
- `pyronaut run`
- `pyronaut test`
- `pyronaut build`
- `pyronaut --tui` (global flag mode)

Delegated executables:

- `pyronaut-install`
- `pyronaut-processor`
- `pyronaut-run`
- `pyronaut-test`
- `pyronaut-native-build` (native branch of `pyronaut build`)
- `pyronaut-tui` (delegated TUI runtime)

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
- `examples/build-success.json`

## Capabilities Contract

Each executable must support:

```bash
<command> --capabilities --json
```

Schema: `schemas/capabilities.schema.json`

This allows the orchestrator to verify compatibility without guessing flags/features.

Minimum capability expectations by command:

- `install`: dependency resolution and scoped cache manifests
- `process`: main/test processing and deterministic cache semantics
- `run`: preflight-compatible runtime execution (`--debug-vm` pass-through aware)
- `test`: selector-aware execution (`--tests` support)
- `build`: mode-aware execution (`jvm`/`native`) and native delegate compatibility
- `tui`: delegated interactive/non-interactive TUI workflow compatibility

Feature flags in capabilities should be used for optional behavior negotiation (for example, test selectors, debug-vm forwarding, native passthrough).

## Command Surface Notes

- `build` defaults to JVM wheel behavior and supports native mode selection via CLI or `tool.pyronaut.build.mode`.
- Native mode delegates to `pyronaut-native-build` and may pass unconsumed native-image arguments through to the native-image command.
- `--tui` is a global orchestrator mode that delegates to `pyronaut-tui` and may drive run/test flows in interactive or smoke/non-interactive paths.

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

- Protocol version string: `1.1`
- Breaking changes require protocol major increment.
- Orchestrator must fail fast if `protocolVersion` major is unsupported.
