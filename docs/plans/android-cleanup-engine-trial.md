# Android cleanup engine trial

Recorded 2026-09-18 from Packet D, starting at corpus commit `2b746cc`.

This trial compares Qwen2.5 0.5B Instruct and Qwen3 0.6B against the same 48-case
corpus in `android/app/src/test/java/com/wisperlow/mobile/text/DictationCorpus.kt`.
The runner is [cleanup_model_trial.py](../../android/tools/cleanup_model_trial.py).
It parses that Kotlin source directly and fails if the expected case count changes,
so a model run cannot silently use a partial or duplicated corpus.

The runner requires a locally supplied adapter. The adapter receives the model ID,
model directory, prompt path, and output path; it writes only the edited transcript
to the output path. This keeps model execution separate from the Android app and
does not download weights or contact a service. A supplied adapter can run one case
per process for cold measurements or use its own process/session protocol for warm
measurements. The JSON report records per-case wall time, timeout, child RSS high
water mark, output, and automated checks. Host measurements are explicitly labeled
and must not be used as Android device results.

## Pinned candidates

The exact revisions and artifact hashes are machine-readable in
[`cleanup_model_candidates.json`](../../android/tools/cleanup_model_candidates.json).
The selected trial artifacts are the same `q4f16` ONNX representation family so
the comparison does not mix model precision as an untracked variable.

| Candidate | Qwen source revision and license | ONNX conversion revision | Trial model artifact | Tokenizer artifact |
| --- | --- | --- | --- | --- |
| Qwen2.5 0.5B Instruct | `Qwen/Qwen2.5-0.5B-Instruct@7ae557604adf67be50417f59c2c2f167def9a775`, Apache-2.0 | `onnx-community/Qwen2.5-0.5B-Instruct@cc5cc01a65cc3ff17bdb73a7de33d879f62599b` | `onnx/model_q4f16.onnx`, 483,003,582 bytes, SHA-256 `b11c1dd99efd57e6c6e5bc4443a019931a5fbd5dd500d48644d8225f5ce0b2cb` | `tokenizer.json`, 7,031,673 bytes, SHA-256 `d24314ef7f0afd1b678c2e24c767e19f24f86b0e` |
| Qwen3 0.6B | `Qwen/Qwen3-0.6B@c1899de289a04d12100db370d81485cdf75e47ca`, Apache-2.0 | `onnx-community/Qwen3-0.6B-ONNX@da1453100cf3ff33ef56d17983fc7a8648706db6` | `onnx/model_q4f16.onnx`, 569,789,750 bytes, SHA-256 `9e33a5911974174761d0dfdcc0bec975d9c45af0eae5e9eb647b8ba9442a8f91` | `tokenizer.json`, 9,117,040 bytes, SHA-256 `e7a95fce95bf5b0946d0ddb3f9d7caa030b7e850bbe92b0edb26bcf563e9f3d5` |

The source model cards identify both models as Apache-2.0: [Qwen2.5
0.5B Instruct](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct/tree/7ae557604adf67be50417f59c2c2f167def9a775)
and [Qwen3 0.6B](https://huggingface.co/Qwen/Qwen3-0.6B/tree/c1899de289a04d12100db370d81485cdf75e47ca).
The conversion repositories point to those base models: [Qwen2.5 ONNX](https://huggingface.co/onnx-community/Qwen2.5-0.5B-Instruct/tree/cc5cc01a65cc3ff17bdb73a7de33d879f62599b)
and [Qwen3 ONNX](https://huggingface.co/onnx-community/Qwen3-0.6B-ONNX/tree/da1453100cf3ff33ef56d17983fc7a8648706db6).
Keep the source license and attribution with any redistributed converted files;
the conversion repositories do not declare a separate SPDX license in their cards.

## Runtime decision

Microsoft's official [Qwen Android example](https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/Qwen_QA/Android)
describes both candidates running fully on-device with the standard
`onnxruntime-android` API, and documents no network calls or telemetry. Its model
instructions use preconverted ONNX plus the Hugging Face tokenizer, which matches
the pinned artifacts above. This is the preferred trial route because it does not
add a telemetry-enabled dependency to the app. It still needs an adapter and a
real Android build/device run before a production decision.

ORT GenAI 0.15.2 is a feasible alternate runtime, but its official Android AAR is
not acceptable as shipped for this app's offline requirement. The pinned
`onnxruntime-genai-android-0.15.2.aar` is 21,570,052 bytes with SHA-256
`f507ddbf0f635554ab12fcbf7dcfb115981eb664b925d90f28ed598dd8eb2ee7` from the
[v0.15.2 release](https://github.com/microsoft/onnxruntime-genai/releases/tag/v0.15.2).
Its [Android telemetry manifest](https://raw.githubusercontent.com/microsoft/onnxruntime-genai/v0.15.2/src/java/src/main/android/AndroidManifest.xml)
adds `INTERNET` and `ACCESS_NETWORK_STATE` and registers a telemetry initializer.
The project's [privacy documentation](https://github.com/microsoft/onnxruntime-genai/blob/main/docs/Privacy.md)
says official packages enable telemetry by default and send trace events over HTTPS.

A no-telemetry source build is technically available. The tagged
[v0.15.2 build driver](https://raw.githubusercontent.com/microsoft/onnxruntime-genai/v0.15.2/build.py)
accepts `--no_telemetry` and maps it to `-DENABLE_TELEMETRY=OFF`; CMake also
supports that definition directly. The candidate command is:

```bash
python build.py \
  --android \
  --android_abi arm64-v8a \
  --build_java \
  --no_telemetry \
  --skip_tests
```

That build still requires source inspection of the resulting AAR manifest,
`readelf` checks for Android 16 KB page-size alignment, a clean network-denied
device run, and an Android integration test before it can be considered. The
current packet does not ship or select it.

## Commands

Validate the pinned metadata and that all 48 cases are parsed:

```bash
python3 android/tools/cleanup_model_trial.py --validate
```

Run one candidate after placing the exact model and tokenizer files in a local
model directory and providing an adapter:

```bash
python3 android/tools/cleanup_model_trial.py \
  --model-id qwen2.5-0.5b-instruct \
  --model-dir /path/to/qwen25 \
  --adapter '/path/to/adapter' \
  --verify-hashes \
  --output artifacts/qwen25-host.json
```

Repeat the same command with `qwen3-0.6b` and compare the two JSON reports. The
adapter is intentionally outside this repository's runtime files. It must use a
fixed prompt, deterministic decoding, a bounded output length, and a process or
session strategy that makes cold versus warm behavior explicit.

## Evidence from this environment

The host has no installed `onnxruntime`, `onnxruntime-genai`, `transformers`,
PyTorch, or model artifact directory, so no real model output or host latency was
collected. `--validate` completed with 48 cases and both pinned model IDs. A local
adapter smoke test using sparse placeholder files exercised the report protocol
with 48 rows; its output is test-harness evidence only and is not model quality.
The Android SDK is also not configured in this worktree, so device execution and
the no-telemetry AAR build remain open gates.

The harness reports automated protected-span and exact-target matches, but those
are screening signals. They do not replace human meaning-preservation review,
device latency, memory, thermal, cancellation, or sustained-run measurements.
