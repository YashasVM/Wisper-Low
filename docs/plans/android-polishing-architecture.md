# Android polishing architecture

## Usage

`DictationService` owns recording generations, the captured editable target, and insertion. After recognition it takes one settings snapshot and calls `polisher.polish(PolishRequest(...))`.

The call returns `Unchanged`, `Polished`, `Unavailable`, or `Failed`. Coroutine cancellation escapes the call. The service checks its generation after recognition and after polishing before it updates the overlay or inserts text.

## Module ownership

| Module | Responsibility |
| --- | --- |
| `text/TranscriptPolisher.kt` | Request and result contract, prompt isolation, input bounds, and output validation. |
| `polish/` | Local model catalog, verified download, tokenizer, ONNX session, generation limits, and cleanup. |
| `service/DictationService.kt` | Session validity, quick insert policy, editable review, clipboard fallback, and history writes. |
| `settings/SettingsRepository.kt` | Mode, insertion preference, setup progress, and migration defaults. |
| `history/TranscriptRepository.kt` | Versioned original and final transcript storage. |

UI code never receives a model handle or prompt. The runtime never inserts text.

## Runtime decision

The selected trial uses Qwen2.5 0.5B Instruct with ONNX Runtime. Microsoft maintains an Android example for Qwen2.5 0.5B and Qwen3 0.6B with offline token generation and past-key-value caching. Qwen2.5 uses the Apache 2.0 license, has a 474 MB Q4 FP16 ONNX build, and fits the app's existing ONNX Runtime deployment. The app pins the model revision, file sizes, hashes, and runtime version before enabling download.

MLC LLM was the other feasible runtime. Its Android deployment requires model-specific compilation, a TVM runtime build, and a physical mobile GPU. That adds a second native toolchain and makes broad device support harder to verify in this repository. MediaPipe LLM Inference targets high-end Android devices and uses larger Gemma-family task bundles. Both remain fallback trials if the selected model misses the device quality or latency gates.

No quality or speed claim follows from the integration choice. The committed corpus measures meaning preservation and grammatical edits. Physical-device runs must record cold and warm latency, peak memory, cancellation, battery, and thermal behavior before Polished mode becomes a recommended default.

## Failure rules

- Original mode does not load or download a cleanup model.
- Existing installations remain in Original and Review first modes until the user chooses otherwise.
- Missing or failed cleanup returns the original transcript for review.
- Empty, truncated, unexpectedly expanded, number-changing, negation-changing, or protected-vocabulary-changing output requires review.
- Quick insert accepts only a validated polished result while the original captured target remains valid.
- A retry reuses the recognized transcript and never records or inserts again.
- The service serializes speech and cleanup inference and releases both after an idle timeout chosen from device measurements.
