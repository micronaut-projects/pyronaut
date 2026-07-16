# External-build fixtures

These fixtures are derived from Micronaut Launch applications generated with:

- `build=GRADLE_KOTLIN` for `gradle-kotlin`;
- `build=MAVEN` for `maven`.

The fixtures intentionally omit build-tool wrapper binaries so the resolver's
PATH fallback is exercised. The E2E task can run them with the locally
installed `gradle` and `mvn` commands.
