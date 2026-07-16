# Maven and Gradle project support

## Summary

Add an internal external-build project model so `pyronaut install`, `run`, and `test` can operate directly on single-project Maven or Gradle Java applications without `pyproject.toml`.

## Implementation

- Detect root-level `pom.xml` and Gradle descriptors (`build.gradle`, `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`), with build descriptors taking precedence over `pyproject.toml`.
- Add an internal project-layout model covering project kind, Java/resource source directories, resolved build/runtime/development-runtime/test classpaths, and the annotation-processor classpath.
- Resolve Maven layouts through the Maven wrapper or `mvn` using Maven's generated effective POM (including inherited compiler/resource configuration) and Maven dependency resolution; resolve Gradle layouts through the Gradle wrapper or `gradle` plus a temporary init script. Preserve configured source sets, resources, repositories, constraints, BOMs, substitutions, and transitive annotation-processor dependencies.
- Persist the resolved external layout and include descriptors/wrapper inputs in cache fingerprints.
- Filter native-provided artifacts by `group:artifact`; use only the native compile classpath as the compiler base.
- Load the persisted layout from `run` and `test`, compiling Java sources through `pyronaut-processor` and adding production/test resources to the appropriate classpaths. Keep any merged external source roots under the current project’s `__pyronaut__` directory.
- Update Python CLI detection, preflight, and delegation while preserving existing Pyproject behavior.

## Verification

- Add unit and command tests for detection, precedence, cache invalidation, resource/source-set resolution, and native-artifact filtering.
- Add Maven and Gradle Kotlin integration fixtures verifying run/test compilation and resource visibility.

The Maven fixture also verifies that compiler processors inherited from its parent POM are persisted with their transitive classpath.
