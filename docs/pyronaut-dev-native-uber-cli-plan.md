# Pyronaut Dev Uber Native Prototype

## Summary

- Add `:micronaut-pyronaut-dev`, a single Picocli/application module whose native image replaces the separate native SDK tools for `install`, `process`, `validate-config`, `test-resources-server`, `run`, and `test`.
- Update the Python `pyronaut` entrypoint so `-Pnative=true` SDK wheels delegate those commands and new source-launch invocations to `pyronaut-dev` instead of shipping individual native binaries.
- Prototype direct source execution with demo parity: `pyronaut [--test] [--port N] [--property k=v|-Dk=v] [--config file-or-dir] [--setup pyproject.toml] <source...> [-- <test-source...>]`.

## Key Changes

- `pyronaut-dev` will expose subcommands matching existing tools and call their `Callable`/Picocli command classes in-process, not through per-tool native executables.
- Native wheel packaging will stage only `pyronaut-dev` as the covered native executable; existing per-tool native artifacts are removed from native wheel staging. `pyronaut-native-build` and `pyronaut-tui` remain JVM distributions for this prototype because they are outside the requested combined tool set.
- Add a direct-source path in `pyronaut-dev`:
  - Single `.py` file uses `PyronautCompiler.pythonCode(...)`.
  - Directories use `PyronautCompiler.pythonSrc(...)`.
  - `--setup` supplies the dependency/config model; if absent, create an in-memory/default Pyronaut model with minimal built-in HTTP/runtime dependencies.
  - `--config` maps to Micronaut config locations; `--port` becomes `micronaut.server.port`; `--property` and `-D` become explicit Micronaut properties.
  - `--test` compiles app plus test sources and runs through the existing JUnit/Pyronaut test launcher path.
- Add the smallest needed `core.pyronaut` included-build API change: let `PyronautCompiler` build an in-memory classloader with an explicit parent/runtime classloader, so resolved user dependencies are visible without writing generated classes to disk.
- Treat native-image build-time initialization of Micronaut compiler/introspection infrastructure as a prototype unblocker, not a complete runtime model. Long term, direct-source/native execution likely needs a composite bean/introspection discovery layer that combines metadata baked into the `pyronaut-dev` image with user bean definitions and introspections generated and loaded after image startup.
- Native-image setup starts from the union of current install/processor/run/test/validate/test-resources native args, de-duplicated in the new module. Keep user application classloading, Micronaut runtime, GraalPy runtime, Netty, and test resources runtime initialized at run time; initialize compiler/tooling support at build time only where native-image accepts it.

## Test Plan

- Unit tests:
  - `pyronaut-dev` command dispatch calls existing command classes in-process for all six covered commands.
  - Python CLI resolves native mode to `pyronaut-dev`, including direct source syntax and legacy command syntax.
  - Direct source argument parsing covers file, directory, `--test`, `--`, `--port`, `--property`, `-D`, `--config`, and `--setup`.
- JVM smoke:
  - `./gradlew :micronaut-pyronaut-dev:installDist :micronaut-pyronaut:testPythonOrchestrator`
  - Run a fixture equivalent to `pyronaut examples/minimal/src/HelloController.py` and assert HTTP hello-world response.
- Native smoke:
  - `./gradlew :micronaut-pyronaut-dev:nativeCompile :micronaut-pyronaut-dev:nativeSmokeTest`
  - Build `./gradlew :micronaut-pyronaut:buildSdkWheel -Pnative=true` and verify the wheel contains one `pyronaut-dev` native binary and no per-tool native binaries.
- E2E:
  - Run existing orchestrator E2E against the native wheel for install/process/run/test.
  - Add one direct-source E2E for server startup and one `--test` E2E for source tests.

## Assumptions

- This prototype intentionally replaces native wheel mode now, while preserving normal non-native/JVM SDK behavior.
- `build`, TUI, project generation, and Docker/native app packaging are not folded into `pyronaut-dev` in this first pass.
- Disk writes for dependency caches, config reports, test reports, and test-resources state remain allowed; generated application classes for direct-source execution stay in memory.
- Build-time initialized Micronaut metadata must not prevent user-provided runtime metadata from being discovered; if it does, the prototype should prefer a combined metadata loader over pushing all introspection infrastructure back to run-time initialization.
