# Testing Pyronaut Releases

## Clean Linux release validation

Use this runbook to validate the complete release-installation flow without
using a developer's Python, Java, GraalVM, Pyronaut cache, Maven repository, or
source fixtures. It creates both managed fixtures from scratch inside a fresh
x86_64 Linux container and resolves their non-Pyronaut dependencies from
Sonatype's Maven snapshots repository.

The runbook uses the host Docker socket for Docker-backed test resources. The
only host state passed to the container is the Docker socket and the
read-capable `PYRONAUT_RELEASE_TOKEN`; no host filesystem mount is required.

Build the SDK wheel and the three Linux native bundles from the same revision.
The bundle manifests and wheel setup contract must stay in sync:

```bash
./gradlew :micronaut-pyronaut:buildSdkWheel \
  :micronaut-pyronaut-dev:assemble \
  :micronaut-pyronaut-run:assemble \
  :micronaut-pyronaut-run-python:assemble
```

When updating a release, replace the wheel and all three matching native assets:

```text
pyronaut-dev-linux-amd64-0.0.1-SNAPSHOT.tar.gz
pyronaut-run-linux-amd64-0.0.1-SNAPSHOT.tar.gz
pyronaut-run-python-linux-amd64-0.0.1-SNAPSHOT.tar.gz
```

Use release-owner credentials with asset-write access when replacing the
wheel. The validation container only requires a read-capable
`PYRONAUT_RELEASE_TOKEN`.

The command matrix is:

| Fixture | Native mode | JVM mode | Assertion |
| --- | --- | --- | --- |
| Direct `app.py` | `run`, `dev` | — | `/hello` returns `Hello World` |
| Direct `App.java` | `run`, `dev` | — | `/hello` returns `Hello World` |
| Generated `simple-python` | `run`, `test`, `dev` | `run`, `test`, `dev` | `/` returns `Hello World`; tests pass |
| Generated `fresh5` external Gradle project | `run`, `test`, `dev` | — | `/hello` returns `Hello World`; tests pass |

Direct source execution always uses the native `pyronaut-dev` launcher. The
`--jvm` and `--native` variants therefore apply to the two managed projects.
The normal published-release run intentionally leaves
`~/.pyronaut/settings.toml` absent so that both the GitHub source and the
native bundle version are resolved from the installed wheel's defaults.

Start a fresh container from the host. Do not use `set -x` because the token is
passed through the environment. Leave the container named until its logs and
cache evidence have been copied out:

```bash
docker run --name pyronaut-release-validation -it \
  --platform linux/amd64 \
  --env PYRONAUT_RELEASE_TOKEN \
  --volume /var/run/docker.sock:/var/run/docker.sock \
  python:3.12-slim-bookworm bash
```

To validate a locally rebuilt wheel before uploading it, add these two options
to the same command (from the repository root):

```bash
--env PYRONAUT_LOCAL_WHEEL=/input/pyronaut-wheel.whl \
--volume "$(pwd)/pyronaut/build/wheel/dist/pyronaut-0.0.1.dev0-py3-none-any.whl:/input/pyronaut-wheel.whl:ro"
```

Use the local-wheel options while validating an updated CLI before uploading
it: the no-settings assertion exercises defaults embedded in the wheel, while
the release must already contain native bundles built from the same setup
descriptor format. Once the wheel asset has been replaced, omit these options
to validate the published wheel.

Run the following inside the container:

[source,bash]
----
set -euo pipefail

# Use HTTPS for Debian metadata and package downloads.
for apt_source in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do
  [ -f "$apt_source" ] || continue
  sed -i 's|http://deb.debian.org|https://deb.debian.org|g' "$apt_source"
done
apt-get update
apt-get install --yes --no-install-recommends ca-certificates curl docker.io jq unzip util-linux

rm -rf /work
mkdir -p /work/downloads /work/home /work/logs /work/projects
export HOME=/work/home
export PATH=/work/cpython-venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

: "${PYRONAUT_RELEASE_TOKEN:?PYRONAUT_RELEASE_TOKEN is required}"
if command -v java >/dev/null 2>&1; then
  echo "The validation container must not provide Java before Pyronaut runs" >&2
  exit 1
fi
docker info >/work/logs/docker-info.txt

python -m venv /work/cpython-venv
source /work/cpython-venv/bin/activate
python -c 'import sys; assert sys.implementation.name == "cpython"; print(sys.version)'

REPOSITORY_API=https://api.github.com/repos/micronaut-projects/pyronaut
RELEASE_TAG="${PYRONAUT_RELEASE_TAG:-v0.0.1-SNAPSHOT}"
RELEASE_DRAFT="${PYRONAUT_RELEASE_DRAFT:-false}"
WHEEL_NAME=pyronaut-0.0.1.dev0-py3-none-any.whl
LOCAL_REPOSITORY=/work/maven-local

AUTH_HEADERS=(
  -H 'Accept: application/vnd.github+json'
  -H 'X-GitHub-Api-Version: 2022-11-28'
  -H "Authorization: Bearer ${PYRONAUT_RELEASE_TOKEN}"
)

# Save the authenticated response as release evidence without ever printing
# the Authorization header or the token.
curl --fail --silent --show-error "${AUTH_HEADERS[@]}" \
  "$REPOSITORY_API/releases/tags/$RELEASE_TAG" \
  > /work/logs/release.json
jq -e --arg tag "$RELEASE_TAG" --argjson draft "$RELEASE_DRAFT" \
  '.tag_name == $tag and .draft == $draft' \
  /work/logs/release.json >/dev/null

for asset in "$WHEEL_NAME"; do
  jq -e --arg name "$asset" '.assets[] | select(.name == $name)' \
    /work/logs/release.json >/dev/null
done

WHEEL_URL=$(jq -r --arg name "$WHEEL_NAME" \
  '[.assets[] | select(.name == $name) | .url][0] // empty' \
  /work/logs/release.json)
test -n "$WHEEL_URL"
if [ -n "${PYRONAUT_LOCAL_WHEEL:-}" ]; then
  cp "$PYRONAUT_LOCAL_WHEEL" "/work/downloads/$WHEEL_NAME"
else
  WHEEL_URL="$WHEEL_URL" WHEEL_PATH="/work/downloads/$WHEEL_NAME" \
    python - <<'PY'
import os
import urllib.request
from pathlib import Path

request = urllib.request.Request(
    os.environ["WHEEL_URL"],
    headers={
        "Accept": "application/octet-stream",
        "Authorization": f"Bearer {os.environ['PYRONAUT_RELEASE_TOKEN']}",
        "X-GitHub-Api-Version": "2022-11-28",
    },
)
with urllib.request.urlopen(request) as response:
    Path(os.environ["WHEEL_PATH"]).write_bytes(response.read())
PY
fi
sha256sum "/work/downloads/$WHEEL_NAME" | tee /work/logs/wheel.sha256

# Install the wheel manually into a CPython virtual environment and prove the
# CLI is running on CPython before any managed or native command is started.
python -m pip install --no-deps "/work/downloads/$WHEEL_NAME"
python -c 'import sys; assert sys.implementation.name == "cpython"'
pyronaut --version | tee /work/logs/pyronaut-version.txt

# No settings file is created for the published-release validation. Resolve
# the default native-image source and bundle version through the installed
# wheel itself; these must produce GitHub Releases and 0.0.1-SNAPSHOT from the
# wheel's 0.0.1.dev0 version.
NATIVE_BASE_URL=$(python -c 'from pyronaut_cli_v2 import cli; print(cli._native_image_configuration()[0])')
test "$NATIVE_BASE_URL" = "https://github.com/micronaut-projects/pyronaut/releases/"
NATIVE_VERSION=$(python -c 'from pyronaut_cli_v2 import cli; print(cli._native_image_configuration()[1])')
test "$NATIVE_VERSION" = "0.0.1-SNAPSHOT"
test ! -e "$HOME/.pyronaut/settings.toml"
printf 'Native bundle version resolved from wheel: %s\n' "$NATIVE_VERSION" \
  | tee /work/logs/native-version.txt

for asset in \
  "pyronaut-dev-linux-amd64-${NATIVE_VERSION}.tar.gz" \
  "pyronaut-run-linux-amd64-${NATIVE_VERSION}.tar.gz" \
  "pyronaut-run-python-linux-amd64-${NATIVE_VERSION}.tar.gz"; do
  jq -e --arg name "$asset" '.assets[] | select(.name == $name)' \
    /work/logs/release.json >/dev/null
done

# Draft validation may opt into an exact tag. The default published run keeps
# settings.toml absent and therefore exercises GitHub's v<version> lookup.
if [ "$RELEASE_DRAFT" = true ]; then
  mkdir -p "$HOME/.pyronaut"
  cat > "$HOME/.pyronaut/settings.toml" <<EOF
[native-images]
release-tag = "$RELEASE_TAG"
EOF
fi

# The writable Maven repository is intentionally empty. Pyronaut stages the
# Pyronaut module JARs and BOM from the installed wheel there during install;
# every other project dependency must come from Maven Central.
mkdir -p "$LOCAL_REPOSITORY"

mkdir -p /work/projects/direct-python /work/projects/direct-java
cat > /work/projects/direct-python/app.py <<'EOF'
from micronaut.http.annotation import Get


@Get(value="/hello", produces="text/plain")
def hello() -> str:
    return "Hello World"
EOF
cat > /work/projects/direct-java/App.java <<'EOF'
package example;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller
public class App {
    @Get("/hello")
    public String hello() {
        return "Hello World";
    }
}
EOF

mkdir -p /work/projects/simple-python/src/simple_python \
  /work/projects/simple-python/tests /work/projects/simple-python/config
cat > /work/projects/simple-python/pyproject.toml <<'EOF'
[project]
name = "simple-python"
version = "0.1.0"

[build-system]
requires = ["setuptools", "wheel", "tomli"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
package-dir."" = "src"
packages.find.where = ["src"]

[tool.pyronaut]
repositories = ["mavenCentral"]
core.version = "5.2.0"
platform.version = "5.1.0"

[tool.pyronaut.toolchain]
type = "native"

[tool.pyronaut.processor]
incremental = true
daemon = true
python-incremental-mode = "optimistic"

[tool.pyronaut.validation]
validate-dependency-injection = true
dependency-injection-validation-strategy = "reachable"
suppressions = ["micronaut.inject", "micronaut.inject-trace", "endpoints.*"]

[tool.pyronaut.sources]
python = "src"
python-test = "tests"
resources = "config"

[tool.pyronaut.dependencies]
runtime = [
  "io.micronaut:micronaut-http-server-netty",
  "io.micronaut.serde:micronaut-serde-jackson",
  "io.micronaut.pyronaut:micronaut-pyronaut-logback"
]
build = ["io.micronaut.serde:micronaut-serde-processor"]
test = [
  "io.micronaut.pyronaut:micronaut-pyronaut-pytest",
  "io.micronaut.pyronaut:micronaut-pyronaut-requests",
  "io.micronaut.test:micronaut-test-junit5"
]
EOF
cat > /work/projects/simple-python/src/simple_python/controller.py <<'EOF'
from dataclasses import dataclass

from micronaut.http import HttpResponse
from micronaut.http.annotation import Get
from micronaut.serde.annotation import Serdeable


@Serdeable
@dataclass
class HelloResponse:
    message: str


@Get(value="/", produces="application/json")
def index() -> HttpResponse[HelloResponse]:
    return HttpResponse.ok(HelloResponse("Hello World")).header("Foo", "Bar!!!")
EOF
cat > /work/projects/simple-python/tests/test_simple_python.py <<'EOF'
from typing import Any

import pytest

from micronaut.runtime.server import EmbeddedServer
from pyronaut import requests
from pyronaut.test import MicronautTest, micronaut_test_fixture


@pytest.fixture
def application_context(request: Any) -> Any:
    fixture = micronaut_test_fixture(
        request,
        MicronautTest(environments=["test"], transactional=False)
    )
    yield fixture
    fixture.stop()


@pytest.fixture
def client(application_context: Any) -> requests.Session:
    session = requests.with_context(application_context)
    yield session
    session.close()


def test_application_starts(application_context: Any) -> None:
    server = application_context[EmbeddedServer]
    assert server.isRunning()


def test_index(client: requests.Session) -> None:
    response = client.get("/")

    assert response.status_code == 200, response.text
    assert response.json() == {"message": "Hello World"}
    assert response.headers["Content-Type"].startswith("application/json")
EOF
cat > /work/projects/simple-python/config/application.toml <<'EOF'
[micronaut.application]
name = "simple-python"

[graalpy.engine]
allow-experimental-options = true
options = { engine.CompilerThreads = 2 }

[logger.levels]
io.micronaut.context.python = "TRACE"

[endpoints.all]
sensitive = false
EOF

mkdir -p /work/projects/fresh5/src/main/java/fresh5 \
  /work/projects/fresh5/src/main/resources /work/projects/fresh5/src/test/java/fresh5
cat > /work/projects/fresh5/settings.gradle.kts <<EOF
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
rootProject.name = "fresh5"
EOF
cat > /work/projects/fresh5/build.gradle.kts <<EOF
plugins {
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.micronaut.aot") version "5.0.2"
}

group = "fresh5"
version = "0.1"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    compileOnly("io.micronaut:micronaut-http-client")
    runtimeOnly("ch.qos.logback:logback-classic")
    testImplementation("io.micronaut:micronaut-http-client")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application { mainClass = "fresh5.Application" }

java {
    sourceCompatibility = JavaVersion.toVersion("25")
    targetCompatibility = JavaVersion.toVersion("25")
}

graalvmNative.toolchainDetection = false
micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing { incremental(true); annotations("fresh5.*") }
    aot {
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}

tasks.withType<AbstractTestTask>().configureEach { failOnNoDiscoveredTests = false }
EOF
cat > /work/projects/fresh5/gradle.properties <<'EOF'
micronautVersion=5.0.4
EOF
cat > /work/projects/fresh5/src/main/java/fresh5/Application.java <<'EOF'
package fresh5;

import io.micronaut.runtime.Micronaut;

public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
EOF
cat > /work/projects/fresh5/src/main/java/fresh5/HelloController.java <<'EOF'
package fresh5;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller
class HelloController {
    @Get("/hello")
    String hello() {
        return "Hello World";
    }
}
EOF
cat > /work/projects/fresh5/src/test/java/fresh5/Fresh5Test.java <<'EOF'
package fresh5;

import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@MicronautTest
class Fresh5Test {
    @Inject EmbeddedApplication<?> application;

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }
}
EOF

# The external Gradle fixture is self-contained too. Download Gradle into the
# fresh HOME rather than relying on an installed host Gradle or JDK.
GRADLE_VERSION=9.5.1
GRADLE_USER_HOME=/work/gradle-home
export GRADLE_USER_HOME
curl --fail --silent --show-error --location \
  --retry 4 --retry-all-errors --retry-delay 2 \
  "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
  --output /work/gradle.zip
unzip -q /work/gradle.zip -d /work
export PATH="/work/gradle-${GRADLE_VERSION}/bin:$PATH"

# Pyronaut embeds GraalPy in its delegated runners, but simple-python's pytest
# package belongs in a GraalPy virtual environment. Keep this separate from
# the CPython environment hosting the installed CLI.
export PYENV_ROOT=/work/pyenv
PYENV_ARCHIVE=/tmp/pyenv.tar.gz
curl --fail --silent --show-error --location \
  --retry 4 --retry-all-errors --retry-delay 2 \
  https://github.com/pyenv/pyenv/archive/refs/heads/master.tar.gz \
  --output "$PYENV_ARCHIVE"
tar -xzf "$PYENV_ARCHIVE" -C /work
mv /work/pyenv-master "$PYENV_ROOT"
export PATH="$PYENV_ROOT/bin:$PYENV_ROOT/shims:$PATH"
eval "$(pyenv init -)"
for pyenv_attempt in 1 2 3; do
  if pyenv install --skip-existing graalpy3.13-25.3.4.1; then
    break
  fi
  if [ "$pyenv_attempt" = 3 ]; then
    echo "GraalPy provisioning failed after ${pyenv_attempt} attempts" >&2
    exit 1
  fi
  sleep "$((pyenv_attempt * 2))"
done
GRAALPY_EXECUTABLE=$(PYENV_VERSION=graalpy3.13-25.3.4.1 pyenv which python)
"$GRAALPY_EXECUTABLE" -m venv /work/projects/simple-python/.venv || true
for launcher in graalpy python python3 python3.13; do
  ln -sf "$GRAALPY_EXECUTABLE" "/work/projects/simple-python/.venv/bin/$launcher"
done
/work/projects/simple-python/.venv/bin/python -m pip install --upgrade pip pytest
/work/projects/simple-python/.venv/bin/python -c \
  'import sys; assert "graal" in sys.version.lower(), sys.version; import pytest; print(sys.version, pytest.__version__)'

STATUS_FILE=/work/logs/command-status.tsv
: > "$STATUS_FILE"
stop_http_process() {
  local pid="$1"
  # Pyronaut delegates to another launcher process. Because the command was
  # started with setsid, terminate the complete process group so a later
  # matrix row cannot inherit its port or daemon.
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" || true
}
run_http() {
  local use_default_port=false
  if [ "${1:-}" = "--default-port" ]; then
    use_default_port=true
    shift
  fi
  local label="$1"
  local port="$2"
  local path="$3"
  shift 3
  local log="/work/logs/${label}.log"
  if [ "$use_default_port" = true ]; then
    setsid "$@" >"$log" 2>&1 &
  else
    setsid env MICRONAUT_SERVER_PORT="$port" "$@" >"$log" 2>&1 &
  fi
  local pid=$!
  # A fresh external Gradle project can resolve several source sets and
  # annotation processors before its application starts. Allow ten minutes
  # for that first cold start; subsequent cache-hit invocations are normally
  # much faster.
  for _ in $(seq 1 600); do
    if response=$(curl --fail --silent "http://127.0.0.1:${port}${path}" 2>/dev/null); then
      printf '%s\n' "$response" >"/work/logs/${label}.response"
      if grep -Fq 'Hello World' <<<"$response"; then
        printf '%s\t0\n' "$label" >> "$STATUS_FILE"
        stop_http_process "$pid"
        return 0
      fi
      printf '%s\t1\n' "$label" >> "$STATUS_FILE"
      stop_http_process "$pid"
      return 1
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      printf '%s\t1\n' "$label" >> "$STATUS_FILE"
      cat "$log" >&2
      return 1
    fi
    sleep 1
  done
  printf '%s\t1\n' "$label" >> "$STATUS_FILE"
  cat "$log" >&2
  stop_http_process "$pid"
  return 1
}

run_test() {
  local label="$1"
  shift
  local log="/work/logs/${label}.log"
  if "$@" >"$log" 2>&1; then
    printf '%s\t0\n' "$label" >> "$STATUS_FILE"
  else
    local status=$?
    printf '%s\t%s\n' "$label" "$status" >> "$STATUS_FILE"
    cat "$log" >&2
    return "$status"
  fi
}

# Installed-wheel execution is deliberately unavailable until the complete SDK
# has been provisioned. The failure text and exit code are part of the CLI
# contract and remain available without Java.
set +e
pyronaut run --local-repository "$LOCAL_REPOSITORY" \
  /work/projects/direct-python/app.py > /work/logs/pre-setup.log 2>&1
PRE_SETUP_STATUS=$?
set -e
test "$PRE_SETUP_STATUS" -eq 8
grep -Fxq 'Pyronaut setup is missing or stale. Run pyronaut setup.' /work/logs/pre-setup.log

run_test sdk-setup \
  pyronaut setup --allow-draft-release --progress on \
  --local-repository "$LOCAL_REPOSITORY"
grep -Fq 'Downloading GraalVM SDK... 0%' /work/logs/sdk-setup.log
grep -Fq 'Downloading GraalVM SDK... 100%' /work/logs/sdk-setup.log
grep -Fq 'Downloading pyronaut-dev... 0%' /work/logs/sdk-setup.log
grep -Fq 'Downloading pyronaut-run... 0%' /work/logs/sdk-setup.log
grep -Fq 'Downloading pyronaut-run-python... 0%' /work/logs/sdk-setup.log
grep -Fq 'Pyronaut setup: locating or provisioning a compatible GraalVM JDK (JDK 25+)...' /work/logs/sdk-setup.log
grep -Fq 'Pyronaut setup: provisioning native launchers...' /work/logs/sdk-setup.log
grep -Fq 'Pyronaut setup: resolving SDK dependencies...' /work/logs/sdk-setup.log
if grep -Fq $'\r' /work/logs/sdk-setup.log; then
  echo 'Setup log contains carriage-return progress output' >&2
  exit 1
fi

# A second setup with identical inputs validates and reuses the published state.
run_test sdk-setup-cache-hit \
  pyronaut setup --allow-draft-release --progress on \
  --local-repository "$LOCAL_REPOSITORY"
grep -Fq 'Pyronaut setup is ready at' /work/logs/sdk-setup-cache-hit.log

(
  cd /work/projects/direct-python
  run_http direct-python-run 18081 /hello \
    pyronaut --allow-draft-release run --progress on \
    --local-repository "$LOCAL_REPOSITORY" app.py
  run_http direct-python-dev 18082 /hello \
    pyronaut --allow-draft-release dev --local-repository "$LOCAL_REPOSITORY" app.py
)

(
  cd /work/projects/direct-java
  run_http direct-java-run 18083 /hello \
    pyronaut --allow-draft-release run --local-repository "$LOCAL_REPOSITORY" App.java
  run_http direct-java-dev 18084 /hello \
    pyronaut --allow-draft-release dev --local-repository "$LOCAL_REPOSITORY" App.java
)

# Gradle itself needs a JVM. Setup provisioned it before invoking the Java
# dependency resolver, so expose that container-local SDK to the Gradle fixture.
SDK_JAVA=$(find -L "$HOME/.pyronaut/sdks" -type f -path '*/bin/java' -print -quit)
test -n "$SDK_JAVA"
export JAVA_HOME="${SDK_JAVA%/bin/java}"
gradle --version | tee /work/logs/gradle-version.txt

run_test simple-python-install \
  pyronaut --allow-draft-release install --progress on \
  --local-repository "$LOCAL_REPOSITORY" --project-dir /work/projects/simple-python
test -f "$LOCAL_REPOSITORY/io/micronaut/pyronaut/micronaut-pyronaut-logback/0.0.1-SNAPSHOT/micronaut-pyronaut-logback-0.0.1-SNAPSHOT.jar"
test -f "$LOCAL_REPOSITORY/io/micronaut/pyronaut/micronaut-pyronaut-bom/0.0.1-SNAPSHOT/micronaut-pyronaut-bom-0.0.1-SNAPSHOT.pom"
run_http --default-port simple-python-native 8080 / \
  pyronaut --allow-draft-release run --progress on \
  --local-repository "$LOCAL_REPOSITORY" --native --project-dir /work/projects/simple-python
run_test simple-python-test-native \
  pyronaut --allow-draft-release test --local-repository "$LOCAL_REPOSITORY" \
  --native --project-dir /work/projects/simple-python
run_http --default-port simple-python-dev-native 8080 / \
  pyronaut --allow-draft-release dev --local-repository "$LOCAL_REPOSITORY" \
  --native --project-dir /work/projects/simple-python
run_http --default-port simple-python-jvm 8080 / \
  pyronaut --allow-draft-release run --progress on \
  --local-repository "$LOCAL_REPOSITORY" --jvm --project-dir /work/projects/simple-python
run_test simple-python-test-jvm \
  pyronaut --allow-draft-release test --local-repository "$LOCAL_REPOSITORY" \
  --jvm --project-dir /work/projects/simple-python
run_http --default-port simple-python-dev-jvm 8080 / \
  pyronaut --allow-draft-release dev --local-repository "$LOCAL_REPOSITORY" \
  --jvm --project-dir /work/projects/simple-python

run_http --default-port fresh5-native 8080 /hello \
  pyronaut --allow-draft-release run --progress on \
  --local-repository "$LOCAL_REPOSITORY" --native --project-dir /work/projects/fresh5
run_test fresh5-test-native \
  pyronaut --allow-draft-release test --local-repository "$LOCAL_REPOSITORY" \
  --native --project-dir /work/projects/fresh5
run_http --default-port fresh5-dev-native 8080 /hello \
  pyronaut --allow-draft-release dev --local-repository "$LOCAL_REPOSITORY" \
  --native --project-dir /work/projects/fresh5

test -d "$HOME/.pyronaut/sdks"
SDK_JAVA=$(find -L "$HOME/.pyronaut/sdks" -type f -path '*/bin/java' -print -quit)
test -n "$SDK_JAVA"
"$SDK_JAVA" -version 2>&1 | tee /work/logs/provisioned-java-version.txt

for tool in pyronaut-dev pyronaut-run pyronaut-run-python; do
  find -L "$HOME/.pyronaut/tools" -type f -name "$tool" -print -quit | grep -q .
done

NATIVE_CACHE="$HOME/.pyronaut/bin/${NATIVE_VERSION}/linux-amd64"
for tool in pyronaut-dev pyronaut-run pyronaut-run-python; do
  test -x "$NATIVE_CACHE/$tool"
  if [ "$RELEASE_DRAFT" = true ]; then
    jq -e --arg tag "$RELEASE_TAG" '."release-tag" == $tag' \
      "$NATIVE_CACHE/${tool}.json" >/dev/null
  else
    jq -e '."release-tag" == null' "$NATIVE_CACHE/${tool}.json" >/dev/null
  fi
done

PYRONAUT_TRACE_DELEGATION=true run_http --default-port cache-hit 8080 /hello \
  pyronaut --allow-draft-release run --local-repository "$LOCAL_REPOSITORY" \
  --native --project-dir /work/projects/fresh5
find "$HOME/.pyronaut" -maxdepth 6 -print | sort > /work/logs/pyronaut-cache-tree.txt
----

After the script succeeds, copy the evidence before removing the container:

```bash
docker cp pyronaut-release-validation:/work/logs ./pyronaut-release-validation-logs
docker cp pyronaut-release-validation:/work/home/.pyronaut ./pyronaut-release-validation-cache
docker rm pyronaut-release-validation
```

The log directory contains the authenticated release response, wheel checksum,
CPython and Gradle versions, every command's exit status, HTTP responses,
delegation/provisioning logs, and the final Pyronaut cache tree. The cache must
contain the provisioned SDK under `~/.pyronaut/sdks`, delegated tools under
`~/.pyronaut/tools`, and the three existing native bundles under the
wheel-resolved `~/.pyronaut/bin/<version>/linux-amd64` directory. The resolved
version is recorded in `native-version.txt`. The setup log must retain visible
0% and 100% download progress, while the second setup and every execution
command reuse those cached assets.
