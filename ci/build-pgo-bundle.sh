#!/usr/bin/env bash
# Builds one PGO-optimized native bundle: instrument, train, optimize, verify.
#
#   ci/build-pgo-bundle.sh <pyronaut-dev|pyronaut-run|pyronaut-run-python> <version>
#
# Environment:
#   JAVA_HOME                  Oracle GraalVM 25.4 (required)
#   PYRONAUT_GRAALPY           GraalPy executable used to run the CLI during training
#                              (default: ~/.pyenv/versions/<pyronautPyenvVersion>/bin/graalpy)
#   PYRONAUT_BUILDER_MAX_HEAP  native-image builder heap, e.g. 48g (default: native-image's own)
#   PYRONAUT_PGO               set to "false" for an emergency build without PGO
#   PYRONAUT_PGO_OUTPUT        directory for the bundle and evidence (default: build/pgo-output/<image>)
#
# See TESTING.md, "PGO release builds".
set -euo pipefail

image="${1:?usage: $0 <pyronaut-dev|pyronaut-run|pyronaut-run-python> <version>}"
version="${2:?usage: $0 <image> <version>}"
case "$image" in
  pyronaut-dev) task=PyronautDev ;;
  pyronaut-run) task=PyronautRun ;;
  pyronaut-run-python) task=PyronautRunPython ;;
  *) echo "Unknown image: $image" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
output="${PYRONAUT_PGO_OUTPUT:-$root/build/pgo-output/$image}"
mkdir -p "$output"

: "${JAVA_HOME:?JAVA_HOME must point at Oracle GraalVM}"
unset GRAALVM_QUICK_BUILD
export PATH="$JAVA_HOME/bin:$PATH"

# --no-daemon: a ./gradlew --stop elsewhere on the host must not kill a long native build.
gradle=(./gradlew --no-daemon --console=plain "-PprojectVersion=$version")
if [[ -n "${PYRONAUT_GRAALPY:-}" ]]; then
  gradle+=("-Ppyronaut.pgo.graalpy=$PYRONAUT_GRAALPY")
fi
heap=()
if [[ -n "${PYRONAUT_BUILDER_MAX_HEAP:-}" ]]; then
  heap=("-Ppyronaut.pgo.builderMaxHeap=$PYRONAUT_BUILDER_MAX_HEAP")
fi

step() {
  local name=$1
  shift
  echo "== $image: $name"
  local started=$SECONDS
  "${gradle[@]}" "$@" 2>&1 | tee "$output/$name.log"
  echo "== $image: $name finished in $((SECONDS - started))s"
}

if [[ "${PYRONAUT_PGO:-true}" == "false" ]]; then
  echo "PYRONAUT_PGO=false: building $image WITHOUT profile-guided optimization. Say so in the release notes." >&2
  step bundle ":micronaut-$image:nativeBundle"
else
  step instrument -Ppyronaut.pgo=instrument ${heap[@]+"${heap[@]}"} ":micronaut-$image:nativeCompile"
  step train ":micronaut-pgo-training:train$task"
  step optimize -Ppyronaut.pgo=optimize ${heap[@]+"${heap[@]}"} ":micronaut-$image:nativeBundle"

  report="build/pgo/$image/pgo-report.txt"
  grep -q '^pgo: applied' "$report" || { echo "$report does not say that PGO was applied" >&2; exit 1; }
  if [[ "$image" == pyronaut-run && "$(uname -s)" == Linux ]]; then
    grep -q '^code-compression: enabled' "$report" || { echo "$report does not say that code compression was enabled" >&2; exit 1; }
  fi
  cp "$report" "$output/"
  mkdir -p "$output/profiles" "$output/training"
  cp build/pgo/"$image"/profiles/*.iprof "$output/profiles/"
  cp -R "pgo-training/build/training/$image/logs" "pgo-training/build/training/$image/training-summary.json" "$output/training/"
fi

cp "$image"/build/distributions/"$image"-*-"$version".tar.gz "$output/"
echo "== $image: done"
ls -l "$output"
