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

## Type checking and static compilation

`--type-check[=off|warn|error]` checks the Python sources against the Java types they use and
`--compile-static[=off|annotated|all]` compiles Python method bodies to Java; the bare flags mean
`warn` and `annotated`. `--no-type-check` and `--no-compile-static` switch either off for one
invocation, `--compile-static-report <dir>` moves the report (`decisions.jsonl` and `summary.txt`
under `main/` and `test/`, by default in `__pyronaut__/reports/static-compilation`), and
`--compile-static-strict` fails the build when an explicit `CompileStatic` cannot be honoured. The
`tool.pyronaut.processor.type-check` and `tool.pyronaut.processor.static-compilation` tables of
`pyproject.toml` hold the same settings; a flag wins over the table. Both travel to the compiler as
`-A` options and are part of the source cache fingerprint, so changing a mode re-processes the
sources. After a pass that compiled statically, one line reports how many methods compiled, how many
were skipped, the most common reason and where the summary is.

## Compilation cache

`pyronaut-processor` stores deterministic per-pass hashes under `__pyronaut__`:

- `processor-main.sha256`
- `processor-test.sha256`

For unchanged inputs (sources + classpath/options), compile passes are skipped with cache-hit reporting.

Incremental compilation can also be enabled explicitly:

```toml
[tool.pyronaut.processor]
incremental = true
# Optional; defaults to "conservative"
python-incremental-mode = "optimistic"
```

or for one invocation:

```bash
pyronaut-processor --project-dir /path/to/app --incremental
```

The CLI is negatable, so `--no-incremental` overrides project configuration. Incremental
state is stored separately for the main and test passes under
`__pyronaut__/incremental/main` and `__pyronaut__/incremental/test`. Isolating visitor
outputs are rebuilt only for changed sources and their dependents; aggregating visitors
reprocess all contributing sources and replace their shared outputs.

The default `conservative` Python mode reprocesses every Python source when a changed
Python dependency chain contains dynamic access or unresolved imports that cannot be
tracked statically. Set `python-incremental-mode = "optimistic"` to rely on the discovered
dependency graph in those cases. This can reduce compilation work for dynamic projects,
but the user is responsible for forcing a clean build when an untracked dynamic relationship
changes.

Incremental compilation is disabled by default. Changes to compiler options or dependency
contents, incompatible processors, corrupt state, and missing outputs trigger a repairing
full compilation. `--no-cache` clears output and incremental state and always performs a
full compilation.
