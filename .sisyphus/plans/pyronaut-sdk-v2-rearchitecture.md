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
> - Dependency-tree unresolved-graph diagnostics are tracked under new merged task 14.
> - Dual source+test processing in `pyronaut-processor` is tracked under new merged task 15.
> - Proxy-aware dependency resolution for `pyronaut-install` is tracked under new merged task 16.
> - `pyronaut-test` classpath alignment with `__pyronaut__/test-classes` is tracked under new merged task 17.
> - `pyronaut-test` JVM warning suppression is tracked under new merged task 18.
> - `pyronaut-processor` progress UX is tracked under new merged task 19.
> - `pyronaut-processor` incremental source caching is tracked under new merged task 20.
> - Orchestrator global cache-bypass propagation is tracked under new merged task 21.
> - Orchestrator `run` preflight coordination hardening is tracked under new merged task 22.
> - Orchestrator `test` preflight coordination hardening is tracked under new merged task 23.
> - JVM debug mode for `pyronaut-run`/`pyronaut-test` is tracked under new merged task 24.

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

- [x] 11. End-to-end parity, migration gate, and review/commit checkpoint workflow

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
  - [x] Automated E2E scenario passes in CI on macOS/Linux matrix.
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
  - [x] `pyronaut-processor --help` includes `-v, --verbose`.
  - [x] Non-verbose fatal path prints concise message + `--verbose` hint, without stacktrace frames.
  - [x] Verbose path prints actionable stacktrace/cause chain.
  - [x] Processor/orchestrator tests for verbose behavior pass.
  - [x] Demo failure diagnosis artifact captured with reproducible command evidence.

- [x] 14. Improve `pyronaut install --dependencies` unresolved graph diagnostics *(merged action item)*

  **What to do**:
  - Improve failure output in `--dependencies` mode so users can still visualize where unresolved artifacts originate.
  - When a dependency cannot be resolved, continue rendering the tree with unresolved nodes/edges marked as failures.
  - Highlight unresolved modules in red when ANSI is enabled, with deterministic plain fallback markers (`ERROR`) in non-color mode.
  - Include dependency path/context leading to unresolved node(s), not only a top-level summary error line.

  **Current observed gap**:
  - Running `pyronaut install --dependencies --scope build` in
    `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app`
    currently prints a generic scope-level error:
    `ERROR: Dependency resolution failed for scope 'build': ...`
    but does not show where unresolved modules sit within the graph path.

  **Must NOT do**:
  - Do not suppress the existing explicit resolution error summary.
  - Do not change existing exit-code taxonomy for resolution failures.
  - Do not require TTY/human-only interpretation for failure diagnostics.

  **Dependencies**:
  - Depends on: 3 (install resolver), 12 (install usability hardening baseline).
  - Blocks: 11 final parity sign-off for dependency diagnostics quality.

  **Acceptance Criteria**:
  - [x] `--dependencies` mode still prints tree structure when resolution failures occur.
  - [x] Unresolved modules are visibly marked in-tree (ANSI red when enabled; plain `ERROR` markers otherwise).
  - [x] Output includes path/context to unresolved artifact(s), not only flat scope-level failure text.
  - [x] Existing and new tests for dependency-tree error rendering pass.
  - [x] `pyronaut install --dependencies --scope build` on the demo app yields actionable graph diagnostics.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Unresolved build dependency still renders graph with highlighted failures
    Tool: Bash
    Preconditions: Demo app contains unresolved build-scope artifact path
    Steps:
      1. Run: pyronaut install --dependencies --scope build --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app > .sisyphus/evidence/task-14-tree.out 2> .sisyphus/evidence/task-14-tree.err
      2. Assert stdout includes "Dependency tree (build):"
      3. Assert stdout includes unresolved module marker in graph output
      4. Assert stderr/stdout includes explicit error summary line
      5. Assert non-zero exit maps to RESOLUTION_ERROR
    Expected Result: Tree context + highlighted unresolved nodes + deterministic failure signal
    Evidence: .sisyphus/evidence/task-14-tree.out, .sisyphus/evidence/task-14-tree.err
  ```

- [x] 15. Extend `pyronaut-process` to process source and test trees with distinct outputs/classpaths *(merged action item)*

  **What to do**:
  - Keep existing main processing behavior:
    - process `src` (and `src-java` where applicable) to `__pyronaut__/classes`
    - use build + runtime classpath behavior for main processing path.
  - Add test processing behavior:
    - process `test` tree to `__pyronaut__/test-classes`
    - use `resolved-test-dependencies` as the primary test processing classpath.
  - Ensure test processing can compile tests with access to source types by compiling source+test as a single logical unit for the test target (or equivalent deterministic mechanism with identical outcome).
  - Define clear invocation contract in `pyronaut-process`/`pyronaut-processor` so both trees are processed in one orchestrated flow.

  **Current behavior baseline**:
  - `pyronaut-processor` currently defaults to one source path and one target path (`__pyronaut__/classes`) and consumes build/runtime manifests.
  - There is no first-class processing pass that emits `__pyronaut__/test-classes` from a test tree using test-scope classpath.

  **Must NOT do**:
  - Do not regress current `src -> __pyronaut__/classes` behavior.
  - Do not collapse main and test outputs into the same target directory.
  - Do not use runtime-only classpath for test-tree processing when test classpath is available.

  **Dependencies**:
  - Depends on: 3 (install scoped manifests), 5 (processor CLI), 8 (test CLI wiring baseline).
  - Blocks: 11 final parity sign-off for end-to-end process/test workflow.

  **Acceptance Criteria**:
  - [x] Main processing still emits `__pyronaut__/classes` from source tree.
  - [x] Test processing emits `__pyronaut__/test-classes` from test tree.
  - [x] Test processing uses install-resolved test classpath (`resolved-test-dependencies`).
  - [x] Tests requiring source types compile/process successfully in the test processing pass.
  - [x] Processor/orchestrator integration tests cover both outputs and classpath selection.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: pyronaut process generates both main and test output trees
    Tool: Bash
    Preconditions: Project has both src and test trees; install manifests generated
    Steps:
      1. Run: pyronaut process --project-dir <app>
      2. Assert directory exists: <app>/__pyronaut__/classes
      3. Assert directory exists: <app>/__pyronaut__/test-classes
      4. Assert both directories contain generated class/metadata outputs
    Expected Result: One process workflow produces distinct main and test outputs
    Evidence: .sisyphus/evidence/task-15-process-dual-output.txt

  Scenario: test-tree processing uses test classpath and resolves source references
    Tool: Bash
    Preconditions: Fixture test code imports/uses types from src
    Steps:
      1. Run: pyronaut install --project-dir <app>
      2. Run: pyronaut process --project-dir <app>
      3. Assert processing exits 0
      4. Assert no classpath/unknown type errors for source types referenced by tests
      5. Assert __pyronaut__/test-classes contains expected generated artifacts
    Expected Result: test processing uses test-scope dependencies and source visibility correctly
    Evidence: .sisyphus/evidence/task-15-test-classpath-source-visibility.txt
  ```

- [x] 16. Add proxy-aware dependency resolution configuration to `pyronaut-install` *(merged action item)*

  **What to do**:
  - Add proxy support for artifact resolution in `pyronaut-install`.
  - Prefer reading proxy configuration from `~/.m2/settings.xml` so users can keep Maven/pyronaut proxy settings in one place.
  - If Maven settings are unavailable or proxy config is absent, support a pyronaut-specific fallback config file:
    - `~/.pyronaut/settings.toml`
  - Define deterministic precedence and override rules (CLI/env > pyronaut settings > Maven settings or equivalent, documented explicitly).
  - Ensure diagnostics make proxy source/selection clear when resolution fails behind a proxy.

  **Must NOT do**:
  - Do not break existing non-proxy resolution defaults.
  - Do not require duplicate proxy configuration if `~/.m2/settings.xml` already provides valid settings.
  - Do not leak credentials in normal logs/error output.

  **Dependencies**:
  - Depends on: 3 (install resolver), 14 (dependency diagnostics clarity).
  - Blocks: 11 parity sign-off for enterprise/proxy environments.

  **Acceptance Criteria**:
  - [x] `pyronaut-install` honors proxy settings from `~/.m2/settings.xml` when present.
  - [x] `pyronaut-install` supports proxy settings from `~/.pyronaut/settings.toml` as fallback.
  - [x] Proxy precedence behavior is documented and test-covered.
  - [x] Resolution succeeds in proxy-configured fixtures and fails with actionable diagnostics when proxy config is invalid.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Maven settings proxy is used for dependency resolution
    Tool: Bash
    Preconditions: Fixture/home setup contains valid ~/.m2/settings.xml proxy configuration
    Steps:
      1. Run: pyronaut install --project-dir <app>
      2. Assert resolution succeeds in proxy-only network fixture
      3. Assert logs/errors do not print proxy credentials
    Expected Result: Maven settings proxy is automatically applied
    Evidence: .sisyphus/evidence/task-16-m2-proxy.txt

  Scenario: Fallback ~/.pyronaut/settings.toml proxy is used when Maven config absent
    Tool: Bash
    Preconditions: No proxy block in ~/.m2/settings.xml; valid ~/.pyronaut/settings.toml proxy config
    Steps:
      1. Run: pyronaut install --project-dir <app>
      2. Assert resolution succeeds in proxy-required fixture
      3. Assert diagnostics mention proxy source selection without secrets
    Expected Result: Fallback proxy settings are honored deterministically
    Evidence: .sisyphus/evidence/task-16-pyronaut-proxy-fallback.txt
  ```

- [x] 17. Ensure `pyronaut-test` uses `__pyronaut__/test-classes` on test execution classpath *(merged action item)*

  **What to do**:
  - Update `pyronaut-test` classpath assembly so generated `__pyronaut__/test-classes` is included for test execution.
  - Preserve inclusion of `__pyronaut__/classes` and config/runtime/build/test dependencies as required by existing behavior.
  - Ensure ordering and visibility are correct so generated test artifacts are preferred for test execution semantics.

  **Must NOT do**:
  - Do not regress existing test command success for projects without test-generated artifacts.
  - Do not drop main generated classes (`__pyronaut__/classes`) from test runtime unless explicitly replaced by a stricter contract.

  **Dependencies**:
  - Depends on: 8 (test CLI), 15 (dual source+test processing outputs).
  - Blocks: 11 parity sign-off for end-to-end test correctness.

  **Acceptance Criteria**:
  - [x] `pyronaut-test` classpath includes `__pyronaut__/test-classes` when present.
  - [x] Existing tests continue to pass with `__pyronaut__/classes` + `__pyronaut__/test-classes` composition.
  - [x] Integration tests verify test-generated artifacts are discoverable during test execution.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: pyronaut-test loads generated test classes
    Tool: Bash
    Preconditions: pyronaut process has emitted __pyronaut__/test-classes
    Steps:
      1. Run: pyronaut test --project-dir <app>
      2. Assert command exits 0 for passing fixture
      3. Assert execution path includes generated test classes lookup
    Expected Result: test execution uses test-classes output on classpath
    Evidence: .sisyphus/evidence/task-17-test-classes-classpath.txt
  ```

- [x] 18. Suppress default `pyronaut-test` JVM warning noise while preserving actionable failures *(merged action item)*

  **What to do**:
  - Add default JVM launch flags for `pyronaut-test` to suppress known runtime warning noise emitted by current GraalVM/JDK combinations during normal test execution:
    - restricted native access warnings (`java.lang.System::load` path)
    - terminally deprecated `sun.misc.Unsafe` warning line noise
  - Align implementation pattern with existing precedent in `pyronaut-cli` application launch defaults.
  - If warning noise still appears from JUL logger categories (e.g. `org.graalvm.python.embedding.VirtualFileSystemImpl warn`), add scoped logging configuration for `pyronaut-test` launch path only.
  - Document default warning-suppression behavior and override guidance.

  **Must NOT do**:
  - Do not suppress or hide real test failures/exceptions.
  - Do not globally mute stderr output for pytest/JUnit execution.
  - Do not apply blanket JVM flags repo-wide without scoping to `pyronaut-test` runtime path.

  **Dependencies**:
  - Depends on: 8 (test CLI), 17 (test classpath behavior baseline).
  - Blocks: 11 parity sign-off quality gate for clean test UX logs.

  **References**:
  - `pyronaut-test/build.gradle.kts` - target launch configuration for `pyronaut-test` JVM args.
  - `pyronaut-cli/build.gradle.kts` - existing warning-suppression precedent (`applicationDefaultJvmArgs`).
  - OpenJDK docs: restricted methods + `--enable-native-access` behavior.
  - OpenJDK launcher docs: `--sun-misc-unsafe-memory-access` behavior.

  **Acceptance Criteria**:
  - [x] Running `pyronaut-test` on `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app` no longer prints default restricted-native-access warning block.
  - [x] Running `pyronaut-test` on the same app no longer prints default terminally deprecated `sun.misc.Unsafe` warning block.
  - [x] If JUL warning suppression is added, it is scoped and test-covered (no masking of actual test failures). *(not required; no JUL suppression added)*
  - [x] Existing `pyronaut-test` behavior and exit-code semantics remain unchanged.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: pyronaut-test output is warning-clean by default
    Tool: Bash
    Preconditions: demo app processed and test-ready
    Steps:
      1. Run: pyronaut-test --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app > .sisyphus/evidence/task-18-test.out 2> .sisyphus/evidence/task-18-test.err
      2. Assert stderr does not contain "A restricted method in java.lang.System has been called"
      3. Assert stderr does not contain "A terminally deprecated method in sun.misc.Unsafe has been called"
      4. Assert command exit behavior still matches fixture expectation
    Expected Result: Default warning noise removed without breaking test execution semantics
    Evidence: .sisyphus/evidence/task-18-test.out, .sisyphus/evidence/task-18-test.err
  ```

- [x] 19. Improve `pyronaut-processor` progress visibility with source counts + completion messaging *(merged action item)*

  **What to do**:
  - Add explicit progress output for both processor passes (main and test/fused pass).
  - Print source counts for each pass (processable Python/Java totals) before compilation starts.
  - Add spinner-style progress in interactive mode and deterministic plain-text fallback for non-interactive mode.
  - Print clear completion messages per pass and final completion summary.

  **Must NOT do**:
  - Do not introduce per-file noisy logging in default mode.
  - Do not regress current concise failure diagnostics (`--verbose` hint and verbose stacktrace path).
  - Do not rely on human-only visual interpretation; messages must remain machine-capturable.

  **Dependencies**:
  - Depends on: 5 (processor CLI baseline), 13 (verbose diagnostics behavior), 15 (dual-pass processing).
  - Blocks: 11 parity sign-off UX criteria for processing observability.

  **References**:
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorMain.java` - dual-pass compile orchestration.
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/InstallProgressReporter.java` - spinner/progress mode pattern.

  **Acceptance Criteria**:
  - [x] `pyronaut-processor` prints "processing main sources" with concrete source count before main compile.
  - [x] `pyronaut-processor` prints "processing test sources" with concrete source count before test/fused compile.
  - [x] Interactive run shows spinner progress; non-interactive run shows deterministic plain-text progress.
  - [x] Completion messages appear for each pass and final overall completion.
  - [x] Existing processor tests updated/added for progress output behavior.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Processor emits count + completion for both passes
    Tool: Bash
    Preconditions: demo app with src and tests trees; install manifests generated
    Steps:
      1. Run: pyronaut-processor --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app > .sisyphus/evidence/task-19-processor.out 2> .sisyphus/evidence/task-19-processor.err
      2. Assert output includes main-pass source count message
      3. Assert output includes test-pass source count message
      4. Assert output includes processing completion summary
    Expected Result: Users can understand in-progress and completed processing work at a glance
    Evidence: .sisyphus/evidence/task-19-processor.out, .sisyphus/evidence/task-19-processor.err
  ```

- [x] 20. Add incremental source-change caching to `pyronaut-processor` main/test passes *(merged action item)*

  **What to do**:
  - Add per-pass cache keys so compiler invocation is skipped when inputs are unchanged:
    - main pass cache key for `src` + `src-java` inputs and relevant compile inputs.
    - test pass cache key for fused test inputs (`src/tests` + Java equivalents) and relevant test compile inputs.
  - Persist cache metadata under `__pyronaut__` in deterministic files.
  - Emit explicit cache-hit/cache-miss status in processor output.
  - Ensure cache invalidation includes meaningful non-source inputs (classpath manifests/options) so stale outputs are not reused.

  **Must NOT do**:
  - Do not skip compilation when any cache key input changed.
  - Do not delete valid outputs on cache-hit path.
  - Do not couple cache correctness to wall-clock timestamps only.

  **Dependencies**:
  - Depends on: 5 (processor CLI), 15 (fused test processing contract), 19 (progress/output contract).
  - Blocks: 11 parity sign-off for deterministic performance behavior.

  **References**:
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorMain.java` - compile skip points.
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/ResolutionCache.java` - hash-based cache key pattern.
  - `pyronaut-processor/src/test/java/io/micronaut/pyronaut/processor/PyronautProcessorMainTest.java` - command-flow regression tests.
  - `pyronaut-processor/src/test/java/io/micronaut/pyronaut/processor/PyronautProcessorCompilationTest.java` - compile behavior assertions.

  **Acceptance Criteria**:
  - [x] First processor run compiles required passes and writes processor cache metadata.
  - [x] Second unchanged run reports cache-hit and skips corresponding compiler invocation(s).
  - [x] Changing only `tests` inputs invalidates only test pass cache key.
  - [x] Changing `src` inputs invalidates main pass cache and (by fused-contract) test pass cache when applicable.
  - [x] Changing classpath/options inputs invalidates the affected pass cache key(s).
  - [x] Processor cache behavior is test-covered and deterministic.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Unchanged project skips recompilation via processor cache
    Tool: Bash
    Preconditions: install manifests generated; clean initial processor run completed
    Steps:
      1. Run: pyronaut-processor --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app > .sisyphus/evidence/task-20-run1.out 2> .sisyphus/evidence/task-20-run1.err
      2. Run same command again -> .sisyphus/evidence/task-20-run2.out/.err
      3. Assert second run reports cache-hit/skip for unchanged pass(es)
      4. Assert output directories remain valid
    Expected Result: Repeated invocation avoids unnecessary compilation while preserving correctness
    Evidence: .sisyphus/evidence/task-20-run1.out, .sisyphus/evidence/task-20-run2.out

  Scenario: Targeted source change invalidates only relevant cache
    Tool: Bash
    Preconditions: baseline processor cache metadata exists
    Steps:
      1. Modify one file under tests tree
      2. Run processor and assert test pass recompiles while main pass remains cache-hit (if unchanged)
      3. Modify one file under src tree
      4. Run processor and assert main pass recompiles and fused test pass invalidates per contract
    Expected Result: Cache invalidation is granular and contract-correct
    Evidence: .sisyphus/evidence/task-20-invalidation.out
  ```

- [ ] 21. Add global `--no-cache` orchestration and cache-bypass propagation for install/process *(merged action item)*

  **What to do**:
  - Add a global orchestrator option `--no-cache` to `pyronaut` command parsing.
  - Propagate cache bypass to update/install stage and process stage consistently:
    - install/update path: bypass install cache layer (`--refresh` behavior)
    - process path: bypass processor cache layer (skip cache reads/writes for that invocation)
  - Ensure direct orchestrator command usage (`pyronaut process --no-cache`, `pyronaut run --no-cache`, `pyronaut test --no-cache`) forwards expected flags deterministically.

  **Must NOT do**:
  - Do not silently ignore `--no-cache` for delegated subcommands.
  - Do not change default cached behavior when `--no-cache` is absent.
  - Do not broaden this task to unrelated cache eviction tooling.

  **Dependencies**:
  - Depends on: 3 (install cache behavior), 9 (orchestrator), 20 (processor cache behavior).
  - Blocks: 22, 23 parity expectations for deterministic preflight behavior.

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - orchestrator parsing/delegation and preflight flow.
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/PyronautInstallMain.java` - `--refresh` cache-bypass semantics.
  - `pyronaut-processor/src/main/java/io/micronaut/pyronaut/processor/PyronautProcessorMain.java` - processor cache path and options.
  - `pyronaut/src/test/python/orchestrator_test.py` - delegation/ordering assertions.

  **Acceptance Criteria**:
  - [x] `pyronaut run --no-cache --project-dir <app>` delegates install/update and process using cache-bypass behavior.
  - [x] `pyronaut test --no-cache --project-dir <app>` delegates install/update and process using cache-bypass behavior.
  - [x] `pyronaut process --no-cache --project-dir <app>` performs processing without cache-hit skips for that invocation.
  - [x] Existing default (cached) path is unchanged when `--no-cache` is not passed.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Orchestrator forwards --no-cache to preflight stages
    Tool: Bash
    Preconditions: PYRONAUT_TRACE_DELEGATION enabled; project fixture available
    Steps:
      1. Run: PYRONAUT_TRACE_DELEGATION=true pyronaut run --no-cache --project-dir <app> > .sisyphus/evidence/task-21-run.out 2> .sisyphus/evidence/task-21-run.err
      2. Assert traced install/update invocation includes cache-bypass flag
      3. Assert traced process invocation includes cache-bypass flag
      4. Assert final delegated run invocation still executes
    Expected Result: Global --no-cache is propagated deterministically to required stages
    Evidence: .sisyphus/evidence/task-21-run.out, .sisyphus/evidence/task-21-run.err
  ```

- [ ] 22. Harden orchestrator `run` coordination to deterministic `update -> process -> run` pipeline *(merged action item)*

  **What to do**:
  - Introduce explicit orchestrator preflight stage semantics for `run`:
    - stage 1: `update` (mapped to install behavior)
    - stage 2: `process`
    - stage 3: `run`
  - Ensure ordering is deterministic and observable in delegation traces.
  - Ensure stage failures short-circuit and return the failing stage exit code.

  **Must NOT do**:
  - Do not execute `run` if `update` or `process` fails.
  - Do not reorder stage sequence.
  - Do not introduce hidden fallback paths that bypass declared preflight.

  **Dependencies**:
  - Depends on: 9 (orchestrator baseline), 21 (`--no-cache` propagation contract).
  - Blocks: run-path parity sign-off in 11.

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - `run()` and `_delegate()` sequencing.
  - `pyronaut/src/test/python/orchestrator_test.py` - run preflight sequencing tests.
  - `pyronaut/src/test/python/e2e_flow_test.py` - end-to-end run flow behavior.

  **Acceptance Criteria**:
  - [x] `pyronaut run --project-dir <app>` delegates `update` then `process` then `pyronaut-run` in order.
  - [x] Failure in `update` or `process` aborts run pipeline and returns that failure code.
  - [x] Orchestrator tests assert deterministic stage order for run path.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: run command executes strict stage order
    Tool: Bash
    Preconditions: PYRONAUT_TRACE_DELEGATION enabled
    Steps:
      1. Run: PYRONAUT_TRACE_DELEGATION=true pyronaut run --project-dir <app> > .sisyphus/evidence/task-22-run.out 2> .sisyphus/evidence/task-22-run.err
      2. Assert stderr trace order: pyronaut-install -> pyronaut-processor -> pyronaut-run
      3. Assert command exits successfully on passing fixture
    Expected Result: Deterministic update->process->run choreography
    Evidence: .sisyphus/evidence/task-22-run.out, .sisyphus/evidence/task-22-run.err
  ```

- [ ] 23. Harden orchestrator `test` coordination to deterministic `update -> process -> test` pipeline *(merged action item)*

  **What to do**:
  - Introduce explicit orchestrator preflight stage semantics for `test`:
    - stage 1: `update` (mapped to install behavior)
    - stage 2: `process`
    - stage 3: `test`
  - Ensure `test` path delegates to `pyronaut-test` (not `pyronaut-run`) after preflight.
  - Ensure stage failures short-circuit and return the failing stage exit code.

  **Must NOT do**:
  - Do not run `pyronaut-run` in the `test` pipeline.
  - Do not execute `pyronaut-test` if `update` or `process` fails.
  - Do not alter existing test exit-code propagation semantics.

  **Dependencies**:
  - Depends on: 9 (orchestrator baseline), 17 (test classpath behavior), 21 (`--no-cache` propagation contract).
  - Blocks: test-path parity sign-off in 11.

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - `run()` branching for `test` command.
  - `pyronaut/src/test/python/orchestrator_test.py` - current `test` preflight behavior assertions.
  - `pyronaut-test/src/main/java/io/micronaut/pyronaut/test/PyronautTestMain.java` - delegated target behavior.

  **Acceptance Criteria**:
  - [x] `pyronaut test --project-dir <app>` delegates `update` then `process` then `pyronaut-test` in order.
  - [x] Failure in `update` or `process` aborts test pipeline and returns that failure code.
  - [x] Orchestrator tests assert deterministic stage order and correct final delegated executable for test path.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: test command executes strict stage order and target executable
    Tool: Bash
    Preconditions: PYRONAUT_TRACE_DELEGATION enabled
    Steps:
      1. Run: PYRONAUT_TRACE_DELEGATION=true pyronaut test --project-dir <app> > .sisyphus/evidence/task-23-test.out 2> .sisyphus/evidence/task-23-test.err
      2. Assert stderr trace order: pyronaut-install -> pyronaut-processor -> pyronaut-test
      3. Assert no delegation to pyronaut-run in trace
      4. Assert test summary reports expected pass/fail output
    Expected Result: Deterministic update->process->test choreography
    Evidence: .sisyphus/evidence/task-23-test.out, .sisyphus/evidence/task-23-test.err
  ```

- [ ] 24. Add `--debug-vm` support to `pyronaut-run` and `pyronaut-test` with JDWP flags *(merged action item)*

  **What to do**:
  - Add `--debug-vm` command option to both `pyronaut-run` and `pyronaut-test` command surfaces.
  - Ensure enabling `--debug-vm` activates JVM debug arguments:
    - `-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005`
  - Define deterministic behavior for port conflicts and suspended startup (clear diagnostics, no silent hang ambiguity).
  - Ensure orchestrator forwarding for `pyronaut run --debug-vm` and `pyronaut test --debug-vm` reaches delegated commands.

  **Must NOT do**:
  - Do not enable debug mode by default.
  - Do not suppress or alter regular failure/exit-code semantics when debug mode is disabled.
  - Do not broaden scope to remote debugging UX beyond requested fixed JDWP arguments.

  **Dependencies**:
  - Depends on: 7 (`pyronaut-run`), 8 (`pyronaut-test`), 9 (orchestrator forwarding).
  - Blocks: run/test developer-debug workflow parity sign-off.

  **References**:
  - `pyronaut-run/src/main/java/io/micronaut/pyronaut/run/PyronautRunMain.java` - run command options/launch behavior.
  - `pyronaut-test/src/main/java/io/micronaut/pyronaut/test/PyronautTestMain.java` - test command options/launch behavior.
  - `pyronaut-run/src/test/java/io/micronaut/pyronaut/run/PyronautRunMainTest.java` - run option behavior test baseline.
  - `pyronaut-test/src/test/java/io/micronaut/pyronaut/test/PyronautTestMainTest.java` - test option behavior test baseline.
  - `pyronaut/src/test/python/orchestrator_test.py` - orchestrator argument forwarding assertions.

  **Acceptance Criteria**:
  - [x] `pyronaut-run --debug-vm --project-dir <app>` starts with requested JDWP arguments and suspend behavior.
  - [x] `pyronaut-test --debug-vm --project-dir <app>` starts with requested JDWP arguments and suspend behavior.
  - [x] If port `5005` is unavailable, command fails with actionable port-conflict diagnostics.
  - [x] `pyronaut run --debug-vm` and `pyronaut test --debug-vm` forward debug mode to delegated commands.
  - [x] Existing non-debug behavior is unchanged when `--debug-vm` is absent.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Debug VM mode activates JDWP for run command
    Tool: Bash
    Preconditions: Fixture app available
    Steps:
      1. Run: pyronaut run --debug-vm --project-dir <app> > .sisyphus/evidence/task-24-run.out 2> .sisyphus/evidence/task-24-run.err
      2. Assert output indicates JVM waiting for debugger / JDWP listener startup on 5005
      3. Terminate process after assertion capture
    Expected Result: Debug VM mode applies requested JDWP arguments for run
    Evidence: .sisyphus/evidence/task-24-run.out, .sisyphus/evidence/task-24-run.err

  Scenario: Debug VM mode port conflict returns clear diagnostics
    Tool: Bash
    Preconditions: Port 5005 occupied by separate process
    Steps:
      1. Start a listener on port 5005
      2. Run: pyronaut test --debug-vm --project-dir <app> > .sisyphus/evidence/task-24-test.out 2> .sisyphus/evidence/task-24-test.err
      3. Assert non-zero exit code
      4. Assert stderr includes actionable message indicating debug port conflict
    Expected Result: Debug mode failure is explicit and diagnosable
    Evidence: .sisyphus/evidence/task-24-test.out, .sisyphus/evidence/task-24-test.err
  ```

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
| C7 | Task 13-17 | `feat(cli-v2): improve diagnostics, proxy support, and source-test classpath processing` |
| C8 | Task 18-20 | `feat(cli-v2): suppress test JVM noise and add processor progress/caching` |
| C9 | Task 21-24 | `feat(cli-v2): add no-cache orchestration, deterministic preflight, and debug-vm support` |

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
- [x] Process verbose diagnostics + PythonApplication failure investigation completed and documented
- [x] Dependency-tree unresolved failures include in-graph highlighted path diagnostics
- [x] `pyronaut-process` supports dual source+test processing with separate outputs and test classpath
- [x] `pyronaut-install` supports proxy configuration from `~/.m2/settings.xml` with `~/.pyronaut/settings.toml` fallback
- [x] `pyronaut-test` includes `__pyronaut__/test-classes` on execution classpath when present
- [x] `pyronaut-test` default runtime output suppresses restricted-native-access and Unsafe warning noise without masking failures
- [x] `pyronaut-processor` reports pass-level source counts + progress + completion in interactive/non-interactive modes
- [x] `pyronaut-processor` incremental caching skips unchanged compiles with deterministic invalidation for source/classpath/option changes
 - [x] `pyronaut --no-cache` propagates to update/install and process stages with deterministic cache-bypass behavior
 - [x] `pyronaut run` enforces deterministic `update -> process -> run` orchestration order
 - [x] `pyronaut test` enforces deterministic `update -> process -> test` orchestration order
 - [x] `pyronaut-run` and `pyronaut-test` support `--debug-vm` with requested JDWP arguments and clear port-conflict diagnostics
