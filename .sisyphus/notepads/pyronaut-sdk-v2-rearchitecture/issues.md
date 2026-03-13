## Notepad: pyronaut-sdk-v2-rearchitecture - issues

_(records from this session appear here)_

- 2026-03-13: No issues observed while wiring `--tests` selectors across CLI, engine, and orchestrator; filtering relies on normalized nodeids.
- 2026-03-13: Python LSP diagnostics are still unavailable in this environment because `basedpyright-langserver` is not installed; verification relied on targeted test execution.

- 2026-03-13: Python LSP (`basedpyright`) is not installed in this environment, so `lsp_diagnostics` cannot be run for `.py` files here. Java diagnostics are clean and the Gradle verification tasks pass.
- 2026-03-13: Native build entrypoint suitability remains a design risk: `pyronaut_application.PyronautMain` is currently used as baseline default, but a dedicated non-reflective native entrypoint variant may be needed if generated-main startup proves unreliable in real projects.
- 2026-03-13: Python LSP (`basedpyright`) remains unavailable in this environment, so Python static diagnostics are still validated via test execution and behavior-driven repro checks rather than language-server analysis.
