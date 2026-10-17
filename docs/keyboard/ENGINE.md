# Wisperlow typing engine: map and conventions

Goal: typing that feels like Gboard or the iPhone keyboard, plus Hindi and Kannada.
Research notes live in `docs/keyboard/research/`.

## Where things live

- `android/keyboard-core/` is a pure Kotlin/JVM module with no Android dependencies, package
  `com.wisperlow.mobile.keyboard`. It holds the engine and its tests and builds in seconds.
  - `Lexicon.kt` and `TypingSession.kt` are today's engine: a unigram list with heuristic
    autocorrect, and the composing/commit/undo state machine. They stay working until the new
    engine replaces them.
  - `KeyboardLayout.kt` and `KeyboardGeometry.kt` hold the key layouts and pixel placement.
    `BoardUnits` and `letterCenters()` convert placement to key units.
  - `lm/`: `Vocabulary` (word ids, surfaces, keys), the `LanguageModel` interface,
    `UnigramModel` (reference implementation), and `NgramTables` (backoff n-gram arrays).
  - `spatial/`: `SpatialModel`, which gives P(touch | key), plus `KeyCenter` and `KeyChance`.
  - `decoder/`: `WordIndex` (letter tree over word keys), with `SimpleWordIndex` as the
    reference, plus `TypedKey`, `Candidate`, `TypingDecoder` and `NextCharModel`.
- `android/app/` is the Android IME: Compose UI (`keyboard/KeyArea.kt`, `KeyboardPanel.kt`)
  and the service (`keyboard/WisperlowKeyboardService.kt`).

## Conventions

- Coordinates: a `Touch(x, y)` is in key units. x counts standard key widths from the left
  padding, y counts rows from the top of the board. On QWERTY without a number row, q's
  centre is (0.5, 0.5), a's is (1.0, 1.5) and z's is (2.0, 2.5).
- Probabilities are natural logs (`Float`). Unknown words get `UnigramModel.UNKNOWN_LOG_PROB`
  (-20).
- Word keys are lowercase with apostrophes removed (`Vocabulary.keyOf`). Surfaces keep their
  casing and apostrophes.
- A context is the preceding word ids, oldest first. `Vocabulary.SENTENCE_START` ("<s>")
  marks a sentence start, and `Vocabulary.UNKNOWN` (-1) marks an unknown word.
- Style: idiomatic Kotlin, small focused classes, and short doc comments that explain why.
  No commented-out code and no new dependencies (JUnit 4 only, for tests).
- Write tests for behaviour. Keep each test class under about 5 seconds.

## Building and testing (read this before running Gradle)

The machine is short on memory and many agents work in parallel. Run Gradle only through the
lock script, so builds take turns and share one warm daemon:

```
cd android
scripts/gradle-locked.sh -q :keyboard-core:test --tests 'com.wisperlow.mobile.keyboard.decoder.*'
```

- Waiting for the lock can take several minutes, so give the command a long timeout
  (20 minutes or more).
- Build only `:keyboard-core` unless your task says otherwise. Never start more than one
  Gradle command at a time.
- Don't edit `gradle.properties`, and don't run `./gradlew` directly.
