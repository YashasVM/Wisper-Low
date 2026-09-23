#!/usr/bin/env bash

set -euo pipefail

readonly expected_sha256="91e0a2e3fda25fe9ef7e9bcdf692730197a54f1288ee8c3478c17894545d9315"
readonly project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly aar="${project_dir}/app/libs/onnxruntime-genai-android-0.15.2.aar"

fail() {
  echo "Polish runtime verification failed: $*" >&2
  exit 1
}

for command in sha256sum unzip readelf; do
  command -v "${command}" >/dev/null || fail "missing required command: ${command}"
done

[[ -f "${aar}" ]] || fail "runtime archive is missing"

actual_sha256="$(sha256sum "${aar}" | cut -d' ' -f1)"
[[ "${actual_sha256}" == "${expected_sha256}" ]] ||
  fail "unexpected archive SHA-256 ${actual_sha256}"

archive_entries="$(unzip -Z1 "${aar}")"
for expected_entry in \
  jni/arm64-v8a/libonnxruntime-genai.so \
  jni/arm64-v8a/libonnxruntime-genai-jni.so \
  jni/x86_64/libonnxruntime-genai.so \
  jni/x86_64/libonnxruntime-genai-jni.so; do
  grep -Fxq "${expected_entry}" <<<"${archive_entries}" || fail "missing ${expected_entry}"
done

if grep -Eqi '(^|/)(libmat\.so|.*telemetry.*|.*initializer.*)$' <<<"${archive_entries}"; then
  fail "telemetry component found in archive"
fi

manifest="$(unzip -p "${aar}" AndroidManifest.xml)"
if grep -Eqi 'uses-permission|androidx\.startup|provider|telemetry' <<<"${manifest}"; then
  fail "runtime manifest contains a permission, provider, or telemetry initializer"
fi

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/wisperlow-runtime.XXXXXX")"
trap 'find "${temporary_dir}" -depth -delete' EXIT

unzip -p "${aar}" classes.jar >"${temporary_dir}/classes.jar"
class_entries="$(unzip -Z1 "${temporary_dir}/classes.jar")"
if grep -Eqi 'telemetry|initializer' <<<"${class_entries}"; then
  fail "telemetry class found in runtime"
fi

while IFS= read -r native_entry; do
  native_file="${temporary_dir}/$(basename "${native_entry}")"
  unzip -p "${aar}" "${native_entry}" >"${native_file}"

  while IFS= read -r alignment; do
    (( 16#${alignment#0x} >= 16#4000 )) ||
      fail "${native_entry} has LOAD alignment ${alignment}; 0x4000 is required"
  done < <(readelf -lW "${native_file}" | awk '$1 == "LOAD" { print $NF }')

  if readelf -dW "${native_file}" | grep -Eqi 'libmat|appcenter|telemetry'; then
    fail "${native_entry} links a telemetry library"
  fi
done < <(grep -E '^jni/(arm64-v8a|x86_64)/.*\.so$' <<<"${archive_entries}")

echo "Verified no-telemetry ONNX Runtime GenAI archive ${actual_sha256}"
