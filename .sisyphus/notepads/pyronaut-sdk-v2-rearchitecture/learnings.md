
## 2026-03-12

- Added orchestrator-level `--debug-vm` for `pyronaut run` / `pyronaut test` that:
  - preflights port 5005 availability (fail-fast with a clear port-in-use error)
  - sets `JAVA_TOOL_OPTIONS` to include `-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005` for the delegated JVM process
  - forwards `--debug-vm` through to `pyronaut-run` / `pyronaut-test` (which now accept the flag to avoid unknown-option errors and to preserve invocation traceability)
## [2026-03-12 17:10] Task 24 – debug-vm
- Added `--debug-vm` to pyronaut-run/test so the CLI accepts the flag and the orchestrator can forward it.
- The orchestrator now checks port 5005, sets `JAVA_TOOL_OPTIONS` to the exact JDWP string, and keeps trace output showing the flag propagation.
- Tests cover the new flag/port failure paths in both the Jupiter tests and orchestrator tests.

## [2026-03-12 17:04] Tasks 21–23 – no-cache and deterministic preflight
- Added a global `--no-cache` flag to the orchestrator that forwards `pyronaut-install --refresh`, `pyronaut-processor --no-cache`, and the final delegated command.
- Run/test commands now always go through install/process before delegating to pyronaut-run/test, reporting failures per stage and keeping trace logs clear.
- Added orchestrator tests for the new flag/stage order and ensured install/processor output mention cache bypass when requested.
