---
name: pyronaut-positioning
description: Align Pyronaut user-facing documentation, README content, introductions, website copy, tutorials, and launch messaging with its Python-first complete application platform positioning. Use when writing or reviewing Pyronaut messaging for Python developers, platform teams, or AI agents.
license: MIT
compatibility: Compatible with Agent Skills specification and repository-local documentation workflows.
metadata:
  author: Pyronaut project
  version: "1.0.0"
---

# Pyronaut positioning

Use this skill whenever Pyronaut documentation is intended to attract, orient,
or guide Python developers or AI agents. Keep the product message Python-first
while making technical details discoverable in the appropriate deeper guide.

## Positioning

Position Pyronaut as the complete application platform for Python.

Use this analogy in prominent launch or overview copy when useful:

> Pyronaut is to Python what Node.js is to JavaScript.

Explain the analogy immediately: Pyronaut provides an integrated path from
project creation to a production service. It is not a claim of API
compatibility with Node.js, a replacement for CPython, or unrestricted
compatibility with every Python package.

Describe the customer problem as the assembled Python application stack: a
framework, server, dependency tooling, testing setup, configuration conventions,
container files, build scripts, and deployment glue that each team must select,
connect, and maintain.

Use the core promise:

> Python applications, not Python plumbing.

Describe the outcome as one coherent path from Python source to a running
service. Emphasize that developers work with Python, `pyproject.toml`, pytest,
and the `pyronaut` CLI.

## Message hierarchy

Lead with these ideas, in this order:

1. Pyronaut is a complete application platform for Python.
2. One CLI and project model cover creation, dependencies, development,
   testing, validation, packaging, and deployment.
3. Python applications get integrated HTTP, dependency injection, configuration,
   serialization, logging, test resources, and web-experience capabilities.
4. The same project model supports local development, CI, and production
   artifacts.
5. Pyronaut is powered by GraalPy and Micronaut infrastructure.

Do not lead landing pages, README introductions, or first-run tutorials with
Java, the JVM, classpaths, Micronaut internals, or implementation modules.
Mention those details when they help a technical reader choose a runtime,
understand compatibility, troubleshoot a build, or extend the platform.

## Runtime guidance

Document the runtime choices accurately, but keep them secondary to the
Python-first product message:

- The JVM runtime is the default and most mature option today, with the broadest
  compatibility and highest peak throughput.
- Crema-based runtimes are experimental for now. Do not present Crema as the
  default or as production-mature until debugging, virtual threads, GraalPy
  deployment support, and other required capabilities are ready.
- Closed-world native images are an available deployment option for use cases
  that value startup time or a small runtime footprint and can meet native-image
  constraints.

When users need a recommendation, tell them to start with the mature JVM path
unless their deployment requirements call for native images. Defer detailed
runtime trade-offs to packaging, deployment, or CLI reference pages.

## Distribution and developer experience

The desired user experience is an all-in-one installation comparable to
`brew install node`, with distribution through channels such as pip, Homebrew,
or SDKMAN when available. Do not document these channels as available until
they are actually released.

The CLI can discover or download compatible toolchains into
`~/.pyronaut/sdks`. A PyPI SDK wheel may omit Crema and native images because
of distribution-size limits; those artifacts can be downloaded on demand when
the user selects a native toolchain.

When documenting the current source-build workflow, be explicit that it is a
contributor setup requiring a compatible GraalVM and GraalPy. Do not let those
source-build prerequisites define the normal Pyronaut user experience.

## Approved language

Prefer:

- complete application platform for Python
- Python applications, not Python plumbing
- integrated development-to-production workflow
- one coherent path from source to production service
- stop assembling your Python backend
- from `pyproject.toml` to a production artifact, one platform
- built on GraalPy, with Micronaut infrastructure underneath

Avoid or qualify:

- replace CPython
- CPython is obsolete
- drop-in compatible with every Python package
- Pyronaut eliminates all integration work
- faster than FastAPI, unless backed by a published representative benchmark
- zero configuration or no integration ever
- JVM-first, Java-first, or classpath-first descriptions in introductory copy

Do not frame CPython, Python, FastAPI, or Node.js as the opponent. Contrast
Pyronaut with the integration work required to assemble and operate an
application stack. Treat framework comparisons as architectural contrasts and
support performance claims with reproducible measurements.

## Documentation workflow

When creating or reviewing a user-facing page:

1. Identify the audience and task. Assume the primary reader is a Python
   developer who wants to build and operate an application, not a Java expert.
2. Start with what Pyronaut is or what the feature provides in user terms.
3. Explain why the feature matters in the application workflow.
4. Show the shortest working Python-first example.
5. Add configuration, testing, packaging, or deployment details as the task
   requires.
6. Put JVM, classpath, GraalPy internals, native-image limits, and Micronaut
   implementation details in explanatory sections or linked reference pages.
7. Verify that commands, dependencies, runtime claims, and distribution claims
   match the current repository before publishing.

For README and introduction pages, prefer a short value proposition followed
by a clear first-service workflow. For technical guides, retain complete
runtime and JVM documentation but introduce it with the user outcome it serves.
Keep one canonical explanation per concept and link to it from task-oriented
pages.

For detailed terminology, page structure, and wording rules, consult
`docs/documentation-style-guide.md` in the repository.

## Review checklist

Before finalizing user-facing Pyronaut documentation, verify:

- The opening sentence identifies Pyronaut as a Python application platform.
- The first workflow uses Python, `pyproject.toml`, pytest, and `pyronaut`.
- The page does not imply that users need Java or JVM knowledge to begin.
- Runtime choices are accurate and do not present Crema as mature by default.
- Native-image trade-offs are explained only where relevant.
- Current source-build prerequisites are distinguished from the intended
  packaged-install experience.
- No unsupported compatibility, performance, or zero-configuration claim is
  made.
- No personal filesystem paths or user-specific machine details appear.
- The page links to deeper technical documentation rather than duplicating it.

## Trigger examples

Use this skill for prompts such as:

- “Rewrite the README for Python developers before launch.”
- “Review the introduction so it explains Pyronaut without leading with the
  JVM.”
- “Write a landing page for Pyronaut’s complete application platform.”

Do not use this skill for prompts such as:

- “Fix a Java compiler error in `pyronaut-install`.”
- “Update an internal Gradle task description with no user-facing messaging.”
- “Document a private test helper whose wording does not affect product
  positioning.”
