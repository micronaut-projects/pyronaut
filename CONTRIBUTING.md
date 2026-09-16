# Contributing Code or Documentation to Micronaut

Sign the [Contributor License Agreement (CLA)](https://cla-assistant.io/micronaut-projects/micronaut-pyronaut). This is required before any of your code or pull-requests are accepted.

## Finding Issues to Work on

If you are interested in contributing to Micronaut and are looking for issues to work on, take a look at the issues tagged with [help wanted](https://github.com/micronaut-projects/micronaut-xxx/issues?q=is%3Aopen+is%3Aissue+label%3A%22status%3A+help+wanted%22).

## JDK Setup

Micronaut pyronaut currently requires JDK 21.

## IDE Setup

Micronaut pyronaut can be imported into IntelliJ IDEA by opening the `build.gradle` file.

## Docker Setup

Micronaut pyronaut tests currently require Docker to be installed.

On Linux with a running Docker daemon, the native runner base images can be
built with:

```bash
./gradlew :micronaut-pyronaut-run:buildDockerImage
./gradlew :micronaut-pyronaut-run-python:buildDockerImage
```

The tasks create `pyronaut-run:<version>`/`pyronaut-run:latest` and
`pyronaut-run-python:<version>`/`pyronaut-run-python:latest`. They are skipped
when the host is not Linux or Docker is unavailable. Use either image as
`PYRONAUT_BASE_IMAGE` in a consumer Dockerfile:

```dockerfile
ARG PYRONAUT_BASE_IMAGE
FROM ${PYRONAUT_BASE_IMAGE}
WORKDIR /app
COPY app/config /app/config
COPY app/__pyronaut__/classes /app/__pyronaut__/classes
COPY app/__pyronaut__/schemas /app/__pyronaut__/schemas
ENTRYPOINT ["/opt/pyronaut/bin/pyronaut-run", "--project-dir", "/app"]
```

## Running Tests

To run the tests, use `./gradlew check`.

## Building Documentation

The documentation sources are located at `src/main/docs/guide`.

To build the documentation, run `./gradlew publishGuide` (or `./gradlew pG`), then open `build/docs/index.html`

To also build the Javadocs, run `./gradlew docs`.

## Working on the code base

If you use IntelliJ IDEA, you can import the project using the Intellij Gradle Tooling ("File / Import Project" and selecting the "settings.gradle" file).

Micronaut Core `5.2.2` and stable GraalPy artifacts are resolved from Maven Central.

## Testing native bundles from a checkout

The Python wheel intentionally does not contain the large native launchers.
When iterating on those launchers locally, build the bundles with:

```bash
./gradlew -PprojectVersion=0.0.3 :micronaut-pyronaut-dev:assemble \
  :micronaut-pyronaut-run:assemble \
  :micronaut-pyronaut-run-python:assemble
```

Point the CLI at the checkout in `~/.pyronaut/settings.toml` and select the
archive version produced by Gradle (the `projectVersion` from
`gradle.properties` when `-PprojectVersion` is omitted):

```toml
[native-images]
base-url = "/absolute/path/to/pyronaut"
version = "0.0.3"
```

The same setting accepts a `file:///absolute/path/to/pyronaut` URL. Relative
paths are resolved from the directory where the CLI is run. For each launcher,
Pyronaut searches the corresponding module's
`build/distributions/<launcher>-<os>-<architecture>-<version>.tar.gz`, for
example `pyronaut-dev/build/distributions/pyronaut-dev-macos-aarch64-0.0.3.tar.gz`.
The archive is unpacked into `~/.pyronaut/bin` just like a downloaded bundle.

## Testing a release

The repeatable clean-container release-validation runbook is maintained in
[`TESTING.md`](TESTING.md). It covers wheel installation into CPython,
authenticated GitHub asset verification, SDK and native-tool provisioning,
fresh managed fixtures, native/JVM execution, progress reporting, and cache
evidence.

## Creating a pull request

Once you are satisfied with your changes:

- Commit your changes in your local branch
- Push your changes to your remote branch on GitHub
- Send us a [pull request](https://help.github.com/articles/creating-a-pull-request)

## Merging a pull request

Before we merge into a module's `master` branch a PR, we have to consider.

Can this PR be merged into a patch release (e.g. documentation fixes, bug fix, patch transitive dependency upgrade, breaking change due to security, GitHub actions sync, Micronaut Build Plugin upgrade)?

Should this PR be merged into the next minor version of the module? For example, a new feature, a new module, or a minor transitive dependency upgrade.

If the PR is going into the next minor version of the module, we need to release a patch version, and branch off `master` a new branch for the current minor module's version. If the `gradle.properties`'s `projectVersion` is 3.1.2-SNAPSHOT the branch should be named 3.1.x, and we push it to GitHub. If `master` contains only commits such as GitHub actions sync (no commits with benefits to users), we can branch off without doing a patch release.

When you merge a PR which will go into the next Module's minor.

- Update `gradle.properties`'s `githubCoreBranch` to point to the next minor branch of Micronaut Core.
- Update `gradle.properties`'s `projectVersion` to the next minor snapshot.
- Upgrade the module to the latest version of Micronaut.

## Checkstyle

We want to keep the code clean, following good practices about organization, Javadoc, and style as much as possible.

Micronaut XXX uses [Checkstyle](https://checkstyle.sourceforge.io/) to make sure that the code follows those standards. The configuration is defined in `config/checkstyle/checkstyle.xml`. To execute Checkstyle, run:

```
./gradlew <module-name>:checkstyleMain
```

Before starting to contribute new code we recommended that you install the IntelliJ [CheckStyle-IDEA](https://plugins.jetbrains.com/plugin/1065-checkstyle-idea) plugin and configure it to use Micronaut's checkstyle configuration file.

IntelliJ will mark in red the issues Checkstyle finds. For example:

![](https://github.com/micronaut-projects/micronaut-core/raw/master/src/main/docs/resources/img/checkstyle-issue.png)

In this case, to fix the issues, we need to:

- Add one empty line before `package` in line 16
- Add the Javadoc for the constructor in line 27
- Add a space after `if` in line 34

The plugin also adds a new tab in the bottom of the IDE to run Checkstyle and show errors and warnings. We recommend that you run the report and fix all issues before submitting a pull request.
