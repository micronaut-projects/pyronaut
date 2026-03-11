# Pyronaut SDK v2 Re-architecture

## TL;DR

> **Quick Summary**: Build a new `pyronaut-cli-v2` architecture with independent CLIs (`install`, `processor`, `run`, `test`) plus a Python `pyronaut` orchestrator, replacing monolithic classloader-heavy behavior with contract-first process boundaries and deterministic dependency/cache behavior.
>
> **Deliverables**:
> - `pyronaut-config-model` shared typed config module (`pyproject.toml` only)
> - New CLI modules: `pyronaut-install`, `pyronaut-processor`, `pyronaut-run`, `pyronaut-test`
> - Python orchestrator command `pyronaut` delegating to independent CLIs
> - Native-image binaries for install + processor (macOS/Linux phase 1)
> - SDK wheel packaging module and CI path for PyPI publication
>
> **Estimated Effort**: XL
> **Parallel Execution**: YES - 4 waves
> **Critical Path**: Contract + config model → install → processor → orchestrator/wheel

---

## Context

### Original Request
Re-architect Pyronaut from monolithic `pyronaut-cli/` into modular v2 CLIs with separate responsibilities, native-image for install/processor, JVM-first run/test, and wheel-based SDK distribution.

### Interview Summary
**Key Discussions / Confirmed Decisions**:
- Test strategy: **TDD for all modules**.
- Config filename policy: **strict `pyproject.toml`**.
- Orchestrator UX: **`pyronaut process` delegates to `pyronaut-processor`**.
- Orchestrator implementation language (phase 1): **Python**.
- Phase-1 platform matrix: **macOS + Linux first**.
- TUI rework: **out of scope (phase 2)**.
- Phase-1 default wheel strategy: **platform-specific SDK wheels for macOS/Linux**.
- Phase-1 default runtime baseline: align with current repo toolchain conventions (Gradle CI Java 21/25; Micronaut/Pyronaut snapshots already used in repo).
- `pyronaut-test` CLI is introduced as new executable surface, while reusing existing `pyronaut-pytest` engine module internals.

**Research Findings**:
- Current monolith command entrypoint: `pyronaut-cli/.../PyronautMainCommand.java`.
- Current install path uses Gradle Tooling API + template Gradle scripts; v2 should switch to Maven Resolver.
- Current classloader-heavy hotspots: `PyronautFileWatcher`, `PyronautCliCompiler`, reflective application manager loading.
- Existing dependency scope semantics already map to Python-oriented scopes (`runtime`, `build`, `test`) via `DependencyScopes`.
- `pyproject.toml` is canonical in repo artifacts.
- Local Maven cache confirms `PyronautCompiler` builder API fields needed by `pyronaut-processor`.

### Metis Review
**Identified Gaps (addressed in this plan):**
- Missing explicit orchestrator↔sub-CLI contract → Added protocol-first task and schema artifacts.
- Missing deterministic cache/offline/concurrency checks → Added install cache key + lock/invalidation tasks and QA.
- Missing explicit phase-1 non-goals → Added strict guardrails.
- Missing parity/smoke acceptance surface → Added end-to-end integration and parity wave.
- Missing wheel strategy clarity → Defaulted to platform-specific wheels for macOS/Linux in phase 1.

---

## Work Objectives

### Core Objective
Deliver a modular Pyronaut SDK v2 with independent executable responsibilities, deterministic dependency/classpath contracts, and wheel-based distribution, while preserving core user workflows (`install`, `process`, `run`, `test`) and enabling safe incremental migration.

### Concrete Deliverables
- New shared module: `pyronaut-config-model`.
- New CLI modules: `pyronaut-install`, `pyronaut-processor`, `pyronaut-run`, `pyronaut-test`.
- New Python orchestrator module (`pyronaut` command).
- Native-image build/test path for install and processor.
- SDK wheel packaging module including CLI assets and Python entrypoint.
- Integration/parity test suite using `pyronaut-demo/app` scenario.

### Definition of Done
- [ ] `./gradlew check` passes for all newly added/modified v2 modules.
- [ ] `pyronaut install/process/run/test` orchestrator flow works on sample project with v2 stack.
- [ ] `pyronaut-install` and `pyronaut-processor` native binaries pass smoke + functional tests on macOS/Linux CI.
- [ ] Wheel install in clean venv exposes `pyronaut` and executes end-to-end v2 flow.

### Must Have
- Strict scope separation: build (annotation processor), runtime, test.
- Strict `pyproject.toml` parsing/modeling.
- Deterministic cache behavior with invalidation on config changes.
- TDD workflow per component.
- Review/commit checkpoint after each component.

### Must NOT Have (Guardrails)
- No TUI redesign in phase 1.
- No plugin ecosystem introduction in phase 1.
- No Windows deliverable in phase 1.
- No reintroduction of monolithic shared classloader orchestration from v1.
- No manual QA-only acceptance criteria.

---

## Verification Strategy (MANDATORY)

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> ALL tasks are verified by agent-executable commands and tool-driven scenarios.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: TDD
- **Framework**: Gradle + JUnit Platform/JUnit Jupiter (+ existing pyronaut-pytest integration where relevant)

### If TDD Enabled
For each implementation task:
1. **RED**: Add failing tests for new contract/behavior.
2. **GREEN**: Implement minimum behavior.
3. **REFACTOR**: Improve structure while keeping tests green.

### Agent-Executed QA Scenarios (MANDATORY)
Primary verification uses Bash/CLI and integration runs.

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation)
├── Task 1: Protocol + contracts + error taxonomy
└── Task 2: pyronaut-config-model

Wave 2 (Core engines)
├── Task 3: pyronaut-install (Maven Resolver + cache)
└── Task 4: pyronaut-install native-image path

Wave 3 (Processing + runtime CLIs)
├── Task 5: pyronaut-processor (JVM implementation)
├── Task 6: pyronaut-processor native-image path
├── Task 7: pyronaut-run (JVM)
└── Task 8: pyronaut-test (JVM)

Wave 4 (Composition + distribution)
├── Task 9: Python orchestrator `pyronaut`
├── Task 10: SDK wheel packaging
└── Task 11: End-to-end parity/integration suite + migration gate
```

Critical Path: 1 → 2 → 3 → 5 → 9 → 10 → 11

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|----------------------|
| 1 | None | 3,5,9,11 | 2 |
| 2 | None | 3,5,7,8 | 1 |
| 3 | 1,2 | 4,5,7,8,9,11 | 4 |
| 4 | 3 | 11 | None |
| 5 | 1,2,3 | 6,7,8,9,11 | 7,8 |
| 6 | 5 | 11 | 7,8 |
| 7 | 2,3,5 | 9,11 | 8 |
| 8 | 2,3,5 | 9,11 | 7 |
| 9 | 1,3,5,7,8 | 10,11 | 10 |
| 10 | 9 | 11 | None |
| 11 | 4,6,9,10 | None | None |

### Agent Dispatch Summary

| Wave | Tasks | Recommended Agents |
|------|-------|-------------------|
| 1 | 1-2 | `task(category="unspecified-high")` |
| 2 | 3-4 | `task(category="unspecified-high")` |
| 3 | 5-8 | `task(category="unspecified-high")` (run/test may execute parallel) |
| 4 | 9-11 | `task(category="unspecified-high")`, wheel task may use writing-focused agent for packaging docs/scripts |

---

## TODOs

> **Merged Plan Note (2026-03-11)**
>
> This main plan now absorbs:
> - `.sisyphus/plans/pyronaut-install-usability-improvements.md`
> - `.sisyphus/plans/pyronaut-process-verbose-diagnostics-and-pythonapplication-investigation.md`
>
> Status retention rule:
> - Install-usability tasks are imported as completed where already verified.
> - Process verbose diagnostics tasks are imported as pending under new merged task 13.

- [x] 1. Define v2 CLI protocol, contracts, and exit-code taxonomy

  **What to do**:
  - Create protocol spec for orchestrator↔sub-CLI communication (structured JSON output mode + stderr logging rules).
  - Define stable exit-code taxonomy and error categories across all CLIs.
  - Define capability/version command (`--capabilities --json`) contract.

  **Must NOT do**:
  - Do not couple contract to one module’s internal classes.
  - Do not rely on unstructured console output for orchestration.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `writing`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Task 2)
  - **Blocks**: 3,5,9,11
  - **Blocked By**: None

  **References**:
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/PyronautMainCommand.java` - current dispatch shape baseline.
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/commands/PyronautRunCommand.java` - current run command interface.
  - Oracle architecture guidance from consultation (session output) - contract-first and fallback model.

  **Acceptance Criteria**:
  - [x] Protocol spec committed in v2 module docs with JSON examples for success/failure.
  - [x] Exit-code table includes deterministic mapping for usage/config/resolution/compile/runtime/internal failures.
  - [x] `--capabilities --json` schema defined and referenced by orchestrator plan.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Protocol schema validation passes
    Tool: Bash
    Preconditions: Spec and JSON samples exist in repo
    Steps:
      1. Run schema validation command against sample success/failure payloads
      2. Assert all samples validate with exit code 0
      3. Save validation output
    Expected Result: All contract samples pass schema checks
    Evidence: .sisyphus/evidence/task-1-protocol-schema.txt

  Scenario: Unknown command error taxonomy sample
    Tool: Bash
    Preconditions: Error taxonomy doc includes unknown-command mapping
    Steps:
      1. Run documented check command for unknown command behavior
      2. Assert non-zero code matches taxonomy
      3. Assert stderr includes standardized code/message token
    Expected Result: Contracted error mapping is deterministic
    Evidence: .sisyphus/evidence/task-1-error-taxonomy.txt
  ```

- [x] 2. Build `pyronaut-config-model` (typed record model + strict `pyproject.toml` parsing)

  **What to do**:
  - Add new shared module for reading/modeling `pyproject.toml` using typed Java records.
  - Model project/build-system/tool.pyronaut repositories + runtime/build/test dependencies.
  - Enforce strict filename policy: `pyproject.toml` only.
  - Add exhaustive parser/model tests from real sample fixtures.

  **Must NOT do**:
  - Do not accept `pyroject.toml`.
  - Do not leave dynamic `TomlParseResult` usage in new v2 callsites.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `none`

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Task 1)
  - **Blocks**: 3,5,7,8
  - **Blocked By**: None

  **References**:
  - `pyronaut-demo/app/pyproject.toml` - canonical shape to support.
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/commands/AbstractPyronautDependencyResolutionAwareCommand.java` - current dynamic access patterns to replace.
  - `pyronaut-cli/src/test/java/io/micronaut/python/cli/commands/PyronautInstallCommandTest.java` - TOML edge-case testing style.

  **Acceptance Criteria**:
  - [x] RED: failing tests for parsing sample pyproject + invalid cases.
  - [x] GREEN: parser maps all required fields to typed records.
  - [x] REFACTOR: parser internals cleaned with full test pass.
  - [x] Invalid filename or missing `pyproject.toml` yields deterministic error.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Parse canonical pyproject fixture
    Tool: Bash
    Preconditions: Test fixture at pyronaut-demo/app/pyproject.toml
    Steps:
      1. Run module tests for config model parser
      2. Assert test "parseCanonicalPyproject" passes
      3. Capture test report XML path
    Expected Result: Typed model produced with expected scope lists
    Evidence: .sisyphus/evidence/task-2-config-tests.txt

  Scenario: Reject wrong filename
    Tool: Bash
    Preconditions: Test creates only pyroject.toml
    Steps:
      1. Run failing-input test case
      2. Assert error code/category equals CONFIG_FILE_NOT_FOUND
      3. Assert message mentions required filename pyproject.toml
    Expected Result: Strict policy enforced
    Evidence: .sisyphus/evidence/task-2-strict-filename.txt
  ```

- [x] 3. Implement `pyronaut-install` with Maven Resolver + deterministic scoped cache

  **What to do**:
  - Implement scoped dependency resolution via Apache Maven Resolver for build/runtime/test.
  - Produce `.pytest_cache` outputs:
    - `resolved-build-dependencies`
    - `resolved-runtime-dependencies`
    - `resolved-test-dependencies`
  - Cache key must include `pyproject.toml` hash + repository/scope inputs.
  - Add offline, stale-cache, and clear error diagnostics behavior.

  **Must NOT do**:
  - Do not reintroduce Gradle Tooling API template-based resolution.
  - Do not merge scope classpaths.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `none`

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 2
  - **Blocks**: 4,5,7,8,9,11
  - **Blocked By**: 1,2

  **References**:
  - Maven Resolver docs: https://maven.apache.org/resolver/resolving-dependencies.html
  - Maven Resolver session setup: https://maven.apache.org/resolver/creating-a-repository-system-session.html
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/commands/PyronautInstallCommand.java` - behavior baseline only.
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/commands/DependencyScopes.java` - current scope semantic mapping.

  **Acceptance Criteria**:
  - [x] RED: tests fail for unresolved scopes/cache miss/hit behavior.
  - [x] GREEN: resolver returns paths for each scope and writes expected cache files.
  - [x] REFACTOR: resolution code organized with clear diagnostics and retry boundaries.
  - [x] Re-run without pyproject change uses cache (no re-resolve).
  - [x] Resolver failure surfaces root cause (artifact/repo/proxy).

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Initial install writes all scoped cache manifests
    Tool: Bash
    Preconditions: pyproject.toml exists in target app root
    Steps:
      1. Run: pyronaut-install --project .
      2. Assert files exist: .pytest_cache/resolved-build-dependencies, resolved-runtime-dependencies, resolved-test-dependencies
      3. Assert each file has newline-separated absolute jar paths
      4. Save command output and file checks
    Expected Result: Three scope manifests produced successfully
    Evidence: .sisyphus/evidence/task-3-install-scope-cache.txt

  Scenario: Second run reuses cache when unchanged
    Tool: Bash
    Preconditions: Cache manifests already generated
    Steps:
      1. Run pyronaut-install again with unchanged pyproject.toml
      2. Assert output includes cache-hit indicator
      3. Assert manifest timestamps unchanged or stable according to policy
    Expected Result: No full re-resolution
    Evidence: .sisyphus/evidence/task-3-cache-hit.txt

  Scenario: Resolution failure returns clear diagnostics
    Tool: Bash
    Preconditions: Add invalid dependency coordinate in test fixture
    Steps:
      1. Run pyronaut-install against invalid fixture
      2. Assert non-zero exit code maps to RESOLUTION_ERROR
      3. Assert stderr includes failing coordinate/repository cause
    Expected Result: Actionable failure message
    Evidence: .sisyphus/evidence/task-3-resolution-failure.txt
  ```

- [x] 4. Build and verify native image for `pyronaut-install`

  **What to do**:
  - Add native-image build configuration for install CLI.
  - Ensure install native binary works on phase-1 target platforms (macOS/Linux).
  - Add smoke and functional native tests in CI.

  **Must NOT do**:
  - Do not block JVM fallback for local development.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (after Task 3)
  - **Blocks**: 11
  - **Blocked By**: 3

  **References**:
  - `pyronaut-cli/src/main/resources/io/micronaut/python/cli/commands/native.build.gradle` - current native argument style baseline.
  - Graal Native Build Configuration docs: https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildConfiguration/

  **Acceptance Criteria**:
  - [x] Native binary builds in CI matrix for macOS/Linux.
  - [x] Native binary executes install workflow and writes scoped cache files.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Native install smoke
    Tool: Bash
    Preconditions: Native binary built
    Steps:
      1. Run: ./build/native/nativeCompile/pyronaut-install --version
      2. Run: ./build/native/nativeCompile/pyronaut-install --project pyronaut-demo/app
      3. Assert cache manifests exist
    Expected Result: Native install binary is functional
    Evidence: .sisyphus/evidence/task-4-native-install-smoke.txt

  Scenario: Native install invalid pyproject path failure
    Tool: Bash
    Preconditions: Native binary built; invalid project path fixture
    Steps:
      1. Run native install with missing pyproject.toml
      2. Assert non-zero exit code mapped to CONFIG_FILE_NOT_FOUND
      3. Assert stderr clearly indicates required pyproject.toml
    Expected Result: Clear, deterministic failure behavior
    Evidence: .sisyphus/evidence/task-4-native-install-failure.txt
  ```

- [x] 5. Implement `pyronaut-processor` CLI using `PyronautCompiler`

  **What to do**:
  - Create processor CLI module invoking `io.micronaut.python.compiler.PyronautCompiler` builder API.
  - Wire defaults:
    - `pythonSrc=src`
    - `targetDir=__pyronaut__/classes`
    - `javaSrc=src-java`
    - `annotationProcessorPath` from build scope cache
    - `classpath` from runtime scope cache
  - Expose supported CLI args for builder fields; keep internal fields non-exposed.

  **Must NOT do**:
  - Do not expose internal unsupported fields as CLI options.
  - Do not run with merged build/runtime classpath.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3
  - **Blocks**: 6,7,8,9,11
  - **Blocked By**: 1,2,3

  **References**:
  - Local source JAR class: `io/micronaut/python/compiler/PyronautCompiler.java` (5.0.0-SNAPSHOT) in local Maven cache.
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/PyronautCliCompiler.java` - existing compiler invocation baseline.
  - User-provided upstream source link for builder fields.

  **Acceptance Criteria**:
  - [x] RED: tests fail for defaults and classpath wiring.
  - [x] GREEN: processor generates expected Micronaut metadata/classes for demo app.
  - [x] REFACTOR: code clean with complete tests.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Processor default paths generate output
    Tool: Bash
    Preconditions: install cache manifests exist
    Steps:
      1. Run: pyronaut-processor --project pyronaut-demo/app
      2. Assert output dir exists: pyronaut-demo/app/__pyronaut__/classes
      3. Assert generated classes/resources are present
    Expected Result: Processor runs with defaults and outputs metadata
    Evidence: .sisyphus/evidence/task-5-processor-defaults.txt

  Scenario: Missing build cache fails clearly
    Tool: Bash
    Preconditions: remove/rename resolved-build-dependencies in fixture
    Steps:
      1. Run pyronaut-processor
      2. Assert non-zero code mapped to CONFIG_OR_CACHE_ERROR
      3. Assert message instructs to run pyronaut-install first
    Expected Result: Fast, actionable failure
    Evidence: .sisyphus/evidence/task-5-processor-cache-miss.txt
  ```

- [x] 6. Build and verify native image for `pyronaut-processor` *(deferred to JVM fallback while GraalVM issue remains)*

  **What to do**:
  - Add native-image/Crema-compatible configuration for processor CLI.
  - Validate javac + Micronaut annotation processing path in native mode.
  - Include fallback behavior definition if native backend unavailable.
  - Keep native path opt-in and default execution path on JVM until native blocker is fixed upstream.

  **Must NOT do**:
  - Do not make native path the only execution mode.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 7-8)
  - **Blocks**: 11
  - **Blocked By**: 5

  **References**:
  - GraalVM metadata/tracing docs (native-image).
  - User reference: micronaut-core PR pattern for javac + processors native integration.

  **Acceptance Criteria**:
  - [ ] Native processor binary builds on macOS/Linux CI. *(currently blocked by GraalVM native-image issue)*
  - [ ] Native processor runs sample processing flow and produces metadata. *(currently blocked by GraalVM native-image issue)*
  - [x] JVM fallback is the default execution mode and native tasks are explicit opt-in.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Native processor functional run
    Tool: Bash
    Preconditions: Native binary built; install cache exists
    Steps:
      1. Run native processor against demo app
      2. Assert generated output exists under __pyronaut__/classes
      3. Assert process exit code 0 and logs show successful compilation
    Expected Result: Native processor usable for core path
    Evidence: .sisyphus/evidence/task-6-native-processor.txt

  Scenario: Native processor invalid source dir failure
    Tool: Bash
    Preconditions: Native binary built
    Steps:
      1. Run native processor with non-existent --pythonSrc path
      2. Assert non-zero exit code and failure category COMPILATION_CONFIG_ERROR
      3. Assert stderr includes offending path
    Expected Result: Invalid input fails with actionable diagnostics
    Evidence: .sisyphus/evidence/task-6-native-processor-failure.txt
  ```

- [x] 7. Implement `pyronaut-run` JVM CLI

  **What to do**:
  - Implement JVM command to launch app with:
    - runtime classpath from install cache
    - processed classes from processor output
    - `config` directory on classpath
  - Keep feature set minimal (no auto-restart enhancements in phase 1).

  **Must NOT do**:
  - No phase-2 watch/logging enhancements.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Task 8; and 6 after 5)
  - **Blocks**: 9,11
  - **Blocked By**: 2,3,5

  **References**:
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/DefaultApplicationManager.java` - current launch style baseline.
  - `pyronaut-demo/app/config` - config directory example.

  **Acceptance Criteria**:
  - [x] TDD tests for classpath assembly and launch preconditions.
  - [x] Run command starts demo app using processed output.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: JVM run launches demo app
    Tool: Bash
    Preconditions: install + process completed for demo app
    Steps:
      1. Start: pyronaut-run --project pyronaut-demo/app
      2. Wait for startup line indicating server running
      3. curl http://localhost:8080 and assert expected response body
      4. Stop process and assert clean shutdown
    Expected Result: App launches and responds over HTTP
    Evidence: .sisyphus/evidence/task-7-run-http.txt

  Scenario: JVM run without processed classes fails preflight
    Tool: Bash
    Preconditions: Remove __pyronaut__/classes in fixture
    Steps:
      1. Run pyronaut-run --project fixture
      2. Assert non-zero exit code PRECONDITION_FAILED
      3. Assert stderr instructs to run pyronaut process first
    Expected Result: Missing preconditions fail fast
    Evidence: .sisyphus/evidence/task-7-run-preflight-failure.txt
  ```

- [x] 8. Implement `pyronaut-test` JVM CLI

  **What to do**:
  - Implement test command using existing pyronaut-pytest/JUnit infrastructure via new CLI entrypoint.
  - Assemble classpath from test scope + processed classes + config dir.

  **Must NOT do**:
  - No native-image requirement for test command in phase 1.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Task 7)
  - **Blocks**: 9,11
  - **Blocked By**: 2,3,5

  **References**:
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/TestApplicationManager.java` - current launcher semantics.
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/PytestTestEngine.java` - existing engine integration.

  **Acceptance Criteria**:
  - [x] TDD tests for classpath assembly and test invocation.
  - [x] CLI executes pytest-backed tests and returns deterministic exit code.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: JVM test command executes pytest integration
    Tool: Bash
    Preconditions: install + process completed; test fixture present
    Steps:
      1. Run: pyronaut-test --project pyronaut-demo/app
      2. Assert test summary output present
      3. Assert exit code 0 for passing fixture
    Expected Result: Tests run through new CLI entrypoint
    Evidence: .sisyphus/evidence/task-8-test-cli.txt

  Scenario: Failing test returns non-zero
    Tool: Bash
    Preconditions: fixture with intentional failing test
    Steps:
      1. Run pyronaut-test against failing fixture
      2. Assert non-zero exit and failure summary present
    Expected Result: Proper failure propagation
    Evidence: .sisyphus/evidence/task-8-test-failure.txt
  ```

- [x] 9. Implement Python `pyronaut` orchestrator CLI

  **What to do**:
  - Build Python command that delegates:
    - `pyronaut install` → `pyronaut-install`
    - `pyronaut process` → `pyronaut-processor`
    - `pyronaut run` → ensure install+process then `pyronaut-run`
    - `pyronaut test` → ensure install+process then `pyronaut-test`
  - Enforce protocol and exit-code handling from Task 1.

  **Must NOT do**:
  - Do not embed install/processor/run/test implementation logic in orchestrator.
  - Do not require Java for orchestrator startup itself.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4
  - **Blocks**: 10,11
  - **Blocked By**: 1,3,5,7,8

  **References**:
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/PyronautMainCommand.java` - command semantics baseline.
  - Metis/Oracle guidance emphasizing contract-first delegation and fallback.

  **Acceptance Criteria**:
  - [x] Subcommand delegation works with stable argument forwarding.
  - [x] `run` and `test` preflight install/process only when needed.
  - [x] Exit codes and stderr/stdout handling conform to protocol.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Orchestrator run performs coordinated flow
    Tool: Bash
    Preconditions: CLI binaries discoverable in PATH or bundled location
    Steps:
      1. Run: pyronaut run --project pyronaut-demo/app
      2. Assert output shows delegated install + process before run
      3. Assert app starts and responds on expected endpoint
    Expected Result: Ordered delegation succeeds end-to-end
    Evidence: .sisyphus/evidence/task-9-orchestrator-run.txt

  Scenario: Delegated process command naming contract
    Tool: Bash
    Preconditions: orchestrator installed
    Steps:
      1. Run: pyronaut process --project pyronaut-demo/app
      2. Assert invoked executable is pyronaut-processor
      3. Assert process output generated
    Expected Result: User-facing command maps correctly
    Evidence: .sisyphus/evidence/task-9-process-delegation.txt
  ```

- [x] 10. Build SDK wheel distribution for phase-1 platforms

  **What to do**:
  - Add v2 SDK wheel module/package flow using Gradle-driven wheel build.
  - Include orchestrator + required CLI assets/binaries for macOS/Linux phase 1.
  - Expose `pyronaut` entrypoint via wheel metadata.
  - Ensure wheel installation path finds bundled CLI executables reliably.

  **Must NOT do**:
  - No Windows packaging in phase 1.
  - No TUI packaging in phase 1.

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (after Task 9)
  - **Blocks**: 11
  - **Blocked By**: 9

  **References**:
  - Prior pyronaut wheel pattern research (BuildWheelTask + pyproject entrypoint + asset include).
  - `pyronaut-projectgen/src/main/java/io/micronaut/pyronaut/projectgen/PyProjectToml.java` - existing Python packaging defaults context.

  **Acceptance Criteria**:
  - [ ] Wheel builds for macOS/Linux targets in CI pipeline.
  - [x] `pip install <wheel>` exposes `pyronaut` command in clean venv.
  - [x] Executing `pyronaut --version` and `pyronaut process` works from installed wheel.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Wheel install and entrypoint smoke
    Tool: Bash
    Preconditions: Wheel artifact built
    Steps:
      1. python -m venv .venv-e2e && source .venv-e2e/bin/activate
      2. pip install dist/pyronaut*.whl
      3. Run: pyronaut --version
      4. Assert exit code 0 and version output
    Expected Result: Wheel entrypoint functional
    Evidence: .sisyphus/evidence/task-10-wheel-smoke.txt

  Scenario: Unsupported platform messaging (phase-1 guardrail)
    Tool: Bash
    Preconditions: Test harness forcing unsupported platform detection path
    Steps:
      1. Execute orchestrator platform check in unsupported-platform fixture/mocked env
      2. Assert deterministic non-zero exit code PLATFORM_UNSUPPORTED
      3. Assert stderr contains explicit "macOS/Linux only in phase 1" guidance
    Expected Result: Guardrail enforced with clear message
    Evidence: .sisyphus/evidence/task-10-platform-guardrail.txt
  ```

- [ ] 11. End-to-end parity, migration gate, and review/commit checkpoint workflow

  **What to do**:
  - Build E2E regression harness for sample project flow:
    - install → process → run → test
  - Add parity checks against expected artifacts/behavior.
  - Insert explicit pause-for-review checkpoints after each major component and commit guidance.

  **Must NOT do**:
  - No automatic phase skipping of review checkpoints.

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Final wave completion
  - **Blocks**: None
  - **Blocked By**: 4,6,9,10

  **References**:
  - `pyronaut-demo/app/pyproject.toml` - canonical integration fixture.
  - `.github/workflows/gradle.yml` and `.github/workflows/graalvm-latest.yml` - CI patterns.

  **Acceptance Criteria**:
  - [ ] Automated E2E scenario passes in CI on macOS/Linux matrix.
  - [x] Checkpoint template exists and is followed:
    - Component complete
    - Review pause
    - If approved: stage + commit
    - Continue to next component

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Full orchestrated E2E flow
    Tool: Bash
    Preconditions: v2 binaries/wheel installed and demo fixture ready
    Steps:
      1. pyronaut install --project pyronaut-demo/app
      2. pyronaut process --project pyronaut-demo/app
      3. pyronaut run --project pyronaut-demo/app (background)
      4. curl app endpoint and assert response
      5. stop run process
      6. pyronaut test --project pyronaut-demo/app
      7. assert all commands succeeded
    Expected Result: End-to-end v2 toolchain functional
    Evidence: .sisyphus/evidence/task-11-e2e-flow.txt

  Scenario: Concurrent install cache lock behavior
    Tool: Bash
    Preconditions: Two install invocations started simultaneously on same project
    Steps:
      1. Start pyronaut install process A
      2. Start pyronaut install process B immediately
      3. Assert no cache corruption and both processes exit deterministically (one lock-waits or one exits with lock-status code per policy)
      4. Validate resulting manifest files are complete and parseable
    Expected Result: Concurrency-safe cache handling
    Evidence: .sisyphus/evidence/task-11-concurrent-install-lock.txt
  ```

- [x] 12. Integrate install usability hardening into v2 canonical plan *(merged from install-usability sub-plan)*

  **What to do**:
  - Consolidate install UX hardening already delivered under v2 architecture:
    - default delegated-path suppression
    - SLF4J single-provider/noise reduction for install flow
    - TTY-aware progress + deterministic non-TTY fallback
    - `--dependencies` tree mode with scope/error formatting
    - expanded invalid-definition/failure tests + wheel E2E coverage
  - Preserve evidence and acceptance in this canonical plan.

  **Status**:
  - Imported from completed plan and retained as delivered.

  **Acceptance Criteria**:
  - [x] Install usability deliverables merged into canonical v2 execution history.
  - [x] Existing verification runs demonstrate no regression for merged install UX scope.

- [ ] 13. Add process verbose diagnostics and root-cause investigation workflow *(merged from process-diagnostics sub-plan)*

  **What to do**:
  - Add `-v/--verbose` to `pyronaut-processor` failure path.
  - Keep non-verbose mode concise with explicit rerun hint to verbose mode.
  - Add tests-after coverage for verbose/non-verbose diagnostics behavior.
  - Update process docs/examples with verbose diagnostics usage.
  - Capture and document root-cause evidence for `PythonApplication` annotation-type failure path.

  **Must NOT do**:
  - Do not broaden to global verbose flags for install/run/test in this task.
  - Do not change exit-code taxonomy semantics.
  - Do not introduce unrelated processor pipeline refactors.

  **Dependencies**:
  - Depends on: 5 (processor CLI), 9 (orchestrator contract), and current test harness.
  - Blocks: final parity sign-off in 11.

  **Acceptance Criteria**:
  - [ ] `pyronaut-processor --help` includes `-v, --verbose`.
  - [ ] Non-verbose fatal path prints concise message + `--verbose` hint, without stacktrace frames.
  - [ ] Verbose path prints actionable stacktrace/cause chain.
  - [ ] Processor/orchestrator tests for verbose behavior pass.
  - [ ] Demo failure diagnosis artifact captured with reproducible command evidence.

---

## Component Review & Commit Strategy (User-required cadence)

After each component group below:
1. Pause for review
2. If approved, stage and commit
3. Proceed to next group

| Checkpoint | Scope | Suggested Commit Message |
|------------|-------|--------------------------|
| C1 | Task 1-2 | `feat(cli-v2): add protocol contracts and config model` |
| C2 | Task 3-4 | `feat(cli-v2): add maven-resolver install command with native image` |
| C3 | Task 5-6 | `feat(cli-v2): add processor command and native pipeline` |
| C4 | Task 7-8 | `feat(cli-v2): add run and test JVM commands` |
| C5 | Task 9-10 | `feat(cli-v2): add python orchestrator and sdk wheel packaging` |
| C6 | Task 11 | `test(cli-v2): add e2e parity and migration gates` |

---

## Success Criteria

### Verification Commands
```bash
./gradlew check
./gradlew test
# plus module-specific native-image tasks for install/processor
```

### Final Checklist
- [ ] All Must Have requirements delivered
- [ ] All Must NOT Have guardrails respected
- [ ] TDD tests present and green for each module
- [ ] E2E orchestrated flow passes on macOS + Linux
- [ ] Review/commit checkpoints executed between components
- [x] Install usability hardening merged and retained as completed in canonical plan
- [ ] Process verbose diagnostics + PythonApplication failure investigation completed and documented
