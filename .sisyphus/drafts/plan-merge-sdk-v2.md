# Draft: Merge sub-plans into pyronaut-sdk-v2-rearchitecture

## Requirements (confirmed)
- Merge install-usability plan into main rearchitecture plan.
- Merge process-verbose diagnostics plan into main rearchitecture plan.
- Produce consolidated TODO list for merged canonical plan.
- Add explicit action item: in `pyronaut install --dependencies`, unresolved dependencies should still be visualized in the graph with failing modules highlighted in red (plain fallback markers when color disabled).
- Add explicit action item: extend `pyronaut-process`/`pyronaut-processor` to process both source and test trees into separate targets (`__pyronaut__/classes` and `__pyronaut__/test-classes`) using test classpath for test processing and source visibility for tests.
- Add explicit action item: support proxy configuration in `pyronaut-install`, preferring `~/.m2/settings.xml` and falling back to `~/.pyronaut/settings.toml`.
- Add explicit action item: ensure `pyronaut-test` includes generated `__pyronaut__/test-classes` on classpath during test execution.

## Technical Decisions
- Main canonical plan remains `.sisyphus/plans/pyronaut-sdk-v2-rearchitecture.md`.
- Install-usability scope imported as completed history.
- Process-verbose scope imported as pending merged task.

## Research Findings
- Install-usability plan tasks are fully checked complete.
- Process-verbose plan tasks are currently unchecked.
- Main plan has remaining open tasks (11 + final checklist), with partial native/wheel caveats already documented.

## Scope Boundaries
- INCLUDE: Markdown plan consolidation and TODO synthesis.
- EXCLUDE: code implementation of merged tasks.
