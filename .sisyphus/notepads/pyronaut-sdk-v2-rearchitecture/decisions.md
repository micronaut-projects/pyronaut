## Notepad: pyronaut-sdk-v2-rearchitecture - decisions

_(records from this session appear here)_

- 2026-03-13: Locked down the `--tests` selector semantics (nodeid/path passthrough vs. wildcard) and committed to filtering after descriptors exist so we can reuse `PytestTestDescriptor.matchesId`.
- 2026-03-13: Chose tolerant default-main behavior for `pyronaut-run`: missing `pyronaut_application.PyronautMain` is treated as non-fatal for default flow, with reflective Micronaut startup attempted first and concise non-stacktrace diagnostics only if both paths are unavailable.
- 2026-03-13: For Task 27 restart semantics, implemented stdlib polling watcher (no new dependency), with roots limited to `src/tests/config`, explicit debounce window, and generated-output ignore behavior to prevent restart loops.
- 2026-03-13: For Task 28 reporting, kept JUnit XML generation in pytest itself (`--junitxml`) and generated HTML summary in Java listener, avoiding a hard runtime dependency on `pytest-html` while still producing deterministic `index.html` output.
- 2026-03-13: For Task 29 provisioning, restricted automatic JAVA_HOME injection to delegated `run`/`test` commands and only enabled default provisioning when using real subprocess execution (not unit-test injected runners), preserving deterministic tests while keeping production auto-provision behavior.

- 2026-03-13: Use `pytest.tests` (pipe-delimited) as the single forwarding channel for `--tests` selectors from CLI to the JUnit engine. Keep parsing/validation in `pyronaut-test` deterministic (reject blank/newline selectors), and keep orchestrator order install → process → test unchanged.
- 2026-03-13: For Task 30 mode control, enforce strict allowed values (`jvm|native`) in both orchestrator flag/config parsing and config-model parsing instead of silently falling back for invalid values.
- 2026-03-13: Kept native build default main class as `pyronaut_application.PyronautMain` to match existing pyronaut native compile baseline; retained `--main-class` override for future non-reflective/native entrypoint variants.
- 2026-03-13: For `--tests` compatibility, prioritize direct file resolution when selectors are concrete file-like stems/paths (including stem-only names) and only retain broad source-dir discovery when wildcard/mixed selectors require descriptor-level filtering.
- 2026-03-13: For report UX compatibility, keep canonical report generation under `__pyronaut__/reports/tests` but mirror artifacts to project root as discoverability aliases; emit warnings (not hard failures) if mirror/remove operations fail.
- 2026-03-13: For run auto-restart lifecycle, fail fast when managed process shutdown cannot be completed after terminate+kill escalation, rather than silently continuing and leaving orphaned delegates.
