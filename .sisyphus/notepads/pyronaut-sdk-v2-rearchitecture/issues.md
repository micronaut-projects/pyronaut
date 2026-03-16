## Notepad: pyronaut-sdk-v2-rearchitecture - issues

_(records from this session appear here)_

- 2026-03-13: No issues observed while wiring `--tests` selectors across CLI, engine, and orchestrator; filtering relies on normalized nodeids.
- 2026-03-13: Python LSP diagnostics are still unavailable in this environment because `basedpyright-langserver` is not installed; verification relied on targeted test execution.

- 2026-03-13: Python LSP (`basedpyright`) is not installed in this environment, so `lsp_diagnostics` cannot be run for `.py` files here. Java diagnostics are clean and the Gradle verification tasks pass.
- 2026-03-13: Native build entrypoint suitability remains a design risk: `pyronaut_application.PyronautMain` is currently used as baseline default, but a dedicated non-reflective native entrypoint variant may be needed if generated-main startup proves unreliable in real projects.
- 2026-03-13: Python LSP (`basedpyright`) remains unavailable in this environment, so Python static diagnostics are still validated via test execution and behavior-driven repro checks rather than language-server analysis.
- 2026-03-13: `./gradlew :micronaut-pyronaut:testPythonOrchestrator` and targeted `python -m pytest ... orchestrator_test.py` runs keep timing out (>360s) because the orchestrator run command is long-lived and never exits once the watched process remains up; needs investigation or tighter test harness timeouts.
- 2026-03-13: Logo fallback is client-side (`onerror`) and not directly unit-testable in pure string assertions; tests assert presence of the primary URL + fallback marker, while browser-based verification can cover runtime fallback rendering if needed.
- 2026-03-13: Full interactive Tamboui parity is not implemented in v2 yet; current Python-side `--tui` path is smoke/non-interactive focused and explicitly reports interactive runtime unavailable, so Task 34 remains open.

- 2026-03-13: `--tui` implementation currently relies on a smoke/non-interactive runner because Tamboui is a Java library; full interactive parity will require either a Java entrypoint or a dedicated bridge layer.
- 2026-03-13: `lsp_diagnostics` (basedpyright) flags existing typing issues (constant redefinition, implicit relative import) in `cli.py`; these should be cleaned up to satisfy the “clean diagnostics” verification requirement for touched Python files.
- 2026-03-13: `:micronaut-pyronaut:testPythonOrchestrator` can appear to hang in this environment because the task runs `unittest discover` and includes long-lived `run` behavior; using direct `python3 -m unittest pyronaut/src/test/python/orchestrator_test.py` provides fast feedback.
- 2026-03-13: Resolved Task 34 parity gap by adding a Tamboui-backed delegated command (`pyronaut delegating-tui`) and wiring Python `--tui` interactive mode to launch bundled `pyronaut-tui`; previous “interactive unavailable” issue is superseded.
- 2026-03-13: `:micronaut-pyronaut-cli:test` currently fails in pre-existing `PyronautInstallCommandTest` assertions unrelated to TUI changes; TUI verification uses `:micronaut-pyronaut-cli:compileJava` plus orchestrator/python test coverage.
- 2026-03-13: Direct dependency from `pyronaut-tui` to `pyronaut-cli` requires Gradle libs repository for transitive `gradle-tooling-api`; module-local repository settings were added to keep extraction isolated without changing global dependency resolution.
- 2026-03-16: Root cause for “`pyronaut --tui` exits immediately” was incomplete extraction (UI classes only referenced via `:micronaut-pyronaut-cli` dependency). Fixed by extracting concrete Tamboui UI/protocol/resource files into `pyronaut-tui` and decoupling module runtime dependency.
- 2026-03-16: Tamboui/JLine requires a real interactive terminal; in non-interactive shells the TUI can fail early (bad file descriptor/terminal size). Guard added to fail fast with clear message and precondition exit code.
