# Enforce Demo-Style Micronaut Integration Test in Pyronaut E2E

## TL;DR

> **Quick Summary**: Update the `pyronaut` module E2E flow so its pytest phase executes a real Micronaut integration test patterned after `pyronaut-demo`, while preserving the existing real HTTP run-check.
>
> **Deliverables**:
> - E2E-generated pytest integration test content aligned to demo pattern
> - E2E assertions that prove integration test execution (not just collection)
> - Verified end-to-end run in `:micronaut-pyronaut:testPythonOrchestratorE2E`
>
> **Estimated Effort**: Short
> **Parallel Execution**: NO - sequential
> **Critical Path**: Task 1 → Task 2 → Task 3 → Task 4

---

## Context

### Original Request
Ensure the E2E test runs a Micronaut integration test similar to `pyronaut-demo/app/tests/test_micronaut_integration.py` to verify the flow.

### Interview Summary
**Key Discussions**:
- User requires demo-like Micronaut integration semantics in E2E pytest stage.
- User confirmed scope must keep both checks:
  - existing real HTTP verification in `run` stage
  - new demo-style Micronaut integration verification in `test` stage

**Research Findings**:
- `pyronaut-demo/app/tests/test_micronaut_integration.py` uses `micronaut_test_fixture`, `MicronautTest(...)`, and bean lookup (`"python.MyController"`).
- Current E2E (`pyronaut/src/test/python/e2e_flow_test.py`) writes only a simple marker-style pytest file via `_write_project_sources`.

### Metis Review
**Identified Gaps (addressed in this plan)**:
- Prevent false positives by asserting executed/passed integration test output, not only collection.
- Lock scope to E2E-generated test source + E2E assertions only.
- Preserve existing real HTTP run-check exactly.
- Add explicit failure diagnostics requirements for pytest/run stages.

---

## Work Objectives

### Core Objective
Make E2E prove real Micronaut integration behavior in pytest stage using the demo test pattern, without weakening current run-stage HTTP validation.

### Concrete Deliverables
- Updated generated test payload in `E2EFlowTest._write_project_sources(...)` to include demo-like Micronaut integration fixtures and assertions.
- Updated E2E assertion logic to verify integration pytest execution result (e.g., explicit passed output) and preserve HTTP endpoint assertion.
- Green E2E command in module verification.

### Definition of Done
- [ ] `./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E` exits 0 and logs show integration test executed/passed.
- [ ] Existing HTTP run-check still validates real endpoint response in E2E.

### Must Have
- Demo-style integration fixture usage in generated pytest test (`micronaut_test_fixture`, `MicronautTest`).
- Bean retrieval/assertion step equivalent to demo semantics.
- No stubs/mocks introduced.

### Must NOT Have (Guardrails)
- Do not remove or relax the real HTTP run-stage check.
- Do not broaden scope to unrelated CLI refactors or platform matrix work.
- Do not accept “collected only” as proof of integration test execution.

---

## Verification Strategy (MANDATORY)

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> All verification is agent-executable using commands/tools only.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: YES (Tests-after for this change)
- **Framework**: Python `unittest` orchestrating real pytest execution through `pyronaut-test`

### Agent-Executed QA Scenarios (MANDATORY — ALL tasks)

Scenario: E2E run-stage still serves HTTP response
  Tool: Bash (Gradle + Python subprocess)
  Preconditions: Java and python3 available; module buildable
  Steps:
    1. Run: `./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E`
    2. Capture test logs from task output
    3. Assert run-stage check validates HTTP response string used in E2E
  Expected Result: Run-stage HTTP assertion passes
  Failure Indicators: Connection refused, timeout, or assertion mismatch
  Evidence: Gradle task output log

Scenario: pytest stage executes integration fixture test (not collection-only)
  Tool: Bash (Gradle + pytest output inspection)
  Preconditions: E2E-generated test file includes demo-like integration test
  Steps:
    1. Run: `./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E`
    2. Inspect combined stdout/stderr for pytest execution summary
    3. Assert output contains explicit execution signal (e.g., `1 passed` or expected equivalent)
  Expected Result: Integration test actually runs and passes
  Failure Indicators: only collection text, zero tests executed, non-zero exit
  Evidence: Gradle task output with pytest summary lines

Scenario: Negative validation for integration fixture failure path
  Tool: Bash
  Preconditions: Temporary local mutation (executor-controlled) of generated assertion to impossible expected value
  Steps:
    1. Mutate expected integration assertion value in E2E writer
    2. Run: `./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E`
    3. Assert command exits non-zero and reports pytest assertion failure
  Expected Result: E2E fails loudly when integration assertion is wrong
  Failure Indicators: false pass despite broken assertion
  Evidence: Failure output excerpt

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately):
└── Task 1

Wave 2 (After Wave 1):
└── Task 2

Wave 3 (After Wave 2):
└── Task 3

Wave 4 (After Wave 3):
└── Task 4

Critical Path: 1 → 2 → 3 → 4
Parallel Speedup: none (sequential dependency chain)
```

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|----------------------|
| 1 | None | 2 | None |
| 2 | 1 | 3 | None |
| 3 | 2 | 4 | None |
| 4 | 3 | None | None |

### Agent Dispatch Summary

| Wave | Tasks | Recommended Agents |
|------|-------|--------------------|
| 1 | 1 | `task(category="quick", load_skills=["git-master"], run_in_background=false)` |
| 2 | 2 | `task(category="quick", load_skills=["git-master"], run_in_background=false)` |
| 3 | 3 | `task(category="quick", load_skills=["git-master"], run_in_background=false)` |
| 4 | 4 | `task(category="quick", load_skills=["git-master"], run_in_background=false)` |

---

## TODOs

- [ ] 1. Replace marker-style generated pytest with demo-style Micronaut integration test

  **What to do**:
  - Update generated test content in `pyronaut/src/test/python/e2e_flow_test.py` (`_write_project_sources`) so generated `tests/test_*.py` mirrors demo pattern:
    - import from `pyronaut.test`
    - define fixture using `micronaut_test_fixture(..., MicronautTest(...))`
    - retrieve `python.MyController`
    - assert non-null and deterministic behavior

  **Must NOT do**:
  - No stub server/test simulation
  - No removal of existing generated controller

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Single focused E2E test writer update
  - **Skills**: `git-master`
    - `git-master`: keep changes scoped and traceable
  - **Skills Evaluated but Omitted**:
    - `playwright`: no browser flow involved

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 2, 3, 4
  - **Blocked By**: None

  **References**:
  - `pyronaut-demo/app/tests/test_micronaut_integration.py` - canonical integration fixture pattern to mirror
  - `pyronaut/src/test/python/e2e_flow_test.py` - E2E source writer location (`_write_project_sources`)

  **Acceptance Criteria**:
  - [ ] Generated pytest test content contains `micronaut_test_fixture` and `MicronautTest(...)`
  - [ ] Generated test includes bean lookup equivalent to `python.MyController`
  - [ ] No marker-file-only assertion remains as sole test proof

  **Commit**: NO

- [ ] 2. Strengthen E2E pytest-stage assertions to prove execution/passing

  **What to do**:
  - Update E2E assertions in `test_orchestrated_install_process_run_test_flow` to validate integration test execution output (e.g., `1 passed` / explicit pass summary).
  - Ensure failure diagnostics include stdout/stderr when pytest stage fails.

  **Must NOT do**:
  - Do not reduce assertions to collection-only checks.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Assertion and failure-message hardening
  - **Skills**: `git-master`
    - `git-master`: small, auditable test assertion changes
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: not UI work

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 3, 4
  - **Blocked By**: 1

  **References**:
  - `pyronaut/src/test/python/e2e_flow_test.py` - current pytest-stage assertions
  - `pyronaut-demo/app/tests/test_micronaut_integration.py` - expected semantic behavior to validate

  **Acceptance Criteria**:
  - [ ] E2E checks for executed/passed integration test signal, not just collection count
  - [ ] On pytest failure, assertion includes command stderr/stdout context

  **Commit**: NO

- [ ] 3. Preserve and verify run-stage HTTP integration check unchanged in intent

  **What to do**:
  - Confirm existing `run` stage in E2E still starts real app and validates real HTTP payload.
  - Keep timeout/retry logic sufficient for CI stability.

  **Must NOT do**:
  - Do not remove run-stage validation because pytest integration exists.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Guardrail validation + minor tuning only if necessary
  - **Skills**: `git-master`
    - `git-master`: keep guardrail-focused adjustments minimal
  - **Skills Evaluated but Omitted**:
    - `playwright`: no browser requirement

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: 4
  - **Blocked By**: 2

  **References**:
  - `pyronaut/src/test/python/e2e_flow_test.py` lines around `run_process`, `_wait_for_http`, and expected body assertion

  **Acceptance Criteria**:
  - [ ] HTTP body assertion remains present and passing
  - [ ] E2E fails if HTTP endpoint is unreachable or payload mismatched

  **Commit**: NO

- [ ] 4. Execute module verification and record evidence

  **What to do**:
  - Run verification commands and ensure clean pass:
    - `./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E`
    - `./gradlew :micronaut-pyronaut:check -PpyronautE2E=true`

  **Must NOT do**:
  - Do not declare success without command evidence.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: verification-only execution
  - **Skills**: `git-master`
    - `git-master`: disciplined verification and status capture
  - **Skills Evaluated but Omitted**:
    - `artistry`: no unconventional strategy needed

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential
  - **Blocks**: None
  - **Blocked By**: 3

  **References**:
  - `pyronaut/build.gradle` task definitions for `testPythonOrchestratorE2E` and `check`

  **Acceptance Criteria**:
  - [ ] Both commands exit with code 0
  - [ ] Output confirms pytest integration test ran and passed
  - [ ] Output confirms run-stage HTTP validation passed

  **Commit**: YES
  - Message: `Strengthen E2E with demo-style Micronaut integration pytest validation`
  - Files: E2E python test and fixture-related files only
  - Pre-commit: both verification commands above

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 4 | Strengthen E2E with demo-style Micronaut integration pytest validation | `pyronaut/src/test/python/e2e_flow_test.py` (+ fixture file only if needed) | `:micronaut-pyronaut:testPythonOrchestratorE2E` + `:micronaut-pyronaut:check -PpyronautE2E=true` |

---

## Success Criteria

### Verification Commands
```bash
./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E
./gradlew :micronaut-pyronaut:check -PpyronautE2E=true
```

### Final Checklist
- [ ] All "Must Have" conditions implemented
- [ ] All guardrails respected
- [ ] E2E pytest stage executes real Micronaut integration-style test
- [ ] E2E run stage still validates real HTTP behavior
- [ ] Verification commands pass end-to-end
