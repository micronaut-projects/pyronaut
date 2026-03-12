# pyronaut-processor

`pyronaut-processor` executes `io.micronaut.python.compiler.PyronautCompiler` to process Python sources and generate Micronaut build-time metadata.

## Current execution mode

- **Default:** JVM/JIT execution (`application` plugin launcher)
- **Native image:** currently **experimental** and disabled by default due an upstream GraalVM native-image compiler issue.

Enable native-image tasks explicitly only for local experimentation:

```bash
./gradlew :micronaut-pyronaut-processor:nativeCompile -PpyronautProcessorNative=true
./gradlew :micronaut-pyronaut-processor:nativeSmokeTest -PpyronautProcessorNative=true
```

Until the GraalVM issue is fixed, production flow should use JVM execution.

## Verbose diagnostics

`pyronaut-processor` supports verbose diagnostics for compilation failures:

```bash
pyronaut-processor --project-dir /path/to/app --verbose
```

- Default mode prints a concise failure message with a rerun hint.
- `--verbose` includes full stacktrace/cause output for root-cause investigation.

## Dual source and test processing

By default, one `pyronaut-processor` invocation performs two processing passes:

- `src` + `src-java` → `__pyronaut__/classes`
- merged (`src` overlaid by `tests`) + merged (`src-java` overlaid by `test-java`) → `__pyronaut__/test-classes`

The test pass uses `resolved-test-dependencies` and compiles a fused source tree so `__pyronaut__/test-classes` contains everything needed for isolated test execution.
If only main sources are present, they are still compiled into `__pyronaut__/test-classes`; if no processable Python/Java sources exist at all, the directory is created empty.
