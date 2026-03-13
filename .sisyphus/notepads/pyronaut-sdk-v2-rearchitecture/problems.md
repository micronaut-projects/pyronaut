## Notepad: pyronaut-sdk-v2-rearchitecture - problems

_(records from this session appear here)_

- 2026-03-13: No blockers or open problems were encountered during selector work.

- 2026-03-13: EngineTestKit-based assertions on exact started/succeeded counts are brittle when the GraalPy AST parser fails to initialize in some environments; selector behavior is still validated, but discovery/execution statistics should not be hardcoded.
- 2026-03-13: No new blockers after regression hardening; remaining risk is environment-specific pytest/engine behavior variance, mitigated by selector-resolution unit tests plus demo-level behavioral verification.
