---
name: docs
description: Write and maintain Micronaut Framework module guides for micronaut-projects repositories using the repository style guide. Use when users ask to add or update AsciiDoc guide sections, edit guide toc.yml, apply Micronaut docs macros, review documentation wording, or fix docs build/publishing tasks.
license: Apache-2.0
compatibility: Micronaut framework repositories in micronaut-projects generated from micronaut-project-template
metadata:
  author: Álvaro Sánchez-Mariscal
  version: "1.0.0"
---

# Docs (Micronaut Maintainer)

Use this skill for maintainer-facing guide work in Micronaut framework repositories. Do not default to end-user application documentation advice.

## Goal

Implement source-backed documentation changes in `src/main/docs/guide`, keep `toc.yml` and content in sync, and validate with the Gradle docs pipeline used across `micronaut-projects` modules.

## Project style guide

Read the repository-root `STYLEGUIDE.md` before editing or reviewing documentation. Treat it as the source of truth for
user-facing voice, page types, headings, links, examples, formatting, and Pyronaut terminology.

This skill owns the documentation workflow, Micronaut-specific macros, and build validation. `STYLEGUIDE.md` owns
editorial decisions. When the two overlap, apply `STYLEGUIDE.md` for prose and terminology and this skill for repository
workflow and Micronaut documentation mechanics. Follow explicit user instructions when they differ.

## Procedure

1. Read and apply the repository style guide.
2. Confirm repository docs layout and build tasks.
3. Plan `toc.yml` and `.adoc` updates in lockstep.
4. Apply Micronaut docs macro conventions used by maintainers.
5. Prefer generated configuration property references over manual tables.
6. Build and validate `publishGuide` and `docs` outputs.

### 1) Read and apply the repository style guide

- Read `STYLEGUIDE.md` from the repository root before making or reviewing a documentation change.
- Identify the page type and primary reader goal before changing structure or content.
- Use the style guide's canonical terminology, heading rules, link conventions, example conventions, and guidance on
  concise, direct, user-facing prose.
- Keep the style guide as a separate repository-level reference so human contributors and documentation tooling can use
  it as well as agents.

### 2) Confirm docs layout and build tasks

- Check `src/main/docs/guide/toc.yml` first.
- List relevant guide files under `src/main/docs/guide/**/*.adoc`.
- If examples are involved, inspect `doc-examples/` (if present) and plan snippet tags from executable sources.
- If assets are involved, keep images in `src/main/docs/resources/img/`.
- Confirm standard commands from `CONTRIBUTING.md`:
  - `./gradlew publishGuide` (or `./gradlew pG`) for guide assembly.
  - `./gradlew docs` for guide + API docs assembly.
- If repository layout diverges from template conventions, follow local conventions and explicitly report the divergence.

### 3) Keep `toc.yml` and files in lockstep

Treat `src/main/docs/guide/toc.yml` as navigation source of truth.

Rules:

1. Every visible section must exist in `toc.yml`.
2. Every `toc.yml` entry must resolve to a real `.adoc` path.
3. Preserve section order unless the request explicitly changes navigation.
4. Use nested sections for grouped topics when needed.

Example nested pattern:

```yaml
controlPanels:
  title: Available Control Panels
  builtIn: Built-in
  management: Management
```

This maps to:

- `src/main/docs/guide/controlPanels.adoc`
- `src/main/docs/guide/controlPanels/builtIn.adoc`
- `src/main/docs/guide/controlPanels/management.adoc`

### 4) Apply Micronaut docs macro conventions

Use docs macros registered by `micronaut-build` (`DocsExtensionRegistry`) and maintained for framework guides.

Preferred mapping:

| Need | Preferred pattern |
|---|---|
| Dependency instructions | `dependency:group:artifact[scope=...]` |
| Source sample synchronized with test suites | `snippet::path/to/File.ext[tags=...]` |
| Multi-format configuration snippets | `[configuration]` listing blocks |
| Configuration properties reference | `include::{includedir}configurationProperties/<fqcn>.adoc[]` |
| Shell commands | `[source,bash]` blocks |
| Repository/release links | `https://github.com/{githubSlug}` and `/releases` links |

Guardrails:

- Do not handwrite separate Maven/Gradle dependency blocks when `dependency:` is suitable.
- Do not duplicate configuration property tables manually when generated references exist.
- Keep environment-sensitive instructions explicit (for example `MICRONAUT_ENVIRONMENTS=dev`).
- Prefer stable links to official Micronaut docs for endpoint semantics.

### 5) Use generated configuration property references correctly

- `micronaut-docs` provides `AsciiDocPropertyReferenceWriter`, which generates AsciiDoc property fragments from configuration metadata.
- Micronaut docs build wiring consumes those fragments via `{includedir}configurationProperties/...` includes.
- Prefer includes over hand-maintained property tables.

See `references/micronaut-docs-providers.md` for confirmed provider/macro details and source locations.

### 6) Build and validate documentation

From repository root, run:

```bash
./gradlew publishGuide
./gradlew docs
```

Validation checklist:

1. Commands exit with code `0`.
2. Output exists under `build/docs/`.
3. Updated sections appear in navigation and render without missing includes.
4. Added images/links resolve.

If `publishGuide` passes but `docs` fails, fix the failing stage and rerun both commands.

## Maintainer Delivery Contract

When finishing docs work, report:

1. Exact changed files (`.adoc`, `toc.yml`, docs resources).
2. Which conventions were applied (`dependency:`, `snippet::`, `[configuration]`, generated property includes).
3. Build commands run and outcomes.
4. Any repo-specific divergences or follow-ups.
5. For comparison or review requests, include a concise before/after/reasoning table.

## Validation Checklist

- [ ] `toc.yml` and `.adoc` changes are consistent.
- [ ] `STYLEGUIDE.md` was read and its editorial and terminology guidance was applied.
- [ ] Macros and includes follow Micronaut maintainer conventions.
- [ ] `publishGuide` and `docs` executed successfully.
- [ ] Output and navigation verified under `build/docs/`.
- [ ] Guidance remains maintainer-focused (not generic app docs).

## References

- `../../../STYLEGUIDE.md`
- `references/micronaut-docs-providers.md`
- `references/control-panel-patterns.md`
- `CONTRIBUTING.md`
- `src/main/docs/guide/toc.yml`
