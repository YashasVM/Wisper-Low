# English typing-engine verification

The shipped configuration keeps the existing English lexical model for word decoding and uses the verified open-corpus `dict/en-next-word.wlm` for next-word prediction only. Directly replacing the lexical model with that corpus model regressed spelling recovery in the earlier held-out investigation, so it was not adopted for autocorrect. This is an independently built model, not Gboard's native model.

Both included reports use the same current code, literal landed taps, original touch coordinates, 40 sentences, 460 words, 416 misspelling pairs and one deterministic seed. The hybrid report enables the next-word corpus; the literal-unigram report disables it. Reports from before the simulator switched from greedy per-key replacement to literal taps are not valid before/after comparisons against these reports.

The simulator now follows the app's tap path: an off-centre tap produces `thw` composing, then the touch-aware word decoder can commit `the` on space. This preserves the literal sequence for undo.

Verification completed:

- Entire keyboard-core JVM suite and app JVM suite passed. A timing-budget test failed during one compiler-loaded run, then the full suite passed on rerun. No performance threshold was weakened.
- Added tests cover separate prediction/decoder vocabularies, sentence-initial capital context, retained personal next-word preference, slang/trusted-literal strip placement, actual packaged corpus loading and preservation of `teh` correction.
- Both bounded simulator runs completed successfully. This subset does not establish Gboard parity or phone latency.

Reproduce from `android/`:

```sh
./scripts/gradle-locked.sh :keyboard-core:test :app:testDebugUnitTest -Pkotlin.incremental=false
./scripts/gradle-locked.sh :keyboard-core:typingEval -PevalArgs="--engine=smart --quick --seeds=1 --limit=40 --out=/tmp/literal-unigram40.json"
./scripts/gradle-locked.sh :keyboard-core:typingEval -PevalArgs="--engine=smart --quick --seeds=1 --limit=40 --next-model=../app/src/main/assets/dict/en-next-word.wlm --out=/tmp/hybrid40.json"
```

Model identity, source attribution, full Apache/ODC license text and training/input provenance ship alongside the asset. Its SHA256 is `5ca5675a0a836dfe2a096cc7862ea3e45a96c6dff128e17f551b4cef04c8cc0a`. The model is not used to overwrite private Gboard data and does not require access to it.

## Sparse typo context discovered in the emulator

Live testing found that undoing `teh` → `the` protected the literal correctly but then offered `densities`, `algo`, and `chords`. The training counts confirm 44 occurrences of `teh` and sparse continuations `densities` 4, `algo` 3, `chords` 2. Trusting a spelling is not evidence that these corpus continuations are useful.

The final prediction guard gives learned personal continuations first priority, then requires a preceding word recognized in both lexical and corpus vocabularies before offering model predictions. A trusted OOV typo no longer activates noisy corpus context or unrelated unknown-context unigram fallback. The actual bundled corpus test verifies empty predictions after trusting `teh`, and exactly `book` after explicitly learning that continuation. A separate test checks trusted contexts absent from the corpus.

The two replay reports above were captured before this final OOV-context guard. Correction scoring is unchanged by that guard. Final validation after the guard passed all 294 core JVM tests and targeted packaged-loader JVM tests. The replay numbers should not be presented as measurements of the last guard revision.

## Final packaged verification

- Release and debug builds succeeded after the final OOV prediction guard.
- Debug lint passed with zero errors and 25 warnings.
- The keyboard editor instrumented test passed on Android 15 emulator against a real EditText/InputConnection: composition, correction, punctuation, undo, literal recommit and suggestion replacement. The first ActivityScenario-based attempt stalled during animated onboarding and was stopped; the final test uses the real editor directly.
- Removed an obsolete instrumentation test referencing DictationService, which no longer exists in this app.
- Installed the minified x86_64 release on the emulator. Live keyboard taps verified teh -> the on space, restoration on backspace, literal retention on the next space, and no sparse corpus suggestions after the rejected typo.
- ARM64 APK includes the byte-identical prediction model and attribution notice. APK signature verifies.
- Physical phone was not connected during these checks; touch latency and Gboard equivalence on the user's phone remain unverified.

Delivered artifact: `Wisperlow-typing-2026-10-29-arm64.apk` (46553180 bytes), SHA256 `325f5dbb24e2c904193d75fa86af8f82bc3be87871469e3751453eb7bfc02da4`.
