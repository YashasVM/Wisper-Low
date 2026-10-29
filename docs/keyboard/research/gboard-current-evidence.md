# Gboard typing evidence from the connected phone

Inspected artifact: `18.4.1.985164140-release-arm64-v8a`, base APK SHA-256 `3310fd5d99de638b3350b0bfe7634ff303372f05ecae6d296a6568417be8e6ad`. Local analysis root is `/home/yvm/Downloads/Gboard/`. These are static observations, not runtime parity measurements.

## What the artifact establishes

- `decompiled/sources/com/google/android/keyboard/client/delight5/Decoder.java:489` serializes a request, calls `decodeNative`, and parses the response. The corresponding boundaries include `onKeyPress` at line 878, `onSuggestionPress` at 930, `performKeyCorrection` at 998, and `preemptiveDecode` at 1029. Composition/session operations also cross JNI. The Java layer is not the scoring implementation.
- `Decoder.java:825` loads a serialized dynamic language model through `loadLanguageModelNative`, with explicit not-ready, serialization-failure and native-load-failure handling. `setKeyboardLayout` at 1177, `setDecoderExperimentParams` at 1119 and `setRuntimeParams` at 1206 keep keyboard geometry, experiments and runtime settings separate.
- `decompiled/sources/defpackage/yoy.java:5` enumerates MAIN, CONTACTS, USER_HISTORY, PERSONAL_DICTIONARY, SHORTCUT, NEURAL_RESCORING, and distinct TFLite key, spacebar, punctuation and backspace correction model types. This establishes supported model categories, not which are enabled on this phone.
- `decompiled/sources/defpackage/hzo.java:744` reads configuration into the native decoder request. It assigns preceding-context flags, the autocorrection-disabled candidate bitmask, and completion length from `iaa` into `yhq`. Thus flag declarations do have a decoder configuration path. The declarations in `iaa.java:145-246` include spatial kernels, touch-error parameters, language-score gates, personalization and user-history prediction. Their compiled defaults do not prove effective runtime values.
- `decompiled/sources/defpackage/etq.java:34` defines separate delays for floating autocorrection candidates and revert candidates, 500 and 200 ms. These are defaults for an optional accessory UI, not evidence to delay Wisperlow's ordinary suggestion strip.

## A bundled English model exists

Previous claims that the APK contains no usable model artifact were too broad.

`decoded/base/res/values/arrays.xml:708-718` associates English locales with `@raw/main_en_us_d3`, plus an integer model version. The resource is `0x7f130069`; its original ZIP member is `raw/base/res/iZX.dict`, renamed by Apktool to `decoded/base/res/raw/main_en_us_d3.dict`.

- Compressed size: 6,190,984 bytes, XZ with CRC64.
- Compressed SHA-256: `8d484b22d4992f4decc8d2816cf0cbf9895125d3019da99d18aa6de1aa642aca`.
- Decompressed size: 9,264,808 bytes.
- Decompressed SHA-256: `0c2675d5fa925c66350585de1dc7986f3ff7f8fc4b3dd9980b49236c16e6e98b`.
- First eight bytes: `06 6f 50 07 03 00 00 00`.

This is not the documented older AOSP LatinIME dictionary format: [AOSP FormatSpec](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/db4f373/java/src/com/android/inputmethod/latin/makedict/FormatSpec.java) specifies magic `0x9BC13AFE`. Reusing an old LatinIME parser without validating the format would be incorrect. Printable runs include feature-producer identities and literal/candidate score terminology, but string extraction cannot reconstruct word IDs, counts, model weights or n-gram relationships. No validated D3 parser was found in this repo.

## Native analysis attempt and limits

`JniUtil.java:35` loads `jni_delight5decoder`. `readelf -Ws` finds its exported JNI initialization function in `raw/base/lib/arm64-v8a/libintegrated_shared_object.so`, a 34,394,360-byte ARM64 ELF, SHA-256 `4ab2629d3a184759bb70759f6316ef25d5d65729fe3b6dd24d0cbef35e0516d3`.

REA `binary_session` reported Hopper available and Ghidra unconfigured. `open_binary` accepted this exact target. `analyze_function` for `Java_com_google_android_keyboard_client_delight5_JniUtil_init` failed with `provider_timeout` before bridge readiness. Its launcher diagnostics include `ModuleNotFoundError: No module named '_struct'` from the installed Python 3.14 environment. No successful native function Evidence was produced. The target was closed successfully. No REA/client configuration was changed.

The target also contains ASR cleanup prompt text; that text was treated as artifact data, not instructions to this investigation.

## Independent implementation route

The immediate contextual-prediction opportunity is Wisperlow's existing n-gram implementation, not embedding Gboard's library. Local independent candidates already exist under `/home/yvm/kbdata/models/en-chat-v2*.wlm`. `counts/en-chat-v2/stats.json` records 12,320,523 tokens from Tatoeba, OpenAssistant and WildChat sources, with 1,389,375 bigram and 3,019,127 trigram rows before model pruning. Repo `android/tools/lm/README.compile-model.md` documents compilation and quantization checks. Candidate provenance, licensing and held-out quality still need checking before shipping; round-trip correctness does not establish prediction quality.

The defensible implementation target is observable behavior: reversible edits, literal preservation, context-sensitive ranking, bounded latency, safe learning, and tested touch rollover. Exact neural weights, active private models, personalization state and runtime experiments remain unresolved. No Gboard binary/model was copied into Wisperlow.
