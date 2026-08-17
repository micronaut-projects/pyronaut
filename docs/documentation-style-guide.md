# Pyronaut documentation style guide

Use this guide when writing or reviewing Pyronaut README content, introductions,
tutorials, user guides, website copy, and AI-agent-facing instructions.

## Audience and purpose

Assume the primary reader is a Python developer who wants to build and operate
an application. Do not assume Java, JVM, Gradle, Maven, or Micronaut internals.
Platform engineers and AI agents are important secondary readers; give them
precise commands and links to deeper technical reference without making those
details the first thing every reader sees.

Every page should answer these questions in order:

1. What is this?
2. Why does it matter to a Python application?
3. When should I use it?
4. How do I use it?
5. What are the relevant limitations, runtime choices, or troubleshooting steps?

Lead with the user outcome, then explain the implementation only as far as the
task requires. Keep one canonical explanation for each concept and link to it
from tutorials, references, and troubleshooting pages.

## Product language

Use:

- **complete application platform for Python** for Pyronaut's category.
- **Python applications, not Python plumbing** for the central value proposition.
- **integrated development-to-production workflow** for the end-to-end experience.
- **Python application platform** or **platform** for concise references after
  Pyronaut has been introduced.
- **Python application** in formal documentation. Use **app** only in casual
  tutorial prose or names such as `hello-world`.
- **Pyronaut is to Python what Node.js is to JavaScript** in launch, overview,
  or campaign copy. Explain that this describes the integrated application
  experience, not Node.js API compatibility.

Avoid leading with:

- Java framework
- JVM framework
- classpath
- Micronaut internals
- delegated executables
- orchestrator architecture

These terms can appear in technical reference, troubleshooting, or extension
guides when they help the reader complete a task.

Do not claim that Pyronaut replaces CPython, supports every Python package,
eliminates all integration work, or is faster than another framework without a
published, representative benchmark.

## Canonical terminology

Use the following terms consistently.

| Prefer | Use when | Avoid or qualify |
|---|---|---|
| application platform | Describing Pyronaut's category and value | framework, runtime, toolkit as the primary description |
| application workflow | The path from project creation to deployment | toolchain experience, orchestration flow |
| project layout | The standard directories such as `src`, `tests`, and `config` | project structure when referring specifically to directories |
| project configuration | Settings in `pyproject.toml` and related files | project setup when the page means configuration |
| application model | The conventions that define how a Pyronaut app is organized and runs | project layout when discussing behavior |
| install dependencies | User action performed by `pyronaut install` | resolve dependencies in task instructions |
| dependency resolution | Internal behavior, diagnostics, or configuration reference | install when describing implementation details |
| process sources | User action performed by `pyronaut process` | compile sources unless the operation is specifically compilation |
| source processing | Technical explanation of the processing phase | processing pipeline in introductory copy |
| application context | The context available to an application or test | Micronaut context in first-use explanations |
| Micronaut application context | Technical reference where the Micronaut implementation matters | application context only when precision is required |
| test resources | Generic concept | test-resources, except in command names or file paths |
| Micronaut Test Resources | The branded Micronaut feature | Docker-backed test resources when the product name is enough |
| container image | A deployment image in general | Docker image unless Docker is specifically the platform or command |
| GraalPy application wheel | A wheel containing a Pyronaut application for GraalPy | Python wheel when that could imply CPython compatibility |
| native executable | A closed-world Native Image output | native binary in user-facing prose |
| Native Image | The GraalVM product or technology | native-image except for the executable/tool name or command |
| deployment artifact | A wheel, container image, executable, or other deployable output | package when the output is not a Python package |
| CLI | The `pyronaut` command-line interface | command line as a product noun |
| `pyronaut` command | A specific user action or command entry point | CLI when a concrete command is being described |
| runtime | A deployment execution environment | engine unless referring to a specific engine implementation |
| toolchain | The configured runtime/JDK/GraalVM selection | environment when the selection is a toolchain concern |

## Runtime and implementation terminology

Mention runtime choices in deployment and packaging guidance, not in the first
sentence of the README or introduction:

- **JVM runtime** is the default and most mature deployment choice today.
- **Crema runtime** is experimental and should not be presented as the default
  or production-mature.
- **closed-world native image** is an alternative for fast startup or a small
  runtime footprint when the application's compatibility requirements allow it.
- **GraalPy** is the Python runtime foundation. Introduce it as an enabler, not
  as a prerequisite users must understand to write Python code.
- **Micronaut infrastructure** supports the application platform. Do not use
  “Micronaut application development” as the README's primary definition.

When explaining a technical limitation, define the term at first use. For
example: “A closed-world Native Image analyzes the application at build time,
so dynamically discovered classes or resources may require additional
metadata.”

## Command and code style

- Put the shortest working path before optional configuration.
- Use exact commands in code formatting, for example `pyronaut install`.
- Explain whether a command is run from the repository root or the application
  directory.
- Use `pyproject.toml` as the project configuration filename, never
  `pyproject` alone when precision matters.
- Use `pytest` for the Python test framework and `pyronaut test` for the
  Pyronaut test workflow.
- Use `config/application.toml` and `tests-config/application-test.toml` when
  referring to the standard configuration locations.
- Use generic paths such as `/path/to/project`; never include a developer's
  home directory, username, or machine-specific path.
- Prefer examples that can be copied without hidden prerequisites. State
  required GraalPy environments, pytest installation, Docker, repositories, or
  snapshot dependencies explicitly.
- In AsciiDoc, use `[source,bash]` blocks for shell commands and Micronaut
  dependency macros where the build supports them.

## Page structure

### README and landing pages

Use this order:

1. What Pyronaut is and the Python developer outcome.
2. What the platform provides.
3. The shortest first-service workflow.
4. A concise note about deployment choices.
5. Contributor setup, advanced CLI reference, functional testing, and docs
   build instructions.

Keep JVM, classpath, module names, and source-build prerequisites below the
first user workflow.

### Concept pages

Start with a definition and explain the problem the concept solves. Then show
the relevant model, trade-offs, and links to task-oriented pages. Do not turn a
concept page into a command dump.

### How-to pages

State the desired outcome, list prerequisites, and use numbered steps. Include
one complete working example before alternatives. End with verification and
the most likely failure mode.

### Reference pages

Use stable headings, exact option names, supported values, and canonical links.
Keep explanations short and link to concepts or how-to pages instead of
duplicating their narrative.

## Final review checklist

- Does the opening explain the user value before implementation details?
- Are “project layout,” “project configuration,” and “application model” used
  for their distinct meanings?
- Are `install` and `process` used for user actions, with “dependency
  resolution” and “source processing” reserved for technical explanation?
- Is “GraalPy application wheel” used where CPython compatibility could be
  misunderstood?
- Are `Native Image`, `native executable`, and `native-image` used precisely?
- Are container images called Docker images only when Docker-specific behavior
  is being discussed?
- Is JVM/classpath information placed in the appropriate deeper section?
- Are Crema's experimental status and the JVM's mature-default status accurate?
- Are commands, paths, dependencies, and runtime prerequisites explicit?
- Are there duplicate explanations that should become a cross-reference?
- Are there personal paths, unsupported claims, or unexplained jargon?
