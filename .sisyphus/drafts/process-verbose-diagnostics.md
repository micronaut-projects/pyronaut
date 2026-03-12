# Draft: Process Verbose Diagnostics

## Requirements (confirmed)
- Add a `-v` / `--verbose` mode for `pyronaut process` to surface stack traces for fatal errors.
- On fatal errors in normal mode, print a hint telling users to rerun with verbose mode.
- Diagnose current failure in `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app`:
  - `Processing failed: java.lang.IllegalArgumentException: The argument does not represent an annotation type: PythonApplication`
- Investigate likely classpath-related root cause after improving error reporting.

## Technical Decisions
- Candidate insertion point for verbose behavior:
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorMain.java`
  - Add `-v` / `--verbose` option and use it in `catch (RuntimeException e)` to print stack trace when enabled.
- Candidate default UX (non-verbose):
  - Keep concise fatal message.
  - Add hint: `Run with --verbose to see the full stack trace.`
- Pending: exact scope of verbose flag (process-only vs shared pattern across commands).

## Research Findings
- Reproduced failure in target app:
  - `pyronaut process`
  - Output: `Processing failed: java.lang.IllegalArgumentException: The argument does not represent an annotation type: PythonApplication`
- Additional runtime diagnostics with javac option:
  - `pyronaut process --option -XprintRounds`
  - Round output shows annotation set includes `PythonApplication`.
- Full stack trace captured via temporary debug wrapper around `PyronautCompiler`:
  - Failure originates from
    `io.micronaut.annotation.processing.PackageElementVisitorProcessor.process(...)`
    calling `RoundEnvironment.getElementsAnnotatedWith(...)`.
  - Thrown by `JavacRoundEnvironment.throwIfNotAnnotation(...)`.
- `PythonApplication` annotation class is present in build classpath and is a real annotation type:
  - `io.micronaut.python.processing.annotation.PythonApplication`
  - Verified via `javap` from resolved `micronaut-inject-python` jar.
- `PyronautCompiler` generates source as:
  - package `pyronaut_application`
  - class `PyronautMain`
  - annotation `@PythonApplication(src = "...")`
- External references reviewed:
  - OpenJDK `JavacRoundEnvironment` guard that throws this exact exception.
  - CLI UX patterns (GraalVM/JLine/Jbang): concise default error + verbose stack trace toggle + hint.

### Current Diagnostic Hypothesis
- Primary diagnosis target is not a missing annotation jar.
- The failing path is annotation-processing internals receiving a non-annotation `TypeElement` for `PythonApplication` in package visitor processing.
- Likely needs deeper investigation in processor interaction/classloader/annotation-type resolution during javac rounds.

### Oracle Consultation Summary
- Ranked likely causes:
  1. wrong symbol resolution (simple-name/FQCN mismatch)
  2. processor/dependency classpath skew
  3. duplicate/shadowed `PythonApplication` symbol on effective paths
  4. lower-probability javac round edge case
- Recommended scope:
  - implement `-v/--verbose` for `pyronaut process` first (minimal risk)
  - keep default concise message + explicit rerun hint
  - add tests for verbose/non-verbose behavior parity on exit codes

## Open Questions
- Should verbose mode be implemented for `pyronaut process` only, or consistently across install/run/test too?
- Should non-verbose mode include only one-line hint, or also include a short diagnostics block (processor + round + annotation name)?
- Do we want to expose existing `--option` diagnostics in help examples (e.g. `--option -XprintRounds`) alongside `--verbose`?

## Scope Boundaries
- INCLUDE: process error-reporting UX and diagnosis of current failure.
- EXCLUDE: unrelated CLI behavior changes.
