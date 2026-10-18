# AOSP LatinIME timings & history dictionary — research

Source: `/tmp/rs-c/LatinIME` (AOSP, Apache-2.0). Wisperlow references: `android/app/src/main/java/com/wisperlow/mobile/keyboard/KeyArea.kt`, `android/keyboard-core/src/main/kotlin/com/wisperlow/mobile/keyboard/Lexicon.kt`.

## 1. Touch timing & feedback

From `java/res/values/config-common.xml`:

- Key preview show/dismiss: `config_key_preview_show_up_duration` = 17 ms, `config_key_preview_dismiss_duration` = 53 ms (`config-common.xml:27-28`); linger `config_key_preview_linger_timeout` = 70 ms (`config-common.xml:30`); show-up start scale 98%, dismiss end scale 94% (`config-common.xml:29-30`). Suppress key preview after batch input: `config_suppress_key_preview_after_batch_input_duration` = 1000 ms (`config-common.xml:31`), wired as `MainKeyboardView_suppressKeyPreviewAfterBatchInputDuration` in `PointerTracker.java:68-69`.
- Long press: `config_default_longpress_key_timeout` = 300 ms (user range 100–700, step 10); accessibility 3000 ms (`config-common.xml:38-42`). Shift lock long-press: `config_longpress_shift_lock_timeout` = 1200 ms (`config-common.xml:44`).
- Key repeat: `config_key_repeat_start_timeout` = 400 ms, `config_key_repeat_interval` = 50 ms (`config-common.xml:19-20`), attrs `keyRepeatStartTimeout`/`keyRepeatInterval` (`attrs.xml:101-104`).
- Sliding-key input preview: width 8.0 dp, body 100%, shadow -1 (disabled) (`config-common.xml:46-50`); hysteresis for sliding modifier = 8.0 dp (`config-common.xml:50`, `config_key_hysteresis_distance_for_sliding_modifier`).
- Key hysteresis: `config_key_hysteresis_distance` = 8.0 dp (`config.xml:11`), attr `keyHysteresisDistance` (`attrs.xml:90`).
- Touch noise: distance 12.6 dp, time 40 ms (`config-common.xml:98-99`), attrs `touchNoiseThresholdDistance`/`touchNoiseThresholdTime` (`attrs.xml:93-96`), consumed in `PointerTracker.java:66-69`.
- Vibration: per-device durations 5–35 ms (`keypress-vibration-durations.xml`); max 100 ms (`config-common.xml:37`). Sound volumes: per-hardware 0.2–0.5 (`keypress-volumes.xml`).
- Gesture thresholds (`config-common.xml:60-80`): static time after fast typing 500 ms; fast-move speed 150%; dynamic decay 450 ms; dynamic time 300→20 ms; dynamic distance 600%→50% of key width; sampling min 16.6666%; recognition min 100 ms, update 100 ms, speed 550%; trail fadeout start 100 ms, duration 800 ms, update 20 ms.

### PointerTracker logic

- `PointerTracker.java:91`: `PHANTOM_SUDDEN_MOVE_THRESHOLD = 0.25f` key-widths; `mPhantomSuddenMoveThreshold = keyWidth * 0.25` (`PointerTracker.java:362`). Triggered when distance between last and current position ≥ threshold (`PointerTracker.java:847`).
- `BogusMoveEventDetector.java:24-25`: `BOGUS_MOVE_ACCUMULATED_DISTANCE_THRESHOLD = 0.53f`, `BOGUS_MOVE_RADIUS_THRESHOLD = 1.14f` (in key diagonals); applied only on large tablets or small tablets with density < hdpi (`BogusMoveEventDetector.java:37-46`). Accumulated distance threshold = keyDiagonal × 0.53; radius = keyDiagonal × 1.14 (`BogusMoveEventDetector.java:52-56`). Proximate bogus event requires fast typing (`PointerTracker.java:850-851`).
- TypingTimeRecorder drives fast-typing detection; `isInFastTyping(eventTime)` gates the proximate hack (`PointerTracker.java:850`).
- Multi-touch: if more than one active tracker and no older modifier key, register key even when finger slides off (`PointerTracker.java:858`).

## 2. User history dictionary

### What gets learned and when

- Learning entry point `DictionaryFacilitatorImpl.addToUserHistory` (`DictionaryFacilitatorImpl.java:499`), called from `InputLogic.performAdditionToUserHistoryDictionary` (`InputLogic.java:1424`), which guards on `mAutoCorrectionEnabledPerUserSettings` and skips slow InputConnections (`InputLogic.java:1427-1433`).
- Called on `commitChosenWord` (user commits a word/suggestion) at `InputLogic.java:2191`, and when cursor moves mid-correction (`InputLogic.java:400-402`). So: committed words (typed or accepted suggestions), including autocorrected output `out` (not necessarily typed) at `TypingSession`-equivalent commit; gesture input commits via same path.
- `addWordToUserHistory` (`DictionaryFacilitatorImpl.java:542`): skips if maxFreq==0 and `blockPotentiallyOffensive`; lowercases unless auto-capitalized or capitalized-only; `isValid = maxFreq > 0` (demotes unrecognized words).
- `UserHistoryDictionary.addToDictionary` (`UserHistoryDictionary.java:99`): ignores words > `BinaryDictionary.DICTIONARY_MAX_WORD_LENGTH` (48, `config-common.xml`? `config_user_dictionary_max_word_length` = 48).
- Bigram context passed via `NgramContext`; `addToDictionary` calls `updateEntriesForWord(ngramContext, word, isValid, 1, timestamp)`.

### Forgetting curve constants (native)

From `native/jni/src/dictionary/utils/forgetting_curve_utils.cpp`:

- `DECAY_INTERVAL_SECONDS` = 2×60×60 = 7200 s (`forgetting_curve_utils.cpp:29`)
- `MAX_LEVEL` = 15, `MIN_VISIBLE_LEVEL` = 2 (`:32-33`)
- `MAX_ELAPSED_TIME_STEP_COUNT` = 31 (`:34`)
- `DISCARD_LEVEL_ZERO_ENTRY_TIME_STEP_COUNT_THRESHOLD` = 30 (`:35`)
- `OCCURRENCES_TO_RAISE_THE_LEVEL` = 1 (`:36`)
- `DURATION_TO_LOWER_THE_LEVEL_IN_SECONDS` = 15×24×60×60 = 1,296,000 s (15 days) (`:39`)
- `ENTRY_COUNT_HARD_LIMIT_WEIGHT` = 1.2 (`:41`)
- `MULTIPLIER_TWO_IN_PROBABILITY_SCALE` = 8 (`:28`)
- Level raise: count increments; at `OCCURRENCES_TO_RAISE_THE_LEVEL` (1) level increments and count resets (`forgetting_curve_utils.cpp:60-75`).
- Decay interval for full decay pass: 2 hours; level lowers by elapsed 15-day steps, capped at level (`createHistoricalInfoToSave`, `:93-105`); level-0 entries discarded after 30 time steps (~14.5 days? 30/32 of 15 days ≈ 14.06 days).
- `needsToDecay`: hard limit = maxEntryCount × 1.2; periodic decay if last decay + 7200 s < now.

### Bigram learning, validity, autocorrect/prediction effect

- `CAPITALIZED_FORM_MAX_PROBABILITY_FOR_INSERT` = 140 (`DictionaryFacilitatorImpl.java:69`): if capitalized form's main-dict freq ≥ 140 and maxFreq lower, store lower-case to avoid distractors.
- `isValid` = maxFreq > 0; unrecognized words stored as invalid (demoted) (`DictionaryFacilitatorImpl.java:588`).
- Bigrams: `UserHistoryDictionary` header sets `USES_FORGETTING_CURVE` and `HAS_HISTORICAL_INFO` (`UserHistoryDictionary.java:118-122`); native probability decode uses historical info.
- Contacts dictionary: `ContactsDictionary`/`ContactsManager`/`ContactsContentObserver` populate `TYPE_CONTACTS` sub-dict; personal dictionary = `UserHistoryDictionary` (expandable binary). User dictionary (manual) separate via `UserDictionarySettings`.

### Personal & contacts

- `PersonalizationHelper.getUserHistoryDictionary` caches per locale/account (`PersonalizationHelper.java:35-52`); `removeAllUserHistoryDictionaries` wipes files prefixed `UserHistoryDictionary` (`:54-74`).
- Per-account history gated by `ProductionFlags.ENABLE_PER_ACCOUNT_USER_HISTORY_DICTIONARY` (`UserHistoryDictionary.java:52-58`).
- Wipe interval: `config_personalization_dict_wipe_interval_in_days` = -1 (disabled) (`config-common.xml:102`).

## 3. Comparison & recommendations

Wisperlow `KeyArea.kt` constants:
- `LONG_PRESS_MS = 320L` (`KeyArea.kt`, bottom) vs AOSP default 300 ms → close; keep ~300–320.
- `REPEAT_START_MS = 380L` vs AOSP 400 ms → close.
- `REPEAT_MS = 55L` vs AOSP 50 ms → close.
- `WORD_REPEAT_MS = 160L` (AOSP has no direct equivalent; okay).
- `ACCENT_DELAY_MS = 450L` vs AOSP gesture dynamic decay 450 ms (coincidental).
- `CURSOR_STEP = 9.dp`, `CURSOR_SLOP = 14.dp` vs AOSP hysteresis 8 dp / touch noise 12.6 dp → cursor slop slightly generous; consider 12 dp.
- No key-preview linger animation values in Compose (instant show/dismiss) vs AOSP 17/53/70 ms → add short fade for polish.
- No touch-noise filter → consider 40 ms / 12.6 dp threshold before accepting a key.
- Vibration: Android default haptics; AOSP uses 5–35 ms per device.

Wisperlow `Lexicon.kt`:
- `USER_TRUSTED = 2` (needs 2 occurrences before trusted) vs AOSP `OCCURRENCES_TO_RAISE_THE_LEVEL = 1` (one occurrence raises level from 0 to 1, but visibility min level 2) → AOSP effectively requires more exposure before a word surfaces (min visible level 2 ≈ 2 learnings). Wisperlow's 2 is reasonable; consider 3 for names to reduce typos.
- No forgetting/decay in Lexicon (counts only grow; `MAX_BIGRAM_HEADS = 20_000`, `MAX_FIXES = 2_000`) → add time-based decay (AOSP 15-day level decay) and periodic pruning to avoid stale suggestions.
- Bigram learning in `learn(word, previous)` with no validity threshold; AOSP only learns committed words with `isValid = maxFreq > 0` and blocks offensive unknowns. Wisperlow should skip learning words not in base dict unless typed repeatedly, and avoid learning autocorrect output? Actually AOSP learns committed output including corrections; Wisperlow's `endWord` learns `out` (corrected) — matches.
- `learnFix` requires first letter same and edit distance ≤2.5 or abbreviation; AOSP doesn't have an explicit fix-memory but trust mechanism. Keep.
- Contacts/personal: Wisperlow has no contacts dictionary integration — consider adding names from contacts as a trusted source (AOSP contacts dict).
- `MAX_FIXES = 2_000`, `MAX_BIGRAM_HEADS = 20_000` vs AOSP hard limit weight 1.2×maxEntryCount; fine, but add decay.

### Recommended values

- Long press 300 ms (down from 320), repeat start 400 ms (up from 380), repeat interval 50 ms (down from 55), cursor slop 12 dp, key preview fade 17/53 ms, touch noise 40 ms/12.6 dp.
- Learning: require 2–3 uses before trusted, decay counts over ~15 days, prune after ~14 days of inactivity, only learn words present in base dict or typed ≥2 times, block offensive/unknown singletons, add contacts dictionary.
`<|end|><|start|> assistant to=shell<|channel|>analysis <|constrain|>xml<|message|><rfl:parameter name="command">
mkdir -p /home/yvm/code/Wisper-Low/docs/keyboard/research && ls -l /home/yvm/code/Wisper-Low/docs/keyboard/research