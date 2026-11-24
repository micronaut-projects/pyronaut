#!/usr/bin/env bash
set -euo pipefail

mode=${1:?usage: bash ci/test-native-wheel.sh <native|crema> [cpython]}
python=$(command -v "${2:-python3}")
case "$mode" in
  native) build_flag=--native ;;
  crema) build_flag=--native-base ;;
  *) echo "Unknown wheel mode: $mode" >&2; exit 2 ;;
esac
test "$(uname -sm)" = "Linux x86_64"
"$python" -c 'import sys; assert sys.implementation.name == "cpython", sys.version'

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
work=$(mktemp -d)
echo "Wheel test workspace: $work"
export PYRONAUT_HOME="$work/pyronaut-home"
mkdir -p "$PYRONAUT_HOME"

tasks=(:micronaut-pyronaut:buildSdkWheel)
if [[ $mode == crema ]]; then
  tasks+=(:micronaut-pyronaut-run-python:nativeBundle)
  # Bare --native-base must use the bundle built from this checkout.
  "$python" - "$root" > "$PYRONAUT_HOME/settings.toml" <<'PY'
import json
import sys

print('[native-images]')
print('base-url = ' + json.dumps(sys.argv[1], ensure_ascii=False))
PY
fi
./gradlew --no-daemon --console=plain "${tasks[@]}"

"$python" -m venv "$work/.venv-pyronaut"
"$work/.venv-pyronaut/bin/python" -m pip install pyronaut/build/wheel/dist/pyronaut-*.whl
"$work/.venv-pyronaut/bin/pyronaut" setup

# Pin the example from the pyronaut-0.1.2 branch used in the startup report.
git init -q "$work/pyronaut-examples"
git -C "$work/pyronaut-examples" fetch -q --depth 1 \
  https://github.com/micronaut-projects/pyronaut-examples.git 25c1e4a2277e6144f3dd3b60b28a5f62c32f6843
git -C "$work/pyronaut-examples" checkout -q --detach FETCH_HEAD
cd "$work/pyronaut-examples/hello-world/python"
"$work/.venv-pyronaut/bin/pyronaut" build main.py "$build_flag" --verbose

"$python" -m venv "$work/.venv-wheel-$mode"
"$work/.venv-wheel-$mode/bin/python" -m pip install --no-deps dist/main-0.1.0-py3-none-linux_x86_64.whl

# StartupEvent is published before the server can answer /hello. Launch from
# a clean directory so the application has to use its installed wheel.
mkdir "$work/launch"
cd "$work/launch"
port=18080
log="$work/main.log"
MICRONAUT_SERVER_PORT=$port "$work/.venv-wheel-$mode/bin/main" > "$log" 2>&1 &
pid=$!
cleanup() {
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  cat "$log"
}
trap cleanup EXIT
for ((attempt = 0; attempt < 120; attempt++)); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "$mode wheel launcher exited before serving /hello" >&2
    exit 1
  fi
  if response=$(curl --fail --silent --noproxy '*' --max-time 2 "http://127.0.0.1:$port/hello"); then
    [[ "$response" == 'Hello World' ]]
    printf '%s wheel returned %s\n' "$mode" "$response"
    exit 0
  fi
  sleep 1
done
echo "$mode wheel did not serve /hello before the timeout" >&2
exit 1
