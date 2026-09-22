# Pyronaut Documentation Style Guide

This guide defines the writing, terminology, structure, and formatting conventions for Pyronaut's user-facing
documentation. Use it when writing or reviewing the guide in `src/main/docs/guide`, module READMEs, CLI guidance, and
examples.

The user guide is written in AsciiDoc and assembled through `src/main/docs/guide/toc.yml`. Follow the conventions in
this file together with the local documentation build and the source code or tests that define the behavior being
documented.

## Voice and tone

- Address the reader as **you**.
- Prefer active voice and direct phrasing.
- Do not invent or guess an actor to force active voice. Name the component that performs the action when that matters;
  use concise passive voice when the actor is unknown or unimportant.
- Prefer action-first wording, such as "Use `pyronaut install` to resolve dependencies."
- Keep the tone user-facing. Explain internal behavior only when it helps the reader make a decision or diagnose a
  problem.
- Keep introductions, summaries, and paragraphs brief and concrete.
- Prefer one idea per paragraph and use headings to make longer pages easy to scan.
- Use present tense for stable behavior. Use versioned or dated wording only when the timing is important.
- Avoid unnecessary implementation jargon. When a technical term is necessary, define it at first use.
- Avoid vague claims such as "easy", "simple", "seamless", "magic", or "just" unless the claim is demonstrated by the
  surrounding workflow.
- When describing behavior Pyronaut provides, name Pyronaut or the concrete component when that makes the sentence
  clearer: "Pyronaut generates..." or "The launcher includes...". Do not attribute Pyronaut-provided behavior to
  Micronaut alone. Keep Micronaut as the actor when describing the external framework, its documented APIs, or behavior
  outside Pyronaut's control.
- Prefer **Micronaut** or a concrete component over **Java** or **JVM** when the language or runtime label adds no
  useful information. Keep Java/JVM terminology for source-language support, API and package names, toolchain modes,
  package formats, flags, and compatibility boundaries.
- Describe limitations plainly. Prefer headings such as **Supported behavior**, **Limits**, or **Troubleshooting**;
  do not call a page a **stub**.

### Product voice and framework attribution

Describe capabilities included in Pyronaut as Pyronaut behavior. Prefer Pyronaut or the concrete component as the
active subject: **Pyronaut includes**, **The launcher provides**, **The processor generates**, and **The binder maps**.
Use **we** only when no clearer active subject exists. Do not describe an included Pyronaut capability as if Micronaut
provides it independently.

| Prefer | Avoid |
| --- | --- |
| The launcher includes Micronaut's HTTP server and validation support. | Micronaut provides the HTTP server and validation support. |
| The processor generates route and bean metadata before startup. | Micronaut generates route and bean metadata before startup. |
| Pyronaut exposes Micronaut APIs through Python decorators. | Java APIs are available through Python decorators. |

Use **Micronaut** when naming the framework model, an included Micronaut module, an API, a configuration property, or
an external Micronaut reference. Prefer **Micronaut** or a concrete component over **Java** or **JVM** in general
descriptions when the language or runtime does not affect the reader's decision. Keep Java/JVM terminology when it
identifies a source language, API or package name, toolchain mode, package format, flag, or compatibility boundary.

Do not replace a precise technical term with Micronaut merely to reduce Java/JVM mentions. Change the surrounding prose,
not literal commands, identifiers, paths, package coordinates, source-language labels, or configuration values.

## Page boundaries and page types

Give each page one primary reader goal and one primary surface. Do not combine CLI, Python API, Java API, IDE, and
deployment instructions into one page unless comparing those surfaces is the purpose of the page.

- **Overview** pages orient the reader to an area. Explain what it covers and when to use it. Link to child pages
  inline when they contain details the overview intentionally omits.
- **Concepts** pages explain a mental model, lifecycle, or design trade-off. Keep them high-level and link to worked
  examples.
- **Quickstarts and tutorials** lead the reader through a complete, progressively built example. End with a working
  result and links to the concepts or guides needed to extend it.
- **Guides and how-to pages** complete one concrete task from prerequisites through verification. Keep optional detail
  out of the main path.
- **Reference** pages document exact commands, options, properties, files, exit codes, API behavior, or compatibility
  boundaries. Do not turn a reference page into a general tutorial.
- **Integration guides** explain how Pyronaut works with a Python package, library, Micronaut module, build tool,
  IDE, or external service. Include setup, a minimal example, supported behavior, limitations, and testing.
- **Decision guides** help the reader choose between supported approaches. Start with the decision, show the meaningful
  trade-offs, then provide the commands or configuration for the selected option.
- **Troubleshooting pages** organize content by symptom. Each item should identify a diagnostic check and an actionable
  fix or workaround.

Use authored pages for reader goals, choices, workflows, focused examples, and troubleshooting. If generated API or
configuration reference exists, link to it instead of copying the complete contract into an authored page.

The documentation build renders the guide as one continuous page, but readers may enter through search or jump between
sections instead of reading linearly. Entry-point pages, quickstarts, and tutorials may end with a concise wayfinding section titled
`== Next steps`. Use a short bulleted list of two or three goal-based links; do not repeat the table of contents or
summarize the page again. Use **Next steps** when the reader can choose between paths or when the tutorial has a natural
continuation.
Reference pages should use inline cross-references instead of a closing navigation section.

## Navigation and file organization

- Treat `src/main/docs/guide/toc.yml` as the navigation source of truth.
- Add every visible page to `toc.yml`, and make sure every entry resolves to a real `.adoc` file.
- Preserve the existing navigation order unless the documentation change intentionally changes the guide structure.
- Use a directory for a group of related pages and a same-named `.adoc` file as its landing page when appropriate. For
  example, `testing.adoc` is the landing page for the `testing/` pages.
- Use descriptive lower-camel-case filenames that match local conventions, such as `directSourceDeclarations.adoc` and
  `fastApiTutorial.adoc`.
- Do not rename or move a page without updating `toc.yml` and all cross-references.
- Keep executable examples under `src/main/docs/examples` when they are shared with tests or need independent
  verification.
- Keep images and other documentation assets under `src/main/docs/resources/`.

Every page must have one visible title, supplied either by its AsciiDoc title or by the guide's navigation conventions.
Do not create a duplicate title in both places.

## Titles and headings

- Use title case for all page titles, including the top-level AsciiDoc `= ...` title and navigation labels in `toc.yml`.
- Use sentence case for `==` and lower-level section headings. Do not treat section headings as page titles.
- Start guide titles with an action verb when possible, such as **Configure Application Properties** or **Run Tests**.
- Use nouns or gerunds for reference headings, such as **Command options**, **Configuration properties**, or **Package
  formats**.
- Do not put commands, option syntax, or long code expressions in a title.
- Use action-oriented titles for procedural steps.
- Keep headings short enough to scan in the generated table of contents.
- Use `== Step N: Action` headings for the main steps of a multi-step tutorial or guide. Do not manually number nested
  actions as `1.1` or `2.1`; let AsciiDoc numbering handle them.
- Do not use decorative horizontal rules between ordinary sections. The AsciiDoc heading hierarchy should provide the
  structure.

## Internal and external links

- Use AsciiDoc cross-references for pages in the same guide, such as `<<configuration,Configuration and build setup>>`.
- Use descriptive link text. Do not expose raw URLs when a meaningful label is available.
- Keep cross-reference anchors stable when possible. If a page is renamed, update the target page, `toc.yml`, and every
  link to it in the same change.
- Use external links only for external resources, such as the official Micronaut, GraalVM, GraalPy, Maven, Gradle,
  Docker, or Python documentation.
- Prefer official and durable external URLs. Avoid links to search results or temporary issue comments when a stable
  reference exists.
- Link to a narrower page when it contains the details the current page intentionally omits. Do not add a link after
  every sentence or step.

## Examples and source reuse

- Make examples copyable. Include imports, file paths, required dependencies, and the command that runs the example when
  those details are necessary to reproduce it.
- Add a short lead-in before an executable code block when the command's purpose or expected result is not obvious.
  Prefer goal-led wording such as "Start the development server:" over generic wording such as "For example:". For a
  clear, single-step command, concise wording such as "Run:" is sufficient.
- Put the expected result or important behavior after a command block.
- Use the correct AsciiDoc source language: `[source,bash]`, `[source,python]`, `[source,java]`, `[source,toml]`,
  `[source,json]`, and so on.
- Prefer `snippet::` for examples that are synchronized with executable sources or tests. Use `include::` when the
  included source is authoritative and a snippet tag is not practical.
- Keep examples consistent with the current implementation. Do not preserve an example merely because it appears in an
  older page.
- Use realistic neutral values such as `hello-pyronaut`, `example.com`, or `8080`. Use angle-bracket placeholders such
  as `<project-dir>` when the reader must substitute a value in a shell command.
- Never hide a required environment variable, working directory, virtual environment, dependency, or setup command.
- Keep code identifiers, commands, option names, package coordinates, file names, and environment variables exactly as
  implemented. Improve the surrounding prose instead of changing a literal technical name.
- When a dependency instruction is needed, prefer the `dependency:` documentation macro over separate handwritten
  Maven and Gradle blocks when the macro is supported.
- Prefer generated configuration-property references when the build provides them. Do not manually duplicate a generated
  property table.

## Procedural guides

A guide should make the reader's goal clear before the first command.

Use this shape for a multi-step workflow:

```adoc
= Configure the application

This guide shows you how to configure ... and verify ... .

== Prerequisites

Before you begin, make sure you have ... .

== Step 1: Create the project configuration

. Create `pyproject.toml`:
+
[source,toml]
----
...
----
+
The file defines ... .

== Step 2: Verify the configuration

. Run the validation command:
+
[source,bash]
----
pyronaut validate-config --scenario dev
----
+
The command reports ... .

== Troubleshooting

* *The command reports ...:* Check ... and run ... again.

```

Procedural rules:

- Give each step one reader action.
- Begin action text with an imperative verb and end a command lead-in with a colon.
- Put the command or code immediately after the action.
- Explain what the command changes and what success looks like after the code block.
- Keep background information outside the numbered action list or attach it to the action it explains.
- Separate required steps from optional variants. Do not interrupt the main path with every supported flag.
- Use a focused option table when several options affect the workflow. Put broad CLI option coverage in the CLI
  reference.
- Use **verification** for the reader's check that the task worked. Reserve **validation** for technical validation
  logic, such as `pyronaut validate-config`.

## CLI documentation

Use the exact command name in inline code and code blocks. Introduce command blocks with the user's goal:

```adoc
Build a JVM wheel:

[source,bash]
----
pyronaut build --jvm
----

The command writes the wheel to `dist/`.
```

For command reference pages, document the following where relevant:

- synopsis
- purpose and operating mode
- positional arguments and command-specific options
- configuration and environment-variable precedence
- generated files, reports, or output artifacts
- exit-code conventions
- platform or prerequisite requirements
- one or two representative examples
- related commands and pages

Keep common flags in the reference page unless they are essential to the task being taught. Do not use vague command
lead-ins such as **For example:** when the sentence can state the user's goal.

## Configuration and dependency documentation

- Show the smallest configuration that accomplishes the stated goal before showing optional settings.
- Explain where a setting is read, which scope it affects, its default, and what takes precedence when multiple sources
  can define it.
- Distinguish project configuration in `pyproject.toml` from runtime application configuration in
  `config/application.toml`.
- Distinguish Python package installation from Micronaut dependency resolution. Do not imply that `pyronaut install` installs
  Python packages unless that behavior is explicitly documented and tested.
- Keep exact TOML keys, Maven coordinates, environment variables, and command options unchanged.
- For a list of related settings, use a focused AsciiDoc table with a header row. Do not create a table for one setting
  that can be explained clearly in prose.
- Mention the command that refreshes generated state after configuration changes when that matters, such as
  `pyronaut install` after changing dependency or IDE settings.

## Tables, callouts, and formatting

- Use AsciiDoc tables for comparisons, option lists, mappings, and compact property summaries.
- Keep tables focused. If a table needs several paragraphs per cell, use subsections instead.
- Use `[source,<language>]` blocks with `----` delimiters for code.
- Use `NOTE:`, `TIP:`, `WARNING:`, and `IMPORTANT:` sparingly, only when the reader could otherwise make a costly or
  unsafe mistake.
- Keep prerequisite lists as short phrases without full stops. If an item is a complete sentence, use complete sentences
  and full stops for every item in that list.
- Use bold for emphasis and labels, not for entire sentences or every new technical term.
- Use inline code for commands, options, file names, paths, configuration keys, package coordinates, classes, methods,
  and environment variables.
- Use `*` or `-` lists consistently within a page. Do not mix list styles without a structural reason.
- Do not add diagrams when a short explanation or table is clearer. When a diagram materially improves understanding,
  keep the source asset with the documentation resources and provide useful alternative text.

## Pyronaut terminology

Use these terms consistently in documentation, examples, CLI help, and release-facing explanations. Exact commands,
package names, option names, and API identifiers remain unchanged even when the surrounding prose uses the preferred term.

| Preferred term | Meaning | Avoid when referring to the same thing |
| --- | --- | --- |
| **Pyronaut** | The product and framework. | Pyronaut framework product, Pyronaut system |
| **Pyronaut CLI** | The `pyronaut` command and its user-facing command set. | the Pyronaut tool, the Python script |
| **project directory** | The directory Pyronaut treats as the project root for a command. | workspace, project folder when precision matters |
| **project mode** | Execution using a project manifest or build model, such as `pyproject.toml`, Maven, or Gradle. | managed mode, normal mode |
| **direct-source mode** | Execution or packaging from source files without a project manifest. | one-file mode, ad hoc mode |
| **direct-source application** | An application launched from source files in direct-source mode. | standalone app, script, unless it is literally a script |
| **project configuration** | Build-time settings for a project, including sources, dependencies, packaging, and toolchain settings. | application configuration |
| **application configuration** | Runtime Micronaut settings, normally in `config/application.toml` or its environment-specific variants. | project settings |
| **generated state** | Files Pyronaut creates for processing, manifests, schemas, reports, stubs, caches, and similar outputs. | source files, application state |
| **processed output** | Classes and metadata generated from application sources so the application can run. | compiled Python, generated application, transformed code |
| **processing** | The Pyronaut phase that reads source declarations and generates Micronaut metadata and classes. | startup scanning, reflection processing |
| **Pyronaut launcher** | A focused executable such as `pyronaut-dev`, `pyronaut-run`, or `pyronaut-test`. | runtime, binary, tool, unless the distinction is unimportant |
| **development mode** | The behavior of `pyronaut dev`, including reload-oriented execution and development support. | dev environment when referring to the command behavior |
| **deployment execution** | Running an application with `pyronaut run` or from a packaged artifact. | production mode, live mode, deploy mode |
| **JVM toolchain** | The GraalVM-based toolchain used to run Pyronaut commands and applications on the JVM. | JVM mode when referring to a toolchain |
| **native toolchain** | The toolchain that uses native Pyronaut launchers for supported command execution. | native mode when the subject is the CLI toolchain |
| **package format** | The output selected for an application, such as a wheel, FAT JAR, Docker image, or native package. | build mode |
| **native image** | A native executable produced through native-image analysis. | native binary when the build model matters |
| **Crema production runtime** | The reusable native production runtime used by the Crema package formats. | base image when the runtime is the subject |
| **GraalPy virtual environment** | A project Python environment created with GraalPy and used for Python packages and pytest-backed workflows. | Python environment when it could be CPython |
| **embedded GraalPy runtime** | The GraalPy runtime supplied inside Pyronaut's application or launcher environment. | local Python, system Python |
| **Pyronaut SDK** | The installed Pyronaut distribution and its machine-local tool state, including provisioned launchers and dependencies. | application runtime, Python installation |
| **Micronaut bean** | An object managed by the Micronaut application context. | service when the framework-managed lifecycle matters |
| **route** | An HTTP method and path mapping handled by the application. | endpoint when describing the Python declaration itself |
| **configuration property** | A named Micronaut setting such as `micronaut.server.port`. | config value when precision matters |
| **Test Resources server** | The Micronaut Test Resources process that provisions or exposes test infrastructure. | Docker server, test database |
| **owned server** | A Test Resources server started and stopped by the current Pyronaut command. | local server |
| **external server** | A Test Resources server already running outside the current command. | shared server unless it is intentionally shared |

Capitalise proper names and exact product labels: **Pyronaut**, **Pyronaut CLI**, **GraalPy**, **GraalVM**, **Micronaut**,
**Test Resources**, **Control Panel**, **OpenAPI**, **Swagger UI**, and **ReDoc**. Use sentence case for ordinary prose.

Prefer **application** over **app** in prose. Keep `app` when it is part of a literal file name, path, command, package,
sample name, or code identifier.

Keep these distinctions clear:

- `pyronaut setup` provisions machine-local SDK and launcher state; `pyronaut install` resolves project dependencies and
  writes project generated state.
- `pyronaut process` processes sources; it does not mean that the application has started.
- The JVM or native **toolchain** controls how Pyronaut tooling runs. The **package format** controls what `pyronaut
  build` produces.
- A GraalPy virtual environment supplies Python packages. The embedded GraalPy runtime executes application code inside
  Pyronaut's launch environment.

## Review checklist

Before submitting documentation, check that:

- The page has one clear reader goal and one primary surface.
- The page type matches the content: overview, concepts, quickstart, guide, reference, integration, decision, or
  troubleshooting.
- The page title and headings follow the capitalization rules.
- `toc.yml` contains the page and all cross-references resolve.
- Commands, paths, options, package coordinates, and configuration keys match the implementation.
- Behavioral, compatibility, performance, and platform claims are backed by implementation, tests, or an authoritative
  source and are qualified to the scope that evidence covers.
- Active-voice sentences name the component that performs the action. Use "we" only when no clearer subject exists, and
  do not assign Pyronaut-provided behavior to Micronaut merely because the implementation uses Micronaut.
- Examples include their required setup and use the correct language tag.
- Source executable examples from tests, or check them against tests, when possible.
- Configuration defaults, precedence, generated files, and limitations are explicit where relevant.
- Troubleshooting items name a symptom and give an actionable diagnostic or fix.
- Use `Next steps` only on entry-point pages, quickstarts, and tutorials when two or three goal-based links help the
  reader continue. Use inline cross-references on reference pages.
- The page uses the canonical Pyronaut terminology.
- Run `./gradlew publishGuide` for guide changes; run `./gradlew docs` when API documentation is also relevant.
