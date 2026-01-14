# Pyronaut

Tools for integrating Python with the Micronaut ecosystem.

This repository contains a Gradle multi-module project (root: `pyronaut-parent`) that provides:
- A Micronaut Project Generator extension to scaffold a Python-oriented project structure (templates, default features).
- A small Micronaut web app to preview the generated project tree and code samples.
- A BOM module to align dependency versions across modules.

## Modules

- pyronaut-projectgen
  - Implements Micronaut ProjectGen features to generate a starter Python project.
  - Default features (non-visible) add:
    - `pyproject.toml` configured for setuptools/wheel, with Pyronaut tool configuration.
    - `setup.py` build helper.
    - Micronaut banner, logging config, hello world controller files + tests.
  - Uses Rocker to compile Java from the template files.

- pyronaut-projectgen-app
  - A Micronaut Netty application that renders a preview of a generated project.
  - Endpoints:
    - `GET /` renders the main page.
    - `POST /modal/preview` accepts form data, generates the project in-memory, and returns a modal view with a file tree and code samples.
  - Bundles static assets (Bootstrap, htmx, Prism, treeview). Target runtime: Java 21.

Versions and catalogs are managed via `gradle/libs.versions.toml`, and Micronaut’s standardized build plugins are used.

## Run the Preview App

- Start the app:
  - `./gradlew :micronaut-pyronaut-projectgen-app:run`
- Open:
  - http://localhost:8080
- From the UI, submit the preview form to see:
  - A file tree of the generated project
  - Code samples extracted from generated files

## How it Works (High Level)

- Generation pipeline
  - The preview app builds `Options` from submitted form data.
  - `PreviewGenerator` produces a map of file paths to contents.
  - `TreeNodeGenerator` turns that map into a navigable tree for the UI.
  - Code samples are derived from the generated files for display.

## License

Licensed under the Apache License, Version 2.0. See `LICENSE` for details.
