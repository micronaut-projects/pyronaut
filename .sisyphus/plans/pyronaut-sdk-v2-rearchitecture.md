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
- Initial baseline had TUI rework out of scope (phase 2); superseded by follow-up Task 34 (`pyronaut --tui` Tamboui delegation port).
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
- No TUI framework migration away from Tamboui; keep TUI scope to delegation port + report analysis (no unrelated redesign).
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
> - `pyronaut-test` Gradle-like `--tests` filtering is tracked under new merged task 25.
> - `pyronaut-run` native reflection metadata + missing-main fallback hardening is tracked under new merged task 26.
> - Orchestrator auto-restart-on-change for `pyronaut run` is tracked under new merged task 27.
> - Test report outputs and `.pyronaut-last-nodeid.txt` relocation under `__pyronaut__` are tracked under new merged task 28.
> - Orchestrator GraalVM JDK auto-provisioning (SDKMAN-first, fallback download, JDK 25+) is tracked under new merged task 29.
> - New `pyronaut build` command + `[tool.pyronaut]` build-mode model support is tracked under new merged task 30.
> - `pyronaut-test` report UX modernization (console link minimization + rich HTML details + Micronaut branding) is tracked under new merged task 31.
> - `pyronaut run` restart-cycle optimization (parallel stop + process synchronization) is tracked under new merged task 32.
> - `pyronaut-test` official online Micronaut SVG logo provenance/reference update is tracked under new merged task 33.
> - Monolith Tamboui TUI port to v2 orchestrator via `pyronaut --tui` delegation and report-analysis workflow is tracked under new merged task 34.
> - Delegated v2 TUI live-reload/status parity + incremental per-test progress artifacts are tracked under new merged task 35.
> - `pyronaut build --native` GraalVM reachability metadata repository integration is tracked under new merged task 36.

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

- [x] 13. Add process verbose diagnostics and root-cause investigation workflow *(merged from process-diagnostics sub-plan)*

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

- [x] 21. Add global `--no-cache` orchestration and cache-bypass propagation for install/process *(merged action item)*

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

- [x] 22. Harden orchestrator `run` coordination to deterministic `update -> process -> run` pipeline *(merged action item)*

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

- [x] 23. Harden orchestrator `test` coordination to deterministic `update -> process -> test` pipeline *(merged action item)*

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

- [x] 24. Add `--debug-vm` support to `pyronaut-run` and `pyronaut-test` with JDWP flags *(merged action item)*

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

- [x] 25. Add Gradle-like `--tests` selection support to `pyronaut-test` and orchestrator forwarding *(merged action item)*

  **What to do**:
  - Add repeatable `--tests` option to `pyronaut-test` CLI and orchestrator forwarding path (`pyronaut test --tests ...`).
  - Define deterministic selector mapping (default for this plan):
    - each `--tests` value is OR-combined with other `--tests` values.
    - if selector contains `::` or ends with `.py`, treat as pytest nodeid/path selector and pass through.
    - otherwise treat as Gradle-like wildcard (`*`, `?`) against normalized pytest nodeids.
  - Wire selection down to pytest execution layer so only matching tests are executed.
  - Preserve current behavior when `--tests` is absent.

  **Must NOT do**:
  - Do not change default whole-suite behavior when no `--tests` option is provided.
  - Do not broaden scope to marker-expression DSL redesign beyond requested `--tests` support.
  - Do not silently ignore invalid/empty selectors; return deterministic diagnostics.

  **Dependencies**:
  - Depends on: 8 (test CLI baseline), 9 (orchestrator forwarding), 17 (test classpath parity).
  - Blocks: 11 parity sign-off for selective test execution UX.

  **References**:
  - `pyronaut-test/src/main/java/io/micronaut/pyronaut/test/PyronautTestMain.java` - new CLI option and discovery wiring.
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/PytestTestEngine.java` - engine selection/descriptor integration.
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/execution/PytestTestExecutor.java` - pytest invocation argument wiring.
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - orchestrator argument propagation.
  - Pytest usage docs (`-k`, nodeid selection): https://github.com/pytest-dev/pytest/blob/main/doc/en/how-to/usage.rst

  **Acceptance Criteria**:
  - [x] `pyronaut-test --tests tests/test_health.py::test_ping --project-dir <app>` executes only the targeted nodeid.
  - [x] `pyronaut-test --tests '*health*' --project-dir <app>` filters execution to wildcard-matching tests.
  - [x] Multiple `--tests` options are OR-combined deterministically.
  - [x] `pyronaut test --tests ...` forwards selectors to delegated `pyronaut-test` command unchanged.
  - [x] Invalid selector input produces clear deterministic error message while preserving existing exit semantics policy.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Nodeid selector executes exactly one targeted test
    Tool: Bash
    Preconditions: fixture project has at least 3 tests including tests/test_health.py::test_ping
    Steps:
      1. Run: pyronaut test --tests tests/test_health.py::test_ping --project-dir <app> > .sisyphus/evidence/task-25-nodeid.out 2> .sisyphus/evidence/task-25-nodeid.err
      2. Assert report output includes executed test id tests/test_health.py::test_ping
      3. Assert no unrelated test ids appear in output/report
    Expected Result: only selected nodeid executes
    Evidence: .sisyphus/evidence/task-25-nodeid.out, .sisyphus/evidence/task-25-nodeid.err

  Scenario: Wildcard selector excludes non-matching tests
    Tool: Bash
    Preconditions: fixture contains both health and non-health tests
    Steps:
      1. Run: pyronaut-test --tests '*health*' --project-dir <app> > .sisyphus/evidence/task-25-wildcard.out 2> .sisyphus/evidence/task-25-wildcard.err
      2. Assert execution report contains only wildcard-matching nodeids
      3. Assert command exit matches filtered suite result
    Expected Result: deterministic Gradle-like wildcard filtering works
    Evidence: .sisyphus/evidence/task-25-wildcard.out, .sisyphus/evidence/task-25-wildcard.err
  ```

- [x] 26. Harden `pyronaut-run` native/reflection behavior with explicit metadata and quiet missing-main fallback *(merged action item)*

  **What to do**:
  - Add run-module reachability metadata file for reflective Micronaut startup path used by `PyronautRunMain`.
  - Ensure missing default main class (`pyronaut_application.PyronautMain`) follows tolerant fallback path without noisy stacktrace spam in normal mode.
  - Keep actionable diagnostics when both default-main and fallback startup fail.
  - Add tests for fallback/noisy-log behavior and native metadata presence.

  **Must NOT do**:
  - Do not remove reflective fallback behavior.
  - Do not hide real runtime failures when fallback path also fails.
  - Do not broaden scope to unrelated run-command feature additions.

  **Dependencies**:
  - Depends on: 7 (`pyronaut-run` baseline), 24 (`--debug-vm` behavior baseline).
  - Blocks: 11 parity sign-off for run reliability and native readiness.

  **References**:
  - `pyronaut-run/src/main/java/io/micronaut/pyronaut/run/PyronautRunMain.java` - fallback and startup path.
  - `pyronaut-run/src/test/java/io/micronaut/pyronaut/run/PyronautRunMainTest.java` - run behavior tests.
  - `pyronaut-install/src/main/resources/META-INF/native-image/io.micronaut/micronaut-pyronaut-install/reachability-metadata.json` - metadata structure precedent.
  - GraalVM reachability metadata docs: https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/ReachabilityMetadata.md

  **Acceptance Criteria**:
  - [x] `pyronaut-run` module contains reachability metadata covering reflective Micronaut startup entry points.
  - [x] Missing `pyronaut_application.PyronautMain` uses fallback startup path without default stacktrace noise in non-verbose mode.
  - [x] If fallback startup also fails, command returns non-zero with concise root-cause diagnostics.
  - [x] Run tests validate fallback path and diagnostics behavior.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Missing default main class falls back cleanly
    Tool: Bash
    Preconditions: fixture without pyronaut_application.PyronautMain but with valid Micronaut bean bootstrap path
    Steps:
      1. Run: pyronaut-run --project-dir <fixture> > .sisyphus/evidence/task-26-fallback.out 2> .sisyphus/evidence/task-26-fallback.err
      2. Assert process starts successfully via fallback path
      3. Assert stderr does not contain full ClassNotFoundException stacktrace in default mode
    Expected Result: fallback startup works with low-noise diagnostics
    Evidence: .sisyphus/evidence/task-26-fallback.out, .sisyphus/evidence/task-26-fallback.err

  Scenario: Broken fallback still fails with actionable diagnostics
    Tool: Bash
    Preconditions: fixture missing both default main and required fallback classes
    Steps:
      1. Run: pyronaut-run --project-dir <broken-fixture> > .sisyphus/evidence/task-26-fail.out 2> .sisyphus/evidence/task-26-fail.err
      2. Assert non-zero exit code
      3. Assert stderr contains concise cause chain and recovery hint
    Expected Result: true failures remain visible and diagnosable
    Evidence: .sisyphus/evidence/task-26-fail.out, .sisyphus/evidence/task-26-fail.err
  ```

- [x] 27. Add orchestrator-managed auto-restart for `pyronaut run` on `src`/`tests`/`config` changes *(merged action item)*

  **What to do**:
  - Add file-watch loop for orchestrated `pyronaut run` that monitors `src`, `tests`, and `config`.
  - On qualifying change, re-run deterministic pipeline `update -> process -> run` and replace active run process.
  - Add debounce and ignore rules to prevent restart loops from generated artifacts.
  - Add integration/e2e tests proving restart triggers and ignore behavior.

  **Must NOT do**:
  - Do not restart on writes under generated/output/cache paths (`__pyronaut__`, `.pytest_cache`, build outputs).
  - Do not bypass declared `update -> process -> run` order during restart cycle.
  - Do not leave orphan run processes after restart.

  **Dependencies**:
  - Depends on: 22 (deterministic run preflight), 21 (`--no-cache` propagation contract), 24 (debug-vm delegation behavior).
  - Blocks: 11 parity sign-off for iterative dev-loop UX.

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - run orchestration and subprocess lifecycle.
  - `pyronaut/src/test/python/orchestrator_test.py` - deterministic stage-order tests and delegation tracing.
  - `pyronaut/src/test/python/e2e_flow_test.py` - process lifecycle test patterns.
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/PyronautFileWatcher.java` - watch-root and restart-loop precedent.

  **Acceptance Criteria**:
  - [x] `pyronaut run --project-dir <app>` automatically restarts on source changes under `src`, `tests`, or `config`.
  - [x] Restart path always re-runs `update -> process -> run` in order.
  - [x] Writes under ignored output/cache directories do not trigger restart.
  - [x] Restart logic terminates prior run process before starting replacement.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Source change triggers full restart pipeline
    Tool: Bash
    Preconditions: PYRONAUT_TRACE_DELEGATION=true; app fixture starts successfully
    Steps:
      1. Start: pyronaut run --project-dir <app> > .sisyphus/evidence/task-27-run.out 2> .sisyphus/evidence/task-27-run.err
      2. Modify file: <app>/src/... (touch or small content change)
      3. Assert trace shows new cycle of pyronaut-install -> pyronaut-processor -> pyronaut-run
      4. Assert only one active run process remains after restart
    Expected Result: orchestrator restarts app deterministically after source change
    Evidence: .sisyphus/evidence/task-27-run.out, .sisyphus/evidence/task-27-run.err

  Scenario: Generated output write does not trigger restart
    Tool: Bash
    Preconditions: run watcher active
    Steps:
      1. Modify file under <app>/__pyronaut__/reports/tests
      2. Wait debounce interval
      3. Assert no additional restart cycle appears in delegation trace
    Expected Result: ignore rules prevent restart loops
    Evidence: .sisyphus/evidence/task-27-ignore.out
  ```

- [x] 28. Standardize test reporting outputs under `__pyronaut__/reports/tests` and relocate `.pyronaut-last-nodeid.txt` *(merged action item)*

  **What to do**:
  - Ensure `pyronaut-test` emits JUnit XML and HTML reports under `__pyronaut__/reports/tests` with deterministic filenames.
  - Move `.pyronaut-last-nodeid.txt` from project root to `__pyronaut__/reports/tests/.pyronaut-last-nodeid.txt`.
  - Ensure report directories are created automatically and are stable for CI artifact collection.
  - Keep report generation behavior deterministic for both full suite and filtered runs.

  **Must NOT do**:
  - Do not keep writing nodeid file to project root.
  - Do not require manual post-processing to find report outputs.
  - Do not break existing failure propagation semantics in test execution.

  **Dependencies**:
  - Depends on: 8 (`pyronaut-test` baseline), 25 (`--tests` filtering), 27 (run watcher ignore path needs report location awareness).
  - Blocks: 11 parity sign-off for CI-reporting determinism.

  **References**:
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/execution/JUnitPytestTestListener.java` - current `.pyronaut-last-nodeid.txt` write location.
  - `pyronaut-test/src/main/java/io/micronaut/pyronaut/test/PyronautTestMain.java` - test command/report listener wiring.
  - `pyronaut-pytest/src/main/resources/META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/pytest_listener.py` - pytest event/report bridge.
  - Pytest JUnit report docs: https://github.com/pytest-dev/pytest/blob/main/doc/en/how-to/output.rst
  - pytest-html docs: https://github.com/pytest-dev/pytest-html/blob/master/docs/user_guide.rst

  **Acceptance Criteria**:
  - [x] Running `pyronaut test --project-dir <app>` writes JUnit XML to `<app>/__pyronaut__/reports/tests/junit.xml`.
  - [x] Running same command writes HTML report to `<app>/__pyronaut__/reports/tests/index.html`.
  - [x] Last-nodeid file is written to `<app>/__pyronaut__/reports/tests/.pyronaut-last-nodeid.txt`.
  - [x] No `.pyronaut-last-nodeid.txt` is created at project root.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Test run emits XML and HTML reports in deterministic directory
    Tool: Bash
    Preconditions: fixture app with passing tests
    Steps:
      1. Run: pyronaut test --project-dir <app> > .sisyphus/evidence/task-28-test.out 2> .sisyphus/evidence/task-28-test.err
      2. Assert file exists: <app>/__pyronaut__/reports/tests/junit.xml
      3. Assert file exists: <app>/__pyronaut__/reports/tests/index.html
      4. Assert junit.xml contains executed testcase entries
    Expected Result: report artifacts are generated in canonical directory
    Evidence: .sisyphus/evidence/task-28-test.out, .sisyphus/evidence/task-28-test.err

  Scenario: Last nodeid file moved out of project root
    Tool: Bash
    Preconditions: fixture includes at least one failing test
    Steps:
      1. Run failing test invocation via pyronaut test
      2. Assert file exists: <app>/__pyronaut__/reports/tests/.pyronaut-last-nodeid.txt
      3. Assert file does not exist: <app>/.pyronaut-last-nodeid.txt
    Expected Result: nodeid tracking is stored under __pyronaut__/reports/tests only
    Evidence: .sisyphus/evidence/task-28-nodeid-location.txt
  ```

- [x] 29. Add GraalVM JDK auto-provisioning in orchestrator (`~/.pyronaut/jdks`, SDKMAN-first, fallback download, min JDK 25) *(merged action item)*

  **What to do**:
  - Add orchestrator JDK provisioning service for GraalVM-compatible JDKs under `~/.pyronaut/jdks`.
  - Provisioning order (default for this plan):
    1. use existing compatible JDK if already configured/present,
    2. attempt SDKMAN-managed install when SDKMAN is available,
    3. fallback to official script-friendly archive download URLs.
  - Enforce minimum JDK major version 25 for provisioned GraalVM runtime.
  - Keep installs idempotent and reuse already-provisioned versions.

  **Must NOT do**:
  - Do not re-download/reinstall same compatible JDK on every command run.
  - Do not override explicit user Java settings when already compatible.
  - Do not broaden phase-1 support beyond macOS/Linux platform matrix.

  **Dependencies**:
  - Depends on: 9 (orchestrator baseline), 10 (wheel packaging path for bundled behavior), 26 (run native-readiness assumptions), 30 (build native mode).
  - Blocks: native build/run developer workflow parity in 11.

  **References**:
  - GraalVM setup automation action: https://github.com/graalvm/setup-graalvm/blob/main/README.md
  - Oracle GraalVM support matrix (JDK 25 baseline): https://github.com/oracle/graal/blob/master/docs/oracle-graalvm/support.md
  - SDKMAN install/init docs: https://github.com/sdkman/sdkman-cli/blob/master/README.md and https://github.com/sdkman/sdkman-cli/blob/master/src/main/bash/sdkman-init.sh
  - GraalVM CE release artifacts (script-friendly URLs): https://github.com/graalvm/graalvm-ce-builds/releases
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - orchestrator command bootstrap and environment wiring.

  **Acceptance Criteria**:
  - [x] Commands requiring GraalVM detect compatible preinstalled JDK and skip provisioning.
  - [x] When no compatible JDK exists, orchestrator provisions JDK 25+ under `~/.pyronaut/jdks`.
  - [x] SDKMAN path is attempted first when available; fallback download path works when SDKMAN unavailable.
  - [x] Second identical run is idempotent (no re-download) and reuses installed JDK.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Provisioning installs GraalVM JDK into ~/.pyronaut/jdks
    Tool: Bash
    Preconditions: No compatible GraalVM JDK configured; network-enabled fixture
    Steps:
      1. Run command requiring GraalVM (e.g., pyronaut build --native --project-dir <app>)
      2. Assert directory created under ~/.pyronaut/jdks/<resolved-version>
      3. Assert selected JAVA_HOME points to provisioned location for delegated native step
    Expected Result: compatible JDK auto-provisioned and used
    Evidence: .sisyphus/evidence/task-29-provisioning.out

  Scenario: SDKMAN unavailable triggers fallback download flow
    Tool: Bash
    Preconditions: SDKMAN not present in environment; no compatible cached JDK
    Steps:
      1. Run same native-required command
      2. Assert logs indicate SDKMAN path skipped/unavailable
      3. Assert fallback archive download path completes and install is usable
    Expected Result: orchestrator provisions via fallback without manual intervention
    Evidence: .sisyphus/evidence/task-29-fallback.out
  ```

- [x] 30. Add new `pyronaut build` command with `[tool.pyronaut]` build-mode model (JVM wheel default, `--native` native flow) *(merged action item)*

  **What to do**:
  - Add orchestrator command surface `pyronaut build`.
  - Extend config model (`[tool.pyronaut]`) with build-mode settings used by `pyronaut build`.
  - Define deterministic build modes (default for this plan):
    - default JVM mode builds project wheel artifact,
    - `--native` mode builds native-image artifact using provisioned GraalVM JDK path.
  - Ensure command composes with existing install/process/build pipeline and artifact output conventions.

  **Must NOT do**:
  - Do not implement a second dependency resolver path; reuse existing install/config infrastructure.
  - Do not make `--native` default mode.
  - Do not introduce unrelated packaging system redesign.

  **Dependencies**:
  - Depends on: 2 (config model), 9 (orchestrator), 10 (wheel packaging), 29 (GraalVM JDK provisioning).
  - Blocks: 11 parity sign-off for packaging/build workflow completeness.

  **References**:
  - `pyronaut-config-model/src/main/java/io/micronaut/pyronaut/config/model/PyprojectModel.java` - typed model extension point.
  - `pyronaut-config-model/src/main/java/io/micronaut/pyronaut/config/model/PyprojectModelReader.java` - parser extension point.
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - new orchestrator subcommand implementation.
  - `pyronaut/build.gradle` - existing wheel build workflow baseline.
  - GraalVM native-image docs: https://github.com/oracle/graal/blob/master/docs/reference-manual/native-image/README.md

  **Acceptance Criteria**:
  - [x] `pyronaut build --project-dir <app>` (without `--native`) produces wheel artifact under canonical dist output.
  - [x] `pyronaut build --native --project-dir <app>` produces native artifact under deterministic build output path.
  - [x] `[tool.pyronaut]` build configuration is parsed by config model and respected by build command defaults.
  - [x] Build command tests cover mode selection, config fallback, and failure diagnostics.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Default build mode produces wheel artifact
    Tool: Bash
    Preconditions: fixture app with valid pyproject.toml build metadata
    Steps:
      1. Run: pyronaut build --project-dir <app> > .sisyphus/evidence/task-30-jvm.out 2> .sisyphus/evidence/task-30-jvm.err
      2. Assert dist directory contains wheel artifact (*.whl)
      3. Optionally install wheel in fresh venv and assert import/entrypoint smoke
    Expected Result: default build path yields JVM wheel artifact
    Evidence: .sisyphus/evidence/task-30-jvm.out, .sisyphus/evidence/task-30-jvm.err

  Scenario: Native build mode uses provisioned GraalVM and emits native artifact
    Tool: Bash
    Preconditions: fixture supports native build; provisioning path available
    Steps:
      1. Run: pyronaut build --native --project-dir <app> > .sisyphus/evidence/task-30-native.out 2> .sisyphus/evidence/task-30-native.err
      2. Assert logs show GraalVM JDK selection/provisioning source
      3. Assert native artifact exists at documented output location
    Expected Result: native build mode is deterministic and fully automated
    Evidence: .sisyphus/evidence/task-30-native.out, .sisyphus/evidence/task-30-native.err
  ```

- [ ] 31. Modernize `pyronaut-test` report UX output and HTML report presentation *(new requirement)*

  **What to do**:
  - Update `pyronaut-test` console report messaging to print only:
    - reports directory path,
    - HTML report path.
  - Keep JUnit XML and last-nodeid artifacts generated, but remove direct console link lines for those artifact paths.
  - Enhance HTML report so each test is expandable/collapsible and includes per-test detail payloads:
    - assertion failure details,
    - captured stdout/stderr (system output) when present.
  - Modernize report styling with a popular CDN CSS library (default decision: Bootstrap 5 CDN).
  - Keep fallback decision documented: if Bootstrap CDN is blocked by policy, use UIkit 3 CDN accordion pattern with equivalent expand/collapse behavior.
  - Include Micronaut logo in the generated HTML report header.
  - Ensure all dynamic test data is HTML-escaped and report remains readable when CDN resources are unavailable.

  **Must NOT do**:
  - Do not change canonical report artifact paths or filenames under `__pyronaut__/reports/tests`.
  - Do not remove JUnit XML or last-nodeid artifact generation.
  - Do not introduce extra report formats/pages, client-side bundling pipelines, or search/sort/filter UI scope.
  - Do not require network connectivity for baseline report readability.

  **Dependencies**:
  - Depends on: 8 (`pyronaut-test` baseline), 28 (report artifact location contracts), 30 (current orchestrator/report UX baseline).
  - Blocks: final reporting UX sign-off and parity quality gate in 11.

  **References**:
  - `pyronaut-test/src/main/java/io/micronaut/pyronaut/test/PyronautTestMain.java` - `publishReportLocations(...)` console report output contract.
  - `pyronaut-test/src/test/java/io/micronaut/pyronaut/test/PyronautTestMainTest.java` - report output/legacy compatibility tests.
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/execution/JUnitPytestTestListener.java` - HTML report generation pipeline.
  - `pyronaut-pytest/src/main/resources/META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/pytest_listener.py` - captured stdout/stderr/failure forwarding.
  - Task 33 reference sources - official online Micronaut SVG provenance/fallback policy for logo rendering.
  - Bootstrap docs/CDN: https://getbootstrap.com/docs/5.3/getting-started/introduction/
  - UIkit CDN fallback reference: https://github.com/uikit/uikit#readme

  **Acceptance Criteria**:
  - [ ] Running `pyronaut test --project-dir <app>` prints report links for only reports directory + HTML report path.
  - [ ] Same command output does not print direct JUnit XML or last-nodeid report lines.
  - [ ] Generated HTML report includes Micronaut logo and Bootstrap-based styling loaded from CDN.
  - [ ] Each test entry supports expand/collapse details showing failure message and captured stdout/stderr when available.
  - [ ] HTML report escapes dynamic test content safely and remains structurally readable when CDN CSS is unavailable.
  - [ ] Existing canonical report artifacts (`junit.xml`, `index.html`, `.pyronaut-last-nodeid.txt`) remain generated under `__pyronaut__/reports/tests`.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Console output links only reports directory and HTML report
    Tool: Bash
    Preconditions: fixture app with passing tests
    Steps:
      1. Run: pyronaut test --project-dir <app> > .sisyphus/evidence/task-31-console.out 2> .sisyphus/evidence/task-31-console.err
      2. Assert stdout contains reports directory line
      3. Assert stdout contains HTML report line
      4. Assert stdout does NOT contain `JUnit XML report:` or `Last nodeid report:`
    Expected Result: concise console linking behavior with deterministic report discovery
    Evidence: .sisyphus/evidence/task-31-console.out, .sisyphus/evidence/task-31-console.err

  Scenario: HTML report exposes expandable per-test diagnostics
    Tool: Bash
    Preconditions: fixture with at least one failing test and one test emitting stdout/stderr
    Steps:
      1. Run: pyronaut test --project-dir <app> > .sisyphus/evidence/task-31-html.out 2> .sisyphus/evidence/task-31-html.err
      2. Assert file exists: <app>/__pyronaut__/reports/tests/index.html
      3. Assert HTML contains Bootstrap CDN reference and Micronaut logo markup
      4. Assert HTML contains expandable section structure per test and includes failure/output payload text
      5. Assert special characters in payload are escaped (e.g., `<` rendered as `&lt;`)
    Expected Result: rich per-test report diagnostics are visible without breaking HTML safety
    Evidence: .sisyphus/evidence/task-31-html.out, .sisyphus/evidence/task-31-html.err

  Scenario: Canonical artifacts remain stable while UX output changes
    Tool: Bash
    Preconditions: fixture app with test execution completed
    Steps:
      1. Run: pyronaut test --project-dir <app>
      2. Assert files exist under canonical path:
         - <app>/__pyronaut__/reports/tests/junit.xml
         - <app>/__pyronaut__/reports/tests/index.html
         - <app>/__pyronaut__/reports/tests/.pyronaut-last-nodeid.txt
      3. Assert no artifact path contract changes from Task 28
    Expected Result: report UX improvements do not regress artifact contracts
    Evidence: .sisyphus/evidence/task-31-artifacts.txt
  ```

- [x] 32. Optimize `pyronaut run` edit/reload cycle by parallelizing stop + process during restart *(new requirement)*

  **What to do**:
  - Update orchestrator restart behavior in `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` so watcher-triggered restart executes:
    1. delegated process refresh (`pyronaut-processor`) and
    2. managed server stop (`_stop_managed_process`)
    in parallel.
  - Restart `pyronaut-run` only after **both** operations complete successfully.
  - Keep deterministic restart transaction semantics:
    - if stop fails, restart is aborted and command exits non-zero,
    - if process fails, restart is aborted and command exits non-zero,
    - no stale process from prior generation is allowed to terminate the newly started process.
  - Preserve existing watcher debounce + ignored directory behavior and existing preflight guardrails.

  **Must NOT do**:
  - Do not bypass orchestrator preflight/install policy for initial startup.
  - Do not introduce restart races where old stop operations can kill a newly spawned run process.
  - Do not trigger restart from writes inside ignored output/cache directories.

  **Dependencies**:
  - Depends on: 22 (deterministic preflight ordering), 27 (auto-restart baseline), 31 (report UX task remains independent).
  - Blocks: end-to-end reload latency/behavior sign-off in 11.

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - `_run_with_auto_restart(...)`, `_stop_managed_process(...)`.
  - `pyronaut/src/test/python/orchestrator_test.py` - restart and stop fallback test baselines.

  **Acceptance Criteria**:
  - [ ] On source change, orchestrator launches delegated process refresh in parallel with stop, then restarts run only after both complete.
  - [ ] If stop phase fails, restart is aborted and deterministic non-zero failure is returned.
  - [ ] If process phase fails, restart is aborted and deterministic non-zero failure is returned.
  - [ ] Existing debounce and ignored-directory restart guardrails remain intact.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Restart overlaps stop and process phases
    Tool: Bash
    Preconditions: orchestrator test harness with deterministic fake process + timing hooks
    Steps:
      1. Run orchestrator tests covering restart ordering/timestamps
      2. Assert event order shows stop and process overlap before restart
      3. Assert restart launch occurs only after both phases report success
    Expected Result: parallel stop+process behavior reduces restart critical path without race regressions
    Evidence: .sisyphus/evidence/task-32-restart-parallel.txt

  Scenario: Stop/process failure aborts restart deterministically
    Tool: Bash
    Preconditions: tests injecting stop failure and process failure conditions
    Steps:
      1. Run failure-path orchestrator tests
      2. Assert non-zero exit and explicit stderr diagnostics for each failing phase
      3. Assert no second run process is started when either phase fails
    Expected Result: failure handling is deterministic and safe
    Evidence: .sisyphus/evidence/task-32-restart-failures.txt
  ```

- [x] 33. Replace report logo placeholder with official online Micronaut SVG reference + provenance guardrails *(new requirement)*

  **What to do**:
  - Update report HTML generation to reference a real official Micronaut SVG online source:
    - primary: `https://micronaut.io/wp-content/uploads/2020/11/MIcronautLogo_Horizontal.svg`
    - fallback: sanctioned brand-kit download link from official logos page.
  - Keep logo rendering deterministic in generated HTML while preserving report readability when external logo loading fails.
  - Document source authority/provenance in code comments/tests and plan references.

  **Must NOT do**:
  - Do not use unofficial third-party logo assets.
  - Do not remove report readability when logo fetch fails.
  - Do not regress existing HTML report structure/sections used by downstream tooling.

  **Dependencies**:
  - Depends on: 31 (report UX modernization baseline).
  - Blocks: report branding sign-off and TUI report-analysis coupling in 34.

  **References**:
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/execution/JUnitPytestTestListener.java` - logo/report HTML generation path.
  - Official Micronaut logos policy/source: https://micronaut.io/brand-guidelines/micronaut-logos/
  - Primary online SVG: https://micronaut.io/wp-content/uploads/2020/11/MIcronautLogo_Horizontal.svg

  **Acceptance Criteria**:
  - [ ] Generated report HTML references the official online Micronaut SVG URL in logo markup.
  - [ ] Fallback behavior/source is present and test-covered for failed primary logo load.
  - [ ] Existing HTML report diagnostics sections and canonical report artifact behavior remain unchanged.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: HTML report contains official Micronaut SVG source reference
    Tool: Bash
    Preconditions: report generation tests and fixture run
    Steps:
      1. Execute pyronaut-pytest report generation tests
      2. Assert generated HTML contains primary official SVG URL
      3. Assert fallback marker/source is present in markup
    Expected Result: report branding uses official Micronaut source with deterministic fallback
    Evidence: .sisyphus/evidence/task-33-logo-reference.txt

  Scenario: Logo load failure does not break report readability
    Tool: Bash
    Preconditions: fixture HTML validation with simulated unavailable logo URL
    Steps:
      1. Run test validating fallback/no-logo failure path
      2. Assert report body, test cards, and diagnostics sections remain renderable
    Expected Result: branding failure does not break report usability
    Evidence: .sisyphus/evidence/task-33-logo-fallback.txt
  ```

- [x] 34. Port monolith Tamboui TUI to v2 and activate with `pyronaut --tui` delegation model *(new requirement)*

  **What to do**:
  - Introduce v2 TUI activation path via orchestrator global flag: `pyronaut --tui` (default mode run; test mode toggle supported).
  - Extract TUI runtime into dedicated module/project `pyronaut-tui` so legacy `pyronaut-cli` remains unchanged during v2 migration.
  - Keep TUI implementation on **Tamboui** (no framework migration), reusing/porting applicable `pyronaut-cli` view/controller patterns.
  - Replace embedded monolith runtime/compiler coupling with explicit delegation to independent v2 commands (`install`, `process`, `run`, `test`).
  - Add report-analysis workflow in TUI test mode that consumes canonical `pyronaut-test` artifacts (`junit.xml`, `index.html`, `.pyronaut-last-nodeid.txt`) from `__pyronaut__/reports/tests`.
  - Add non-interactive/agent-executable TUI smoke verification path so CI/agents can validate launch + delegation + report parsing without manual terminal interaction.

  **Must NOT do**:
  - Do not embed monolith `PyronautCliCompiler`/application manager runtime into v2 TUI flow.
  - Do not migrate away from Tamboui.
  - Do not redesign report schema beyond parsing existing canonical outputs.
  - Do not bypass orchestrator lifecycle guardrails while in TUI mode.

  **Dependencies**:
  - Depends on: 9 (orchestrator baseline), 27 (restart baseline), 31/33 (report structure + branding source), existing protocol/delegation contracts.
  - Blocks: final v2 parity sign-off for interactive developer workflow.

  **References**:
  - Legacy Tamboui TUI: `pyronaut-cli/src/main/java/io/micronaut/python/cli/ui/PyronautTui.java`, `ui/view/RootView.java`, `ui/view/TestTreeView.java`, `ui/UiController.java`, `commands/BaseSourceCommand.java`.
  - Extracted v2 TUI module: `pyronaut-tui/src/main/java/io/micronaut/pyronaut/tui/`.
  - Legacy test event flow: `pyronaut-cli/src/main/java/io/micronaut/python/cli/TestApplicationManager.java`, `ui/TuiEventSink.java`, `protocol/ProtocolConstants.java`.
  - Tamboui docs (must retain framework): https://tamboui.dev/docs/main/index.html
  - v2 orchestrator entrypoint: `pyronaut/src/main/python/pyronaut_cli_v2/cli.py`

  **Acceptance Criteria**:
  - [ ] `pyronaut --tui` launches Tamboui-based v2 TUI and enters delegated run workflow.
  - [ ] TUI run/test actions delegate to independent v2 CLIs (no embedded monolith compiler/runtime).
  - [ ] TUI test workflow parses canonical report artifacts and renders deterministic test summary/status details.
  - [ ] Non-interactive TUI smoke path is available and CI/agent executable.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: v2 TUI launch/delegation smoke in non-interactive mode
    Tool: Bash
    Preconditions: TUI smoke mode enabled for CI/agents
    Steps:
      1. Run: pyronaut --tui --project-dir <app> --smoke
      2. Assert process exits 0 and logs indicate Tamboui TUI initialized
      3. Assert delegation trace includes v2 executable invocations (install/process/run or test path)
    Expected Result: TUI launches and delegates through independent CLI commands deterministically
    Evidence: .sisyphus/evidence/task-34-tui-smoke.txt

  Scenario: TUI test mode analyzes pyronaut-test reports
    Tool: Bash
    Preconditions: canonical report artifacts exist under <app>/__pyronaut__/reports/tests
    Steps:
      1. Run delegated test flow to generate reports
      2. Run TUI in test-analysis smoke mode
      3. Assert parsed summary includes pass/fail counts and failing test identifiers from reports
      4. Assert missing/corrupt report fixture path emits deterministic warning without crash
    Expected Result: TUI test pane/report analysis is driven by pyronaut-test report outputs
    Evidence: .sisyphus/evidence/task-34-tui-report-analysis.txt
  ```

- [ ] 35. Harden delegated v2 TUI live-reload/status behavior and add incremental per-test progress artifacts *(new requirement)*

  **What to do**:
  - Ensure `pyronaut --tui` run mode visibly reflects file-change detection, processing, and restart lifecycle transitions.
  - Ensure delegated run mode responds to `src`/`config` edits by re-running `pyronaut-processor` and restarting safely.
  - Ensure delegated test mode responds to `tests` (and relevant `src`/`config`) edits by re-running `pyronaut-processor` and re-running tests automatically.
  - Add an incremental per-test artifact stream in `pyronaut-pytest` for in-progress updates while tests are running.
  - Keep final canonical artifacts (`junit.xml`, `index.html`, `.pyronaut-last-nodeid.txt`) intact for final summary/traceability.
  - Use generation-scoped restart supervision so stale stop events cannot terminate newly started processes.

  **Default design decisions (locked for this task)**:
  - Incremental progress artifact format: append-only NDJSON, line-buffered, flush-per-event.
  - Artifact path: `<project>/__pyronaut__/reports/tests/events.ndjson`.
  - Minimum event fields:
    - `runId` (string)
    - `eventType` (`session_started` | `test_started` | `test_output` | `test_finished` | `session_finished`)
    - `testId` (nullable string)
    - `status` (nullable enum)
    - `timestamp` (ISO-8601 string)
    - `payload` (object for output/failure/metadata)
  - TUI consumes `events.ndjson` incrementally for live state, then reconciles final status from canonical reports.

  **Current observed gap baseline**:
  - `pyronaut-tui` delegated command currently performs one-shot run/test flows and does not sustain delegated watcher-triggered loop behavior across `src/tests/config` edits.
  - TUI status signaling for change/process/restart is incomplete during delegated execution.
  - Test progress is effectively end-of-run because no persisted incremental event stream exists today.

  **Must NOT do**:
  - Do not migrate away from Tamboui.
  - Do not change canonical report filenames/locations defined in Task 28.
  - Do not remove or weaken final JUnit/HTML report generation.
  - Do not introduce restart races or orphan processes under rapid file changes.
  - Do not re-couple delegated TUI flow to monolith-only in-process assumptions.

  **Dependencies**:
  - Depends on: 27 (watch/restart baseline), 28 (report artifact contract), 32 (restart transaction hardening), 34 (delegated TUI baseline).
  - Blocks: final interactive v2 parity sign-off in 11.

  **References**:
  - `pyronaut-tui/src/main/java/io/micronaut/pyronaut/tui/commands/PyronautDelegatingTuiCommand.java` - delegated run/test wiring.
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - orchestrator lifecycle/delegation behavior.
  - `pyronaut/src/main/python/pyronaut_cli_v2/tui/app.py` - TUI integration and workflow state.
  - `pyronaut/src/main/python/pyronaut_cli_v2/tui/reports.py` - final report parsing behavior.
  - `pyronaut-pytest/src/main/resources/META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/pytest_listener.py` - pytest hook timings.
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/execution/JUnitPytestTestListener.java` - report/event listener extension point.
  - `pyronaut-pytest/src/main/java/io/micronaut/test/pytest/listener/PytestTestListener.java` - listener callback contract.
  - pytest-reportlog incremental stream pattern: https://github.com/pytest-dev/pytest-reportlog
  - JUnit Open Test Reporting incremental event pattern: https://github.com/junit-team/junit5/tree/main/junit-platform-reporting

  **Acceptance Criteria**:
  - [ ] In `pyronaut --tui` run mode, edits under `src` or `config` visibly transition TUI through change-detected → processing → restarting → running.
  - [ ] Run-mode edit handling re-runs delegated `pyronaut-processor` and restarts app safely (no stale-process termination race).
  - [ ] In `pyronaut --tui` test mode, edits under `tests` (and relevant `src`/`config`) re-run delegated `pyronaut-processor` and `pyronaut-test` automatically.
  - [ ] `pyronaut-pytest` writes `__pyronaut__/reports/tests/events.ndjson` incrementally while tests execute.
  - [ ] TUI test mode consumes incremental events for live per-test progress before final JUnit parse completes.
  - [ ] Final summary output remains deterministic and consistent with canonical final artifacts.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Delegated TUI run mode surfaces change + safe restart lifecycle
    Tool: Bash
    Preconditions: TUI smoke/trace mode enabled; app runs successfully
    Steps:
      1. Run: PYRONAUT_TRACE_DELEGATION=true pyronaut --tui --project-dir <app> --smoke-run > .sisyphus/evidence/task-35-run.out 2> .sisyphus/evidence/task-35-run.err
      2. Modify a file under <app>/src
      3. Assert state trace includes change-detected/processing/restart markers
      4. Assert delegation trace includes `pyronaut-processor` then `pyronaut-run`
      5. Assert single active run process remains after restart
    Expected Result: visible, deterministic restart lifecycle on source edits
    Evidence: .sisyphus/evidence/task-35-run.out, .sisyphus/evidence/task-35-run.err

  Scenario: Delegated TUI test mode reruns process+tests on test edits
    Tool: Bash
    Preconditions: TUI test smoke mode enabled
    Steps:
      1. Run: PYRONAUT_TRACE_DELEGATION=true pyronaut --tui --project-dir <app> --smoke-test > .sisyphus/evidence/task-35-test.out 2> .sisyphus/evidence/task-35-test.err
      2. Modify a file under <app>/tests
      3. Assert delegation trace includes `pyronaut-processor` and `pyronaut-test` rerun sequence
      4. Assert TUI trace indicates rerun in progress and completion
    Expected Result: test edits trigger deterministic process+test rerun with visible status
    Evidence: .sisyphus/evidence/task-35-test.out, .sisyphus/evidence/task-35-test.err

  Scenario: Incremental per-test event stream is available before session end
    Tool: Bash
    Preconditions: fixture with multiple tests (pass/fail/stdout)
    Steps:
      1. Run: pyronaut test --project-dir <app> with controlled harness
      2. While run is active, read `<app>/__pyronaut__/reports/tests/events.ndjson` and verify lines append over time
      3. Assert `test_started` and `test_finished` events appear for executed tests
      4. Assert final `junit.xml` and `index.html` still exist at completion
    Expected Result: incremental progress is persisted during execution without regressing final reports
    Evidence: .sisyphus/evidence/task-35-events-stream.txt
  ```

- [ ] 36. Integrate GraalVM reachability metadata repository into `pyronaut build --native` via Python+Java hybrid delegation *(new requirement)*

  **What to do**:
  - Keep `pyronaut build` command ownership in Python orchestrator (`pyronaut/src/main/python/pyronaut_cli_v2/cli.py`) for mode selection and preflight behavior.
  - Replace Python direct `native-image` invocation for native mode with delegation to a new Java executable (default contract name in this plan: `pyronaut-native-build`).
  - Implement the Java native delegate to:
    - read project inputs (`--project-dir`, `--main-class`, `--output`),
    - resolve/apply GraalVM reachability metadata repository entries for runtime dependencies,
    - execute native-image with metadata configuration included,
    - return deterministic exit codes/messages aligned with existing orchestrator taxonomy.
  - Extend config modeling to include native metadata controls under `[tool.pyronaut.build]` with defaults that preserve current UX:
    - `mode` (existing)
    - `metadata.enabled` (default `true`)
    - `metadata.version` (optional pinned repository version)
    - `metadata.repositoryUrl` (optional override)
    - `metadata.excludedModules` (optional list)
  - Ensure wheel/bundled executable packaging includes the new delegate binary/script and resolver wiring.

  **Default design decisions (locked for this task)**:
  - Architecture: **Option C hybrid** (Python orchestrator + Java native delegate); full build-command migration to Java is out of scope for this task.
  - Native metadata behavior default: enabled and repository-backed.
  - On metadata repository unavailability with empty local cache: fail fast with deterministic precondition-style diagnostic (no silent best-effort continue).
  - On metadata repository unavailability with valid cached metadata: continue using cache and emit deterministic warning.
  - JVM wheel branch of `pyronaut build` remains unchanged.

  **Current observed gap baseline**:
  - Native build path currently shells `native-image` directly in Python with classpath + main class only and has no metadata repository integration.
  - Real-project native build failure reproduced at:
    - `/Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app`
  - `pyronaut-config-model` currently exposes only `tool.pyronaut.build.mode`; no metadata controls exist.

  **Must NOT do**:
  - Do not migrate all `pyronaut build` logic to Java in this task.
  - Do not change JVM wheel-mode behavior/output conventions.
  - Do not introduce a second independent dependency resolver path disconnected from existing install manifests.
  - Do not silently ignore metadata resolution failures when no cache is available.
  - Do not break existing orchestrator exit-code semantics.

  **Dependencies**:
  - Depends on: 2 (config model), 9 (orchestrator command framework), 10 (wheel packaging), 29 (GraalVM provisioning), 30 (build command baseline).
  - Blocks: native build parity quality gate in 11.

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` - current `_run_build` implementation and native branch behavior.
  - `pyronaut/src/test/python/orchestrator_test.py` - build-mode/native invocation tests.
  - `pyronaut-config-model/src/main/java/io/micronaut/pyronaut/config/model/PyprojectModel.java` - `[tool.pyronaut.build]` typed model.
  - `pyronaut-config-model/src/main/java/io/micronaut/pyronaut/config/model/PyprojectModelReader.java` - parser/validation extension points.
  - `pyronaut-install/build.gradle.kts`, `pyronaut-processor/build.gradle.kts` - in-repo Graal native plugin patterns.
  - Native build tools metadata docs:
    - https://github.com/graalvm/native-build-tools/blob/master/docs/src/docs/asciidoc/gradle-plugin.adoc
    - https://github.com/graalvm/native-build-tools/blob/master/docs/src/docs/asciidoc/maven-plugin.adoc
  - Reachability metadata repository:
    - https://github.com/oracle/graalvm-reachability-metadata
    - https://github.com/graalvm/native-build-tools/tree/master/common/graalvm-reachability-metadata

  **Acceptance Criteria**:
  - [ ] `pyronaut build --native --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app` succeeds and produces a native binary in deterministic output location.
  - [ ] Python build native path no longer directly executes `native-image`; it delegates to Java native-build executable.
  - [ ] Native delegate resolves/applies repository metadata for runtime dependencies and emits deterministic evidence (resolved metadata source/path/count).
  - [ ] Config model parses new metadata settings under `[tool.pyronaut.build]` with deterministic defaults and validation.
  - [ ] Metadata unavailable + no cache yields deterministic non-zero exit with actionable error.
  - [ ] Metadata unavailable + cache present uses cache with deterministic warning and successful build where otherwise valid.
  - [ ] Wheel packaging and command resolution include the native delegate executable without regressing existing install/process/run/test/build command discovery.

  **Agent-Executed QA Scenarios**:
  ```
  Scenario: Demo app native build succeeds with repository metadata enabled
    Tool: Bash
    Preconditions: GraalVM available/provisionable; demo app install/process prerequisites resolvable
    Steps:
      1. Run: pyronaut build --native --project-dir /Users/graemerocher/dev/micronaut/demos/pyronaut-demo-ref/app > .sisyphus/evidence/task-36-native-success.out 2> .sisyphus/evidence/task-36-native-success.err
      2. Assert native artifact exists under <app>/__pyronaut__/native/application (or final documented output path)
      3. Assert logs include metadata repository resolution evidence (version/url/cache source and applied module count)
      4. Assert command exits 0
    Expected Result: repository-backed metadata path enables successful native build for target project
    Evidence: .sisyphus/evidence/task-36-native-success.out, .sisyphus/evidence/task-36-native-success.err

  Scenario: Native build delegates to Java executable instead of direct Python native-image call
    Tool: Bash
    Preconditions: Delegation trace enabled
    Steps:
      1. Run: PYRONAUT_TRACE_DELEGATION=true pyronaut build --native --project-dir <app> > .sisyphus/evidence/task-36-delegation.out 2> .sisyphus/evidence/task-36-delegation.err
      2. Assert trace contains delegated Java native-build executable invocation
      3. Assert trace does not show Python constructing direct terminal `native-image -cp ...` command as execution path
    Expected Result: native build responsibility is encapsulated in Java delegate
    Evidence: .sisyphus/evidence/task-36-delegation.out, .sisyphus/evidence/task-36-delegation.err

  Scenario: Metadata repository unavailable with no cache fails deterministically
    Tool: Bash
    Preconditions: Force metadata URL/network failure and clear local metadata cache fixture
    Steps:
      1. Run native build command against failure fixture
      2. Assert non-zero exit code maps to deterministic precondition/config category
      3. Assert stderr includes explicit metadata repository failure cause and recovery hint
    Expected Result: no silent partial success when metadata cannot be resolved and cache is absent
    Evidence: .sisyphus/evidence/task-36-metadata-unavailable-fail.txt

  Scenario: Metadata repository unavailable with valid cache reuses cache
    Tool: Bash
    Preconditions: Populate metadata cache once; then simulate repository outage
    Steps:
      1. Run native build command with repository unavailable
      2. Assert build succeeds when other inputs are valid
      3. Assert logs include deterministic cache-reuse warning/source marker
    Expected Result: cached metadata enables deterministic offline-compatible behavior
    Evidence: .sisyphus/evidence/task-36-metadata-cache-reuse.txt
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
| C10 | Task 25-36 | `feat(cli-v2): add test filtering/reporting UX, restart optimization, TUI delegation hardening, and native metadata-aware build flow` |

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
- [x] `pyronaut-test` supports Gradle-like `--tests` filtering with deterministic selector forwarding
- [x] `pyronaut-run` includes explicit native-image reflection metadata and low-noise missing-main fallback behavior
- [x] `pyronaut run` auto-restarts on `src/tests/config` changes without restart loops from generated outputs
- [x] Test reports (`junit.xml`, `index.html`) and `.pyronaut-last-nodeid.txt` are written under `__pyronaut__/reports/tests`
- [x] Orchestrator auto-provisions compatible GraalVM JDK (SDKMAN-first, fallback download) under `~/.pyronaut/jdks` with JDK 25+ minimum
- [x] `pyronaut build` exists and supports JVM wheel default mode plus `--native` mode via `[tool.pyronaut]` config
- [ ] `pyronaut build --native` resolves/applies GraalVM reachability metadata repository entries (with deterministic cache/offline behavior) for real-project native builds
- [ ] `pyronaut-test` console output links only reports directory + HTML report, and HTML report includes expandable per-test diagnostics with Bootstrap CDN styling + Micronaut logo
- [ ] `pyronaut run` restart cycle overlaps delegated processing with server stop and restarts only after both complete deterministically
- [ ] `pyronaut-test` report HTML references official online Micronaut SVG source (with documented fallback/provenance)
- [ ] `pyronaut --tui` launches Tamboui-based v2 delegated TUI and analyzes `pyronaut-test` report artifacts without embedded monolith runtime/compiler coupling
- [ ] `pyronaut --tui` run/test loops surface change/restart progress and consume incremental per-test events from `__pyronaut__/reports/tests/events.ndjson`
