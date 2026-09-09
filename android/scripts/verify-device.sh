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
case "$model_id" in
    sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) catalog_id="parakeet-tdt-0.6b-v3-int8" ;;
    sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8) catalog_id="parakeet-tdt-0.6b-v2-int8" ;;
    *)
        echo "unsupported model directory: $model_id" >&2
        echo "expected an official Parakeet v2 or v3 directory name" >&2
        exit 64
        ;;
esac
for required in tokens.txt encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx; do
    [[ -s "$model_dir/$required" ]] || {
        echo "model is missing a required file: $required" >&2
        exit 66
    }
done
if find "$model_dir" -type l -print -quit | grep -q .; then
    echo "refusing a model containing symbolic links" >&2
    exit 66
fi

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
remote_archive="${remote_dir}.tar"
local_archive="$(mktemp "${TMPDIR:-/tmp}/wisperlow-stt.XXXXXX.tar")"
cleanup() {
    rm -f -- "$local_archive"
    adb shell rm -rf "$remote_dir" "$remote_archive" >/dev/null 2>&1 || true
}
trap cleanup EXIT

adb install -r "$APK_PATH"
adb shell rm -rf "$remote_dir"
adb shell mkdir -p "$remote_dir"
tar -C "$model_dir" -cf "$local_archive" .
adb push "$local_archive" "$remote_archive"
adb shell run-as "$PACKAGE_NAME" mkdir -p "files/models/$model_id"
# Stream the archive through the shell into run-as. This avoids relying on
# cross-UID reads of /data/local/tmp, which are blocked on some Android builds.
adb shell cat "$remote_archive" | adb shell run-as "$PACKAGE_NAME" tar -xf - -C "files/models/$model_id"
printf '%s\n' "$catalog_id" | adb shell run-as "$PACKAGE_NAME" sh -c "cat > files/models/$model_id/.installed"
adb shell run-as "$PACKAGE_NAME" test "\$(cat files/models/$model_id/.installed)" = "$catalog_id"

cd "$ANDROID_DIR"
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.wisperlow.mobile.stt.SttEngineInstrumentedTest
