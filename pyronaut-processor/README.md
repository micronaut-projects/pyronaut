# pyronaut-processor

`pyronaut-processor` executes `io.micronaut.python.compiler.PyronautCompiler` to process Python sources and generate Micronaut build-time metadata.

## Current execution mode

`pyronaut-processor` runs on the JVM/JIT through its application launcher.

## Verbose diagnostics

`pyronaut-processor` supports verbose diagnostics for compilation failures:

```bash
pyronaut-processor --project-dir /path/to/app --verbose
```

- Default mode prints a concise failure message with a rerun hint.
- `--verbose` includes full stacktrace/cause output for root-cause investigation.

## Progress output

`pyronaut-processor` reports pass-level progress with source counts.

```bash
pyronaut-processor --project-dir /path/to/app --progress auto
```

- `--progress auto` uses spinner output on interactive terminals and deterministic plain lines otherwise.
- `--progress on` always enables spinner-style progress.
- `--progress off` suppresses progress output.

## Dual source and test processing

By default, one `pyronaut-processor` invocation performs two processing passes:

- configured Python source dir (default `src`) + configured Java source dir (default `src-java`) → `__pyronaut__/classes`
- merged main+test sources using the configured Python/Java test dirs (defaults `tests` and `test-java`) → `__pyronaut__/test-classes`

The test pass uses `resolved-test-dependencies` and compiles a fused source tree so `__pyronaut__/test-classes` contains everything needed for isolated test execution.
If only main sources are present, they are still compiled into `__pyronaut__/test-classes`; if no processable Python/Java sources exist at all, the directory is created empty.

## Incremental compile cache

`pyronaut-processor` stores deterministic per-pass hashes under `__pyronaut__`:

- `processor-main.sha256`
- `processor-test.sha256`

For unchanged inputs (sources + classpath/options), compile passes are skipped with cache-hit reporting.
