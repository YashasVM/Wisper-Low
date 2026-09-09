#!/usr/bin/env bash
set -Eeuo pipefail

# Install the debug APK, provision an already downloaded model into the app's
# private files directory, and run the real-model instrumentation test.
# Usage: ANDROID_SERIAL=<serial> ./scripts/verify-device.sh /path/to/model-dir

readonly PACKAGE_NAME="com.wisperlow.mobile"
readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly ANDROID_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
readonly APK_PATH="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"

if [[ $# -ne 1 ]]; then
    echo "usage: $0 MODEL_DIRECTORY" >&2
    exit 64
fi

model_dir="$(cd -- "$1" 2>/dev/null && pwd)" || {
    echo "model directory does not exist: $1" >&2
    exit 66
}
model_id="$(basename -- "$model_dir")"
if [[ ! "$model_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "model directory name contains unsupported characters: $model_id" >&2
    exit 64
fi
for required in tokens.txt encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx .installed; do
    [[ -s "$model_dir/$required" ]] || {
        echo "model is missing a required file: $required" >&2
        exit 66
    }
done
[[ "$(<"$model_dir/.installed")" == "$model_id" ]] || {
    echo ".installed must contain exactly the model directory name ($model_id)" >&2
    exit 66
}

adb_bin="${ANDROID_HOME:-}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then
    adb_bin="$(command -v adb || true)"
fi
[[ -n "$adb_bin" && -x "$adb_bin" ]] || {
    echo "adb not found; set ANDROID_HOME or add adb to PATH" >&2
    exit 69
}

adb_args=()
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb_args=(-s "$ANDROID_SERIAL")
else
    mapfile -t devices < <("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    if [[ ${#devices[@]} -ne 1 ]]; then
        echo "connect exactly one authorized device or set ANDROID_SERIAL" >&2
        "$adb_bin" devices >&2
        exit 69
    fi
    adb_args=(-s "${devices[0]}")
fi

adb() {
    "$adb_bin" "${adb_args[@]}" "$@"
}

adb wait-for-device
[[ -f "$APK_PATH" ]] || {
    echo "debug APK not found: $APK_PATH (build it first)" >&2
    exit 66
}

remote_dir="/data/local/tmp/wisperlow-stt-${model_id}-${$}"
cleanup() {
    adb shell rm -rf "$remote_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb install -r "$APK_PATH"
adb shell rm -rf "$remote_dir"
adb shell mkdir -p "$remote_dir"
adb push "$model_dir/." "$remote_dir/"
adb shell run-as "$PACKAGE_NAME" rm -rf "files/models/$model_id"
adb shell run-as "$PACKAGE_NAME" mkdir -p "files/models/$model_id"
adb shell run-as "$PACKAGE_NAME" cp -R "$remote_dir/." "files/models/$model_id/"
adb shell run-as "$PACKAGE_NAME" test -s "files/models/$model_id/.installed"

cd "$ANDROID_DIR"
./gradlew connectedDebugAndroidTest --tests 'com.wisperlow.mobile.stt.SttEngineInstrumentedTest'
