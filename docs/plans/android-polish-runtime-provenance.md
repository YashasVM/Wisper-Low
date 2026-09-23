# Android polish runtime provenance

Wisperlow vendors a no-telemetry build of ONNX Runtime GenAI for Android. The
official 0.15.2 archive registers a telemetry initializer and adds network
permissions, so it is not suitable for the app's offline processing boundary.

The vendored archive was built from the upstream `v0.15.2` tag at commit
`ed5f4e87147731e5b07810f9f5c90103b3603cdf` with ONNX Runtime Android 1.26.0,
Android NDK 29.0.14206865, minimum SDK 33, and these GenAI build options:

```text
--build_java --android --no_telemetry --skip_wheel --skip_tests
--cmake_generator=Ninja
```

The package contains only `arm64-v8a` and `x86_64`, matching the app's ABI
filters. Every native `PT_LOAD` segment is aligned to `0x4000` for Android
16 KB page compatibility. Its manifest has no permissions, providers, or
startup initializers, and the archive contains no Microsoft App Center
telemetry library. `android/scripts/verify-polish-runtime.sh` enforces those
properties and the package SHA-256 in CI.

ONNX Runtime GenAI is distributed under the MIT License. The cleanup model is
downloaded only after an explicit user action and is not stored in this
repository. Its pinned model card and license are recorded alongside its
artifact hashes in `android/tools/cleanup_model_candidates.json`.
