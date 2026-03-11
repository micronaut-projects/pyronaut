# Pyronaut Install Usability Improvements (Wheel + Local Dev)

## TL;DR

> **Quick Summary**: Improve `pyronaut install` usability by removing noisy delegated path output, fixing SLF4J provider warnings, adding TTY-aware progress UI, introducing `--dependencies` tree visualization with red-highlighted errors, and expanding failure-path tests.
>
> **Deliverables**:
> - Cleaner default CLI output (no delegated executable path line by default)
> - Single-provider SLF4J runtime behavior for `pyronaut-install`
> - Interactive progress UX with non-interactive fallback
> - `pyronaut install --dependencies` tree output with scope filters and error highlighting
> - Comprehensive unit/integration/E2E tests for failure and invalid config scenarios
>
> **Estimated Effort**: Large
> **Parallel Execution**: YES - 4 waves
> **Critical Path**: 1 → 2 → 4 → 6 → 8

---

## Context

### Original Request
Plan fixes for `pyronaut-install` usability issues reported in wheel-installed local usage:
1. unnecessary delegated executable path line printed
2. SLF4J provider warning noise
3. no dependency download visibility (spinner/progress)
4. no dependency graph mode like `mvn dependency:tree` (`--dependencies`, red errors)
5. add comprehensive failure/invalid-definition tests

### Interview & Research Summary
**Codebase findings**:
- Delegated path echo originates in `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` (`_delegate` currently prints `shlex.join(command_line)` to stderr).
- `pyronaut-install` currently resolves dependencies via `MavenClasspathResolver` and prints only exceptions from `PyronautInstallMain`.
- Resolver repository alias mapping (`mavenCentral`, `mavenLocal`, others as `repo-N`) is in `MavenClasspathResolver.toRepositories`.
- Existing install tests cover: happy path, cache reuse, unresolved dependency exit code, managed BOM versionless resolution, native smoke.

**External findings**:
- SLF4J warning is expected when no provider exists; add exactly one provider for app packaging (prefer lightweight default).
- Picocli supports ANSI auto/on/off patterns suitable for red-highlighted errors with non-TTY fallbacks.
- Maven tree UX patterns (scope filtering, structured output) are a good model for initial `--dependencies` mode.

### Metis Review (Integrated)
- Lock output contracts (stdout/stderr behavior, deterministic non-TTY output).
- Make new UX additive and backward-safe; avoid full Maven parity in v1.
- Ensure wheel-installed runtime validation for SLF4J provider behavior.
- Include explicit TTY/non-TTY acceptance criteria and richer failure matrices.

---

## Work Objectives

### Core Objective
Deliver a predictable, script-safe, and user-friendly `pyronaut install` experience for wheel users and local developers while preserving current resolution semantics.

### Concrete Deliverables
- Output behavior update in delegator (`pyronaut_cli_v2`) for path-line suppression by default.
- Install command UX extension in `pyronaut-install`:
  - progress model and renderer (TTY-aware)
  - dependency tree mode (`--dependencies`)
  - improved diagnostics with clear repo/scope context
- Logging provider strategy to eliminate SLF4J startup warning noise.
- Expanded test suite across failure, config validation, and UX contracts.

### Definition of Done
- [x] `pyronaut install` no longer prints delegated executable full path in default mode.
- [x] No `No SLF4J providers were found` warning during wheel-installed install path.
- [x] `pyronaut install --dependencies` emits dependency tree output and marks resolution errors clearly.
- [x] Progress output behaves correctly in TTY and degrades deterministically in non-TTY.
- [x] Comprehensive tests pass, including invalid TOML/dependency definitions and resolution failures.

### Must Have
- Additive flag model (new behavior controllable, low regression risk).
- Clear error attribution (scope + dependency + repository context).
- Red error highlighting only where terminal supports ANSI; plain fallback elsewhere.

### Defaults Locked for v1
- `--dependencies` defaults to `runtime` scope; `--scope` expands to others including `all`.
- Progress defaults to `auto` (interactive only on TTY); non-TTY defaults to plain deterministic lines.
- Tree mode is read-only by default (diagnostics view), avoiding install side effects.
- SLF4J provider default target for install runtime is lightweight single-provider strategy (favor simple provider unless test evidence requires otherwise).

### Must NOT Have (Guardrails)
- Do not rewrite dependency resolution algorithm beyond data extraction for tree display.
- Do not introduce multiple SLF4J providers.
- Do not require human/manual verification in acceptance criteria.
- Do not attempt full Maven dependency-tree feature parity in initial iteration.

---

## Verification Strategy (MANDATORY)

> **UNIVERSAL RULE: ZERO HUMAN INTERVENTION**
>
> All verification below is executable by agent commands and assertions only.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: YES (tests-after)
- **Framework**: JUnit 5 + existing Python unittest E2E harness + Gradle task verification

### Agent-Executed QA Scenarios (MANDATORY)

Scenario: Delegated path line removed from default UX
  Tool: Bash
  Preconditions: Wheel installed in venv; sample project with valid `pyproject.toml`
  Steps:
    1. Run: `pyronaut install --project-dir <app> 2>&1 | tee /tmp/install.out`
    2. Assert output does **not** contain delegated executable full path pattern (`.../tools/pyronaut-install/bin/pyronaut-install --project-dir`)
  Expected Result: No full-path echo line appears in default mode
  Failure Indicators: Any matching line in output
  Evidence: `/tmp/install.out`

Scenario: SLF4J warning noise eliminated
  Tool: Bash
  Preconditions: Same as above
  Steps:
    1. Run: `pyronaut install --project-dir <app> 2>&1 | tee /tmp/install-slf4j.out`
    2. Assert output contains none of:
       - `No SLF4J providers were found`
       - `Defaulting to no-operation`
       - `multiple SLF4J providers`
  Expected Result: No SLF4J provider warning lines
  Failure Indicators: Any warning match
  Evidence: `/tmp/install-slf4j.out`

Scenario: Dependency tree command output + error highlighting fallback
  Tool: Bash
  Preconditions: app with one intentionally broken dependency
  Steps:
    1. Run: `pyronaut install --dependencies --project-dir <app> > /tmp/tree.out 2>/tmp/tree.err`
    2. Assert `tree.out` includes dependency tree header and nodes
    3. Assert failures are clearly marked (`ERROR` marker); ANSI red only when enabled/TTY
  Expected Result: Tree present, errors visually distinguishable and script-safe
  Failure Indicators: Missing tree output or unmarked failure nodes
  Evidence: `/tmp/tree.out`, `/tmp/tree.err`

Scenario: Invalid TOML/dependency definitions fail clearly
  Tool: Bash
  Preconditions: Fixture with malformed TOML and another with invalid dependency coordinate
  Steps:
    1. Run install on malformed TOML fixture; assert non-zero exit and parse error prefix
    2. Run install on invalid coordinate fixture; assert non-zero exit and coordinate validation message
  Expected Result: Clear, deterministic failure messages by category
  Failure Indicators: Generic opaque error output or wrong exit code mapping
  Evidence: Captured command outputs per fixture

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Foundation):
├── Task 1: Output contract and flag design
└── Task 2: Delegator path-line suppression/gating

Wave 2 (Runtime UX + Logging):
├── Task 3: SLF4J provider packaging strategy
└── Task 4: Progress UI abstraction + TTY fallback

Wave 3 (Core Feature):
└── Task 5: --dependencies tree output + scope/error formatting

Wave 4 (Quality + Rollout):
├── Task 6: Install test matrix expansion
├── Task 7: Wheel-installed integration/E2E scenarios
└── Task 8: Documentation + migration notes

Critical Path: 1 → 3 → 4 → 5 → 6 → 7
Parallel Speedup: ~30% vs strict sequential
```

### Dependency Matrix

| Task | Depends On | Blocks | Can Parallelize With |
|------|------------|--------|----------------------|
| 1 | None | 3,4,5,6 | 2 |
| 2 | 1 | 7,8 | 3,4 |
| 3 | 1 | 7 | 4 |
| 4 | 1 | 5,6,7 | 3 |
| 5 | 1,4 | 6,7,8 | None |
| 6 | 4,5 | 7 | None |
| 7 | 2,3,5,6 | 8 | None |
| 8 | 7 | None | None |

### Agent Dispatch Summary

| Wave | Tasks | Recommended Agents |
|------|-------|--------------------|
| 1 | 1,2 | quick + git-master |
| 2 | 3,4 | unspecified-high + git-master |
| 3 | 5 | unspecified-high + git-master |
| 4 | 6,7,8 | quick/unspecified-high + git-master |

---

## TODOs

- [x] 1. Lock CLI output contract and option model

  **What to do**:
  - Define stdout/stderr contract for install/progress/tree/errors.
  - Define option semantics:
    - `--dependencies`
    - `--scope` behavior in tree mode
    - progress controls (`auto/on/off` or equivalent)
    - color controls (`auto/always/never` or equivalent)
  - Define compatibility behavior for existing scripts.

  **Must NOT do**:
  - Do not change resolver semantics in this task.

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: design contract and flag semantics from existing code paths
  - **Skills**: `git-master`
    - `git-master`: keep interface changes minimal and traceable
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: terminal CLI only

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Task 2)
  - **Blocks**: 3,4,5,6
  - **Blocked By**: None

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` (`_delegate`, `_run_subprocess`)
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/PyronautInstallMain.java` (existing options)
  - Maven tree UX pattern reference from external research (TreeMojo model)

  **Acceptance Criteria**:
  - [x] Option contract documented in code-level behavior tests
  - [x] stdout/stderr placement is explicit and test-covered

- [x] 2. Remove default delegated executable path echo (with optional debug gate)

  **What to do**:
  - Update delegator output behavior so default install output is concise.
  - Keep optional debug/verbose path-line output for troubleshooting.

  **Must NOT do**:
  - Do not remove useful error propagation from delegated command execution.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `playwright`: no browser activity

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: 7,8
  - **Blocked By**: 1

  **References**:
  - `pyronaut/src/main/python/pyronaut_cli_v2/cli.py` line with `print(shlex.join(command_line), file=sys.stderr)`
  - `pyronaut/src/test/python/orchestrator_test.py` for existing delegator behavior expectations

  **Acceptance Criteria**:
  - [x] Default command output omits full executable path line
  - [x] Debug mode still provides delegated command visibility

- [x] 3. Add SLF4J provider strategy for pyronaut-install runtime

  **What to do**:
  - Add exactly one provider for install runtime packaging (minimal default preferred).
  - Ensure provider strategy is deterministic in wheel-installed execution.
  - Verify no multiple-provider collisions.

  **Must NOT do**:
  - Do not introduce heavy logging framework unless justified by requirements.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `artistry`: conventional dependency packaging task

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: 7
  - **Blocked By**: 1

  **References**:
  - `pyronaut-install/build.gradle.kts` dependency block
  - SLF4J guidance: providers and no-provider warning behavior
  - `pyronaut-logging` / `pyronaut-logback` modules for in-repo logging dependency patterns

  **Acceptance Criteria**:
  - [x] No SLF4J no-provider warning in install path
  - [x] No multiple-provider warning introduced

- [x] 4. Implement install progress rendering with TTY-aware fallback

  **What to do**:
  - Add progress event/reporting API around resolver execution.
  - Add interactive spinner/progress renderer for TTY.
  - Add deterministic plain text mode for non-TTY/CI.

  **Must NOT do**:
  - Do not emit raw cursor control sequences in non-interactive mode.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: this is terminal UX but no web frontend

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2
  - **Blocks**: 5,6,7
  - **Blocked By**: 1

  **References**:
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/MavenClasspathResolver.java`
  - `pyronaut-cli/src/main/java/io/micronaut/python/cli/ui/ConsoleProgressDisplay.java`
  - picocli ANSI behavior references from research

  **Acceptance Criteria**:
  - [x] TTY mode shows spinner/progress updates
  - [x] non-TTY mode remains plain/deterministic without ANSI cursor noise

- [x] 5. Add `pyronaut install --dependencies` tree mode with red-highlighted resolution errors

  **What to do**:
  - Introduce `--dependencies` mode.
  - Render dependency tree for selected scope (default runtime, scope override).
  - Highlight resolution failures in red where ANSI is enabled; provide plain `ERROR` markers otherwise.

  **Must NOT do**:
  - Do not implement full Maven parity (keep v1 focused).

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `ultrabrain`: not required for first-iteration tree rendering

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 3
  - **Blocks**: 6,7,8
  - **Blocked By**: 1,4

  **References**:
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/PyronautInstallMain.java`
  - `pyronaut-install/src/main/java/io/micronaut/pyronaut/install/MavenClasspathResolver.java`
  - Maven tree UX model (TreeMojo), Picocli ANSI output patterns

  **Acceptance Criteria**:
  - [x] `--dependencies` prints tree output for runtime by default
  - [x] scope override works and is validated
  - [x] resolution errors visually distinguished (ANSI or plain fallback)

- [x] 6. Expand install tests for invalid definitions and failure matrix

  **What to do**:
  - Add tests for malformed TOML, invalid dependency coordinate types/values, repo failure attribution, offline/refresh behavior.
  - Add tests for new output contracts and option behaviors.

  **Must NOT do**:
  - Do not rely on live external repositories in deterministic tests.

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `artistry`: no unconventional method needed

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4
  - **Blocks**: 7
  - **Blocked By**: 4,5

  **References**:
  - `pyronaut-install/src/test/java/io/micronaut/pyronaut/install/PyronautInstallMainTest.java`
  - `pyronaut-config-model/src/main/java/io/micronaut/pyronaut/config/model/PyprojectModelReader.java`

  **Acceptance Criteria**:
  - [x] New negative-path tests cover invalid TOML and invalid coordinates
  - [x] Tests assert clear error messaging and exit code mapping

- [x] 7. Add wheel-installed integration/E2E checks for install usability

  **What to do**:
  - Add/extend E2E scenarios validating wheel-installed command behavior for:
    - no path-noise line
    - no SLF4J warning
    - progress fallback behavior
    - `--dependencies` output and error markers

  **Must NOT do**:
  - Do not require human terminal observation.

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `playwright`: terminal + subprocess focused

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4
  - **Blocks**: 8
  - **Blocked By**: 2,3,5,6

  **References**:
  - `pyronaut/src/test/python/e2e_flow_test.py`
  - `pyronaut/README.md` wheel installation instructions

  **Acceptance Criteria**:
  - [x] Automated E2E scenario validates wheel install UX contracts
  - [x] Failures include useful captured stdout/stderr diagnostics

- [x] 8. Update docs/help for new install UX and dependency tree mode

  **What to do**:
  - Update relevant module README/help text for new flags and behavior.
  - Document non-TTY behavior and examples for `--dependencies` and progress controls.

  **Must NOT do**:
  - Do not leave behavior undocumented when flags are added.

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: `git-master`
  - **Skills Evaluated but Omitted**:
    - `frontend-ui-ux`: docs only

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4 final
  - **Blocks**: None
  - **Blocked By**: 7

  **References**:
  - `pyronaut/README.md`
  - `pyronaut-install` command options/help text in `PyronautInstallMain`

  **Acceptance Criteria**:
  - [x] README/help includes new command examples and behavior notes
  - [x] docs match tested behavior exactly

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 2 | Improve delegator install output defaults | `pyronaut/src/main/python/pyronaut_cli_v2/*` | pyronaut orchestrator tests |
| 3-5 | Enhance pyronaut-install logging, progress, and dependency tree UX | `pyronaut-install/src/main/java/*`, build file | install module tests + focused integration |
| 6-7 | Add comprehensive install failure and wheel E2E coverage | install tests + pyronaut E2E python tests | module check with E2E |
| 8 | Document install UX and dependency graph usage | README/help docs | docs lint/review |

---

## Success Criteria

### Verification Commands
```bash
./gradlew :micronaut-pyronaut-install:test
./gradlew :micronaut-pyronaut:testPythonOrchestrator
PYRONAUT_E2E=true ./gradlew :micronaut-pyronaut:testPythonOrchestratorE2E
PYRONAUT_E2E=true ./gradlew :micronaut-pyronaut:check
```

### Final Checklist
- [x] Delegator no longer prints unnecessary path line by default
- [x] SLF4J warning eliminated with exactly one provider strategy
- [x] Install progress UX works in TTY and degrades safely in non-TTY
- [x] `--dependencies` provides tree output with clear error highlighting/fallback
- [x] Comprehensive failure/invalid-definition tests are present and passing
- [x] Wheel-installed path covered by automated verification
