# Pyronaut Process Verbose Diagnostics and PythonApplication Failure Investigation

## TL;DR

> **Quick Summary**: Add `-v/--verbose` to `pyronaut process` so fatal failures can emit full stack traces on demand, keep default output concise with an explicit rerun hint, then use verbose diagnostics to produce a concrete root-cause analysis for the `PythonApplication` failure in the demo app.
>
> **Deliverables**:
> - `pyronaut process -v/--verbose` support in processor command
> - Non-verbose fatal output: concise message + `--verbose` hint
> - Automated tests (tests-after strategy) for verbose/non-verbose behavior
> - Documented diagnosis of `The argument does not represent an annotation type: PythonApplication`
>
> **Estimated Effort**: Medium
> **Parallel Execution**: YES - 2 waves
> **Critical Path**: Task 1 → Task 2 → Task 4

---

## Context

### Original Request
In `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app`, running `pyronaut process` fails with:

`Processing failed: java.lang.IllegalArgumentException: The argument does not represent an annotation type: PythonApplication`

Requested outcomes:
1. Add a way to diagnose failures (preferably `-v` / `--verbose` stack trace mode).
2. Add a hint in non-verbose fatal output telling users how to rerun with verbose.
3. Use improved diagnostics to investigate likely classpath/annotation-processing cause.

### Interview Summary
**Decisions captured**:
- Verbose scope: **process-only**.
- Default fatal output: **message + hint**.
- Test strategy: **YES (Tests-after)**.

**Known behavior confirmed**:
- Failure is reproducible in the target demo app.
- Full stack trace (captured via temporary local debug wrapper) points to:
  - `io.micronaut.annotation.processing.PackageElementVisitorProcessor.process(...)`
  - `RoundEnvironment.getElementsAnnotatedWith(...)`
  - `JavacRoundEnvironment.throwIfNotAnnotation(...)`
- `io.micronaut.python.processing.annotation.PythonApplication` exists and is an annotation type.
- Generated source from `PyronautCompiler` is `pyronaut_application.PyronautMain` with `@PythonApplication(...)`.

### Metis Review
**Identified gaps (addressed in this plan)**:
- Need explicit guardrails to prevent scope creep into broad processor refactors.
- Need explicit output-contract acceptance criteria (verbose vs non-verbose shape).
- Need explicit edge-case coverage (cause-chain, help output includes `-v/--verbose`).
- Need explicit diagnosis artifact after verbose rollout.

---

## Work Objectives

### Core Objective
Improve `pyronaut process` failure diagnosability without degrading default UX, and produce a concrete diagnosis path for the current `PythonApplication` annotation-type failure.

### Concrete Deliverables
- `pyronaut-processor` command supports `-v, --verbose`.
- Default fatal path prints concise error plus deterministic hint (`Run with --verbose to see the full stack trace.`).
- Verbose path prints full stack trace including cause chain.
- Tests added/updated for verbose behavior in processor command.
- Documentation/examples updated where process error output contract is described.
- Root-cause investigation notes for the demo failure are captured.

### Definition of Done
- [ ] `pyronaut-processor --help` includes `-v, --verbose`.
- [ ] Non-verbose failure prints concise error + verbose hint, without stacktrace frames.
- [ ] Verbose failure prints stacktrace frames (`at ...`) and cause chain (`Caused by:` where applicable).
- [ ] Existing relevant test suites pass.
- [ ] Demo failure diagnosis is documented with command evidence.

### Must Have
- Process-only verbose implementation.
- Stable, deterministic non-verbose hint text.
- Tests-after coverage for new behavior.

### Must NOT Have (Guardrails)
- No broad “global verbose” rollout across install/run/test in this scope.
- No unrelated annotation-processing pipeline refactors.
- No temporary debug wrappers/instrumentation committed.
- No changes to exit-code taxonomy semantics.

---

## Verification Strategy (MANDATORY)

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> ALL tasks in this plan are verified by agent-executed commands only.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: Tests-after
- **Framework**: JUnit 5 + Python unittest/pytest flows in existing repo tasks

### Agent-Executed QA Scenarios (MANDATORY)

Scenario: Non-verbose process failure is concise and includes hint
  Tool: Bash
  Preconditions: Demo app at `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app` reproduces failure
  Steps:
    1. Run: `pyronaut process --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app 2> .sisyphus/evidence/task-nonverbose.err`
    2. Capture exit code.
    3. Assert stderr contains substring: `Processing failed:`
    4. Assert stderr contains substring: `Run with --verbose to see the full stack trace.`
    5. Assert stderr does **not** contain typical stack frame token: `"\tat "`
  Expected Result: Concise fatal output with deterministic verbose hint and no stack frames.
  Failure Indicators: Missing hint, stacktrace in default mode, missing failure summary.
  Evidence: `.sisyphus/evidence/task-nonverbose.err`

Scenario: Verbose process failure prints stack trace and cause chain
  Tool: Bash
  Preconditions: Same demo app failure condition
  Steps:
    1. Run: `pyronaut process --verbose --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app 2> .sisyphus/evidence/task-verbose.err`
    2. Capture exit code.
    3. Assert stderr contains `Processing failed:` summary line.
    4. Assert stderr contains stack trace frame token `"\tat "`.
    5. Assert stderr contains `The argument does not represent an annotation type`.
    6. Assert `Caused by:` appears when nested cause exists.
  Expected Result: Verbose mode reveals full traceback needed for diagnosis.
  Failure Indicators: No stack frames, missing root cause text.
  Evidence: `.sisyphus/evidence/task-verbose.err`

Scenario: Help output advertises verbose switch
  Tool: Bash
  Preconditions: Built processor executable available
  Steps:
    1. Run: `pyronaut-processor --help > .sisyphus/evidence/task-help.out`
    2. Assert output contains `-v` and `--verbose` in options table.
  Expected Result: Users can discover diagnostics mode from help.
  Failure Indicators: Flag missing from help.
  Evidence: `.sisyphus/evidence/task-help.out`

Scenario: Regression safety on orchestrator command forwarding
  Tool: Bash + Python tests
  Preconditions: Repository test env set
  Steps:
    1. Run relevant Python orchestrator tests for argument forwarding.
    2. Assert command delegation tests still pass with existing command argument behavior.
  Expected Result: No breakage in command forwarding semantics.
  Failure Indicators: Forwarding tests fail, `-v`/`--verbose` altered unexpectedly.
  Evidence: Test output logs.

---

## Execution Strategy

### Parallel Execution Waves

Wave 1 (start immediately):
├── Task 1: Add verbose option + non-verbose hint behavior in processor
└── Task 2: Add/update processor-focused tests (depends on Task 1)

Wave 2 (after Wave 1):
├── Task 3: Update docs/examples for new verbose diagnostics behavior
└── Task 4: Run verbose-based diagnosis on failing demo app and capture findings

Critical Path: Task 1 → Task 2 → Task 4

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|----------------------|
| 1 | None | 2, 3, 4 | None |
| 2 | 1 | 4 | 3 |
| 3 | 1 | None | 2 |
| 4 | 1,2 | Final report | 3 |

### Agent Dispatch Summary

| Wave | Tasks | Recommended Agents |
|------|-------|-------------------|
| 1 | 1,2 | quick / unspecified-low (Java CLI + tests) |
| 2 | 3,4 | writing (docs) + quick (diagnostic command verification) |

---

## TODOs

- [ ] 1. Add `-v/--verbose` failure diagnostics to `pyronaut-processor`

  **What to do**:
  - Add option field in `PyronautProcessorMain`:
    - `@CommandLine.Option(names = {"-v", "--verbose"}, ...)`
  - Adjust fatal exception handling path so:
    - Default: concise error + hint line
    - Verbose: print stack trace (including cause chain)
  - Keep existing exit-code behavior unchanged.

  **Must NOT do**:
  - Do not change install/run/test behavior.
  - Do not change error taxonomy codes.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: single-command CLI behavior enhancement in one module.
  - **Skills**: [`git-master`]
    - `git-master`: precise, minimal edits with clean test deltas.
  - **Skills Evaluated but Omitted**:
    - `playwright`: not browser-related.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 1
  - **Blocks**: 2, 3, 4
  - **Blocked By**: None

  **References**:
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorMain.java` - option parsing and fatal catch blocks.
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorExitCode.java` - preserve existing process error mapping.
  - `pyronaut-cli-v2/docs/cli-protocol.md` - stream/error contract expectations.

  **Acceptance Criteria**:
  - [ ] `pyronaut-processor --help` includes `-v, --verbose`
  - [ ] Default failure output includes hint `Run with --verbose to see the full stack trace.`
  - [ ] Default failure output excludes stack trace frames (`\tat`)
  - [ ] Verbose failure output includes stack frames and cause chain

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Non-verbose process failure emits concise message + hint
    Tool: Bash
    Preconditions: Demo app failure still reproducible
    Steps:
      1. pyronaut process --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app 2> .sisyphus/evidence/task-1-nonverbose.err
      2. Assert .sisyphus/evidence/task-1-nonverbose.err contains "Processing failed:"
      3. Assert .sisyphus/evidence/task-1-nonverbose.err contains "Run with --verbose"
      4. Assert .sisyphus/evidence/task-1-nonverbose.err does NOT contain "\tat "
    Expected Result: concise output + hint, no stack trace frames
    Evidence: .sisyphus/evidence/task-1-nonverbose.err

  Scenario: Verbose process failure emits stack trace
    Tool: Bash
    Preconditions: Same demo app failure condition
    Steps:
      1. pyronaut process --verbose --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app 2> .sisyphus/evidence/task-1-verbose.err
      2. Assert .sisyphus/evidence/task-1-verbose.err contains "Processing failed:"
      3. Assert .sisyphus/evidence/task-1-verbose.err contains "\tat "
      4. Assert .sisyphus/evidence/task-1-verbose.err contains "Caused by:"
    Expected Result: full stack trace and cause chain available
    Evidence: .sisyphus/evidence/task-1-verbose.err
  ```

- [ ] 2. Add tests-after coverage for verbose and non-verbose failure modes

  **What to do**:
  - Extend `PyronautProcessorMainTest` with controlled failing executor scenario.
  - Assert non-verbose stderr contract.
  - Assert verbose stderr contract.
  - Keep existing tests green.

  **Must NOT do**:
  - No brittle assertions on full stack trace text; assert stable anchors.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: focused test additions around one command class.
  - **Skills**: [`git-master`]

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 1
  - **Blocks**: 4
  - **Blocked By**: 1

  **References**:
  - `pyronaut-processor/src/test/java/io/micronaut/pyronaut/processor/PyronautProcessorMainTest.java` - existing test harness patterns.
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautCompilerExecutor.java` - mock/failing executor seam.

  **Acceptance Criteria**:
  - [ ] New non-verbose failure test passes
  - [ ] New verbose failure test passes
  - [ ] Existing processor tests remain green

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Processor tests validate verbose/non-verbose behavior
    Tool: Bash
    Preconditions: Task 2 test additions are implemented
    Steps:
      1. ./gradlew :micronaut-pyronaut-processor:test
      2. Assert test task exits 0
      3. Assert output includes new test names for verbose/non-verbose failure behavior
    Expected Result: new and existing processor tests all pass
    Evidence: Gradle test output log
  ```

- [ ] 3. Update docs/examples for process diagnostics behavior

  **What to do**:
  - Update process failure example(s) to include verbose hint behavior.
  - Update command help/docs where process flags are documented.

  **Must NOT do**:
  - No speculative root-cause claims in user docs.

  **Recommended Agent Profile**:
  - **Category**: `writing`
    - Reason: primarily docs and UX text consistency.
  - **Skills**: [`git-master`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 4)
  - **Blocks**: None
  - **Blocked By**: 1

  **References**:
  - `pyronaut-cli-v2/docs/examples/process-failure.json` - process failure contract sample.
  - `pyronaut-cli-v2/docs/cli-protocol.md` - stderr/contract narrative.

  **Acceptance Criteria**:
  - [ ] Docs mention `-v/--verbose` for process diagnostics
  - [ ] Failure example reflects new hint behavior

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Documentation reflects process verbose diagnostics contract
    Tool: Bash
    Preconditions: Docs updated
    Steps:
      1. Search updated docs/examples for "--verbose"
      2. Search process-failure example for rerun hint wording
      3. Verify references use process command context
    Expected Result: docs and examples reflect actual command behavior
    Evidence: matching file paths and content snippets
  ```

- [ ] 4. Diagnose and record root cause path using verbose output in demo app

  **What to do**:
  - Run `pyronaut process --verbose` in target demo app.
  - Capture full stderr evidence.
  - Correlate with processor chain (`PackageElementVisitorProcessor` path).
  - Produce diagnosis summary with confidence level and next actionable fix candidates.

  **Must NOT do**:
  - Do not silently broaden into unrelated processor refactor work.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: debugging and synthesis across javac/processor boundaries.
  - **Skills**: [`git-master`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Task 3)
  - **Blocks**: Final completion
  - **Blocked By**: 1, 2

  **References**:
  - `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app/pyproject.toml` - reproduction target config.
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorMain.java` - fatal error emission path.
  - OpenJDK `JavacRoundEnvironment.throwIfNotAnnotation` behavior (external diagnostic reference).

  **Acceptance Criteria**:
  - [ ] Verbose evidence file captured for failing demo run
  - [ ] Diagnosis summary identifies failing component path and likely cause class
  - [ ] Summary includes reproducible command set used for diagnosis

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Root-cause diagnosis capture with verbose output
    Tool: Bash
    Preconditions: Task 1 complete and demo app available
    Steps:
      1. pyronaut process --verbose --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app 2> .sisyphus/evidence/task-4-diagnosis.err
      2. Assert captured stderr includes PackageElementVisitorProcessor stack frame
      3. Assert captured stderr includes "The argument does not represent an annotation type"
      4. Record diagnosis summary with suspected root-cause classpath/symbol-resolution path
    Expected Result: reproducible diagnosis artifact is available for follow-up fixing
    Evidence: .sisyphus/evidence/task-4-diagnosis.err
  ```

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 1+2 | `feat(processor): add verbose stacktrace mode for process failures` | `pyronaut-processor/*` tests | `./gradlew :micronaut-pyronaut-processor:test` |
| 3 | `docs(cli): document process verbose diagnostics hint` | `pyronaut-cli-v2/docs/*` | docs lint/check if available |
| 4 | `docs(troubleshooting): capture PythonApplication process failure diagnosis` | selected docs/report file | reproduce command logs |

---

## Success Criteria

### Verification Commands
```bash
./gradlew :micronaut-pyronaut-processor:test
./gradlew :micronaut-pyronaut:testPythonOrchestrator
pyronaut process --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app
pyronaut process --verbose --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app
```

### Final Checklist
- [ ] Process-only verbose mode implemented
- [ ] Non-verbose fatal message includes rerun hint
- [ ] Verbose mode emits actionable stack trace
- [ ] Automated tests added and passing
- [ ] Root-cause diagnosis for current failure documented
