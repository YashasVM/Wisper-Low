# Gboard APK inspection: 14.2.09 preload (x86_64)

Scope: read-only inspection of one user-supplied artifact. No code or assets copied into the repository. Companion work directory: `/tmp/wisper-gboard-inspection/`.

## Artifact identity

- File: `/tmp/wisper-gboard-inspection/gboard-14.2.09-preload-x86_64.apk`
- Source: `adb pm path /product/app/LatinIMEGooglePrebuilt/LatinIMEGooglePrebuilt.apk` on `emulator-5558`
- Package: `com.google.android.inputmethod.latin`
- versionName: `14.2.09.629370537-preload-x86_64`
- versionCode: `143544272`
- minSdk: `23`, targetSdk: `34`, compileSdk: `34`
- SHA256: `74dbae9c0057304784c54294bbebc9d4475ced94fdbee4f6d87079f0d98ba511`
- Size: `85,147,445` bytes
- This is a **2024 preload build**, not a 2026 current Gboard. Treat all findings as version-bound.

## Method and evidence

- Skill followed: `/home/yvm/.agents/skills/reverse-engineer-anything/SKILL.md` (REA evidence workflow).
- REA MCP status: **not available** in this session. `tools.opencode.list_mcp_resources()` returned no resources/templates; no `inspect_android_package`, `open_binary`, or `decode_android_resources` tool was advertised. Per skill, no REA setup/reinstall was performed.
- Fallback used: Android SDK `aapt` (`/home/yvm/.local/share/android-sdk/build-tools/34.0.0/aapt`) plus Python `zipfile` inventory. Evidence files retained under `/tmp/wisper-gboard-inspection/`:
  - `gboard_resources.txt` (285,508 lines; `aapt dump resources`)
  - `gboard_strings.txt` (174,744 lines; `aapt dump strings`)
  - `gboard_manifest.txt` (757 lines; `aapt dump xmltree AndroidManifest.xml`)
- Evidence references below are `gboard_strings.txt` line numbers and zip entry paths. Resource IDs are obfuscated by R8, so many values are string-table/config names rather than human-readable resource names.

## Verified facts

### A. Feature/config inventory present in the APK

All of the following are present as string/config identifiers in `gboard_strings.txt` (line numbers in parentheses):

- Adaptive/typing controls: `pref_key_auto_correction` (14644), `pref_key_latin_auto_correction` (14664), `pref_key_pk_auto_correction` (14679), `pref_key_enable_llm_pc` (14657), `enable_auto_capitalization` (13228), `enable_auto_capitalization_on_pk=false` (13229), `enable_auto_language_switching` (13230), `enable_auto_paste_chips` (13231), `enable_auto_show_translate` (13232), `enable_at_cursor_suggestions_for_pk` (13227).
- Autocorrect UX: UI strings `Autocorrect suggestions shown at the cursor when toolbar is minimized` (9601), `Return to original text when backspacing after an autocorrection` (11433), `Undo auto-correct on backspace` (12050), `Show word suggestions near the cursor` (11653), `Show floating suggestions while editing recent autocorrected word` (15016).
- Autospace: `enable_autospace_after_punctuation` (13236), `enable_auto_space_smart_punctuation` (13233), `enable_auto_space_zh_hk` (13234), `enable_auto_space_zh_tw` (13235), `Autospace after punctuation` (9609), `auto_space` (12505), `enable_autospace_after_punctuation` (13236), `[Beta] Insert space after punctuation (only available in US English)` (12262).
- Key popup: `enable_popup_on_keypress` (13283), `Popup on keypress` (11318), `pref_key_preview_popup_dismiss_delay` (14681), `ro.com.google.ime.key_popup` (14825).
- Space/cursor gestures: `Gesture cursor control` (10314), `Gesture delete` (10315), `Move cursor by sliding across space bar` (11025), `Move cursor to the end` (11026), `Move cursor to the head` (11027), `Swipe on space bar to accept` (11809), `Double tap on spacebar inserts a period followed by a space` (10032), `Double-space period` (10033), `enable_double_space_period` (13246), `enable_gesture_input` (13262), `enable_incremental_gesture_input` (13265), `enable_incremental_gesture_input_ko` (13266), `enable_incremental_gesture_input_zh_tw` (13267), `enable_gesture_auto_commit` (13260), `enable_scrub_delete` (13291), `enable_scrub_move` (13292), `pref_gesture_floating_preview_text` (14642), `pref_gesture_preview_trail` (14643).
- Haptics: `enable_vibrate_on_keypress` (13305), `Vibrate on keypress` (12124), `Vibration strength on keypress` (12125), `Haptic feedback on keypress` (10409), `Key release haptic feedback cutoff` (10625), `pref_vibration_duration_settings` (14698), `ro.com.google.ime.vibration` (14829), `system_haptic_settings` (15896).
- Long-press delays: `key_long_press_delay` (13817), `key_long_press_delay_for_a11y` (13818), `pref_key_longpress_timeout` (14668), `pref_key_preview_popup_dismiss_delay` (14681), `space_key_long_press_delay=500` (15095), `ja_enable_12keys_longpress_delay=false` (13753), `korean_10key_timeout_milliseconds` (13910), `handwriting_timeout_ms` (13591), `handwriting_scrollout_delay` (13589).
- Undo/commit: `Undo` (12049), `undo is disabled` (15354), `Undo auto-correct on backspace` (12050), `Return to original text when backspacing after an autocorrection` (11433), `Auto commit glide input` (9594), `enable_gesture_auto_commit` (13260), `enable_gesture_auto_commit_zh_tw` (13261).
- Glide: `Glide typing` (10323), `Show gesture trail` (11633), `enable_gesture_input` (13262).
- Multilingual/Indic/transliteration: `enable_sc_tc_conversion` (13285), `enable_sc_tc_conversion_zh_hk` (13286), `enable_sc_tc_conversion_zh_tw` (13287), `enable_transliteration_tablet_floating_ui_v2=false` (13303), `romanized indic voice dictation` promo (9431), `enable_voice_in_chinese=false` (13307), `enable_voice_in_korean=false` (13308), `enable_tamil_phonetic_layout=false` (13302), `enable_hebrew_qwerty_layout=false` (13264), `enable_iw_staggered_layout` (13268), `enable_korean_composition_mode=false` (13273), `enable_wubi=false` (13311).
- Emoji keyword search: `assets/emoji_search_en_us.zip` (62,634 bytes) containing `en-us` (123,448 bytes) and `en-us.shortcuts` (6,583 bytes); manifest declares locales `en_au,en_ca,en_gb,en_in,en_ke,en_ng,en_ph,en_us,en_za` and compressed size 62,634.
- Remote config plumbing: `assets/phenotype/com_google_android_inputmethod_latin_package_metadata.binarypb` (171 bytes) plus many `com.google.android.gms.phenotype.*` manifest entries, indicating feature flags are largely Phenotype/remote-config driven, not simple local resources.

### B. Numeric resources vs remote placeholders vs inferred names

- The only directly observed numeric default in the string table is `space_key_long_press_delay=500` (`gboard_strings.txt` line 15095), i.e. 500 ms for space long-press.
- `key_long_press_delay` and `key_long_press_delay_for_a11y` (lines 13817-13818) are config keys without exposed numeric values in this artifact.
- `pref_key_longpress_timeout` (14668), `pref_key_preview_popup_dismiss_delay` (14681), `pref_vibration_duration_settings` (14698), and `handwriting_timeout_ms` (13591) are preference/config keys; their active values are not recoverable from the APK string/resource tables here.
- Most `enable_*` and `pref_*` identifiers are feature-flag/config keys. Their default true/false suffixes where present (e.g. `enable_auto_capitalization_on_pk=false`, `enable_transliteration_tablet_floating_ui_v2=false`) are build-time default hints, but runtime values come from Phenotype/remote config and user settings, so they cannot be treated as guaranteed active behavior.
- `ro.com.google.ime.*` entries (`key_popup`, `vibration`, `key_border`, `number_row`, `secondary_sym`, `emoji_key`, `bs_theme`, `hideicon`, `showicon`) are OEM/system-property style toggles, not normal in-app numeric settings.

### C. Native model asset inventory (by path/size only; no model data copied)

| Path | Size (bytes) |
|---|---:|
| `lib/x86_64/libintegrated_shared_object.so` | 38,721,448 |
| `res/iZX.dict` | 7,316,204 |
| `lib/x86_64/libgraphics-core.so` | 218,760 |
| `lib/x86_64/libimage_processing_util_jni.so` | 48,104 |
| `lib/x86_64/libnative_crash_handler_jni.so` | 35,896 |
| `lib/x86_64/libexpressive_concepts_model_less_predictor_jni_native.so` | 18,744 |
| `lib/x86_64/libgrammar-checker_jni.so` | 18,744 |
| `lib/x86_64/libogg_opus_encoder.so` | 18,744 |
| `assets/emoji_search_en_us.zip` | 62,634 |
| `assets/emoji_superpacks_manifest_bundled.zip` | 352 |

Notes:
- The large `libintegrated_shared_object.so` likely bundles native inference/IME core; its internal symbols/models were not decompiled here (no REA/JADX available).
- `res/iZX.dict` is the only large bundled dictionary visible. Other language models/dictionaries are not present in this APK and are expected to be downloaded via Phenotype/MDD/superpacks at runtime.
- **Why APK alone does not reveal trained user aggregate data:** personalized language models, learned words, federated aggregate updates, and user history are runtime/server-side artifacts. This APK contains only client code, flags, one bundled dictionary, theme assets, and emoji search data. No user aggregate model or personal dictionary payload is present.

### D. Contrast with our current implementation (read-only)

Our relevant files: `android/app/src/main/java/com/wisperlow/mobile/keyboard/KeyArea.kt`, `WisperlowKeyboardService.kt`, `android/app/src/main/java/com/wisperlow/mobile/settings/SettingsRepository.kt`, `android/keyboard-core/...`.

Five concrete small UX gaps with artifact evidence:

1. **Haptic strength/duration is binary in our settings, Gboard exposes graded vibration controls.**
   - Gboard evidence: `Vibration strength on keypress` (12125), `pref_vibration_duration_settings` (14698), `Key release haptic feedback cutoff` (10625) + explanatory string (12171), `ro.com.google.ime.vibration` (14829).
   - Ours: `KeyboardPrefs.haptics: Boolean` only (`SettingsRepository.kt`); service calls `performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)` (`WisperlowKeyboardService.kt` ~451/466) with no duration/strength.
   - Confidence: high (API surface visible). Verification needed: decide whether Android haptic APIs available on our minSdk; UX test on device.

2. **Long-press delay is a hard-coded 320 ms, while Gboard has a 500 ms space long-press default and separate a11y delay.**
   - Gboard evidence: `space_key_long_press_delay=500` (15095), `key_long_press_delay` (13817), `key_long_press_delay_for_a11y` (13818), `pref_key_longpress_timeout` (14668).
   - Ours: `LONG_PRESS_MS = 320L` in `KeyArea.kt` (line 505), `longPress = LocalViewConfiguration.current.longPressTimeoutMillis.coerceAtMost(LONG_PRESS_MS)`.
   - Confidence: high. Verification needed: measure current long-press misfires vs Gboard; expose setting rather than change default blindly.

3. **Key popups are always on in our code; Gboard makes popup-on-keypress a user toggle.**
   - Gboard evidence: `enable_popup_on_keypress` (13283), `Popup on keypress` (11318), `ro.com.google.ime.key_popup` (14825), `pref_key_preview_popup_dismiss_delay` (14681).
   - Ours: `KeyArea.kt` always renders `KeyPreview` (line 281) and alternates popup (line 284); no setting in `KeyboardPrefs`.
   - Confidence: high. Verification needed: add preference + settings UI + tests; check performance on low-end devices.

4. **Autospace-after-punctuation is not implemented/exposed, despite our autocorrect and double-space-period support.**
   - Gboard evidence: `enable_autospace_after_punctuation` (13236), `Autospace after punctuation` (9609), `enable_auto_space_smart_punctuation` (13233), beta string (12262).
   - Ours: `KeyboardPrefs` has `doubleSpacePeriod` and `autoCorrect`, but no autospace-after-punctuation flag or insertion logic found in grep.
   - Confidence: medium (absence based on grep). Verification needed: search decoder/typing session more deeply; implement punctuation-triggered space only for supported locales.

5. **Undo-after-autocorrect is internal-only; Gboard surfaces explicit "Undo auto-correct on backspace" UX.**
   - Gboard evidence: `Undo auto-correct on backspace` (12050), `Return to original text when backspacing after an autocorrection` (11433), `Undo` (12049).
   - Ours: `TypingSession.revertible` and `WisperlowKeyboardService` expose revert suggestion (`SuggestionPolicy`/`revert`), but UX is suggestion-strip based; no dedicated backspace-undo affordance or haptic/text confirmation.
   - Confidence: medium. Verification needed: inspect backspace handling; add explicit undo chip/timeout if desired.

### Unknowns / limitations

- REA engine unavailable; no JADX/decompilation was performed. DEX classes, methods, and exact runtime control flow were not inspected.
- Resource names are obfuscated, so numeric resource values and active flag defaults beyond string-table hints are not recoverable here.
- Phenotype remote config dominates feature activation; APK strings/config keys do not prove a feature is enabled for any user/device.
- No runtime/emulator interaction was performed (per instruction: parent is testing its own keyboard concurrently).
- Native model internals and trained aggregate data are outside APK scope.

## Verification

- APK SHA256 matches recorded value above.
- Report limited to paths under `/tmp/wisper-gboard-inspection/` and one docs file.
- No proprietary code, models, dictionaries, or assets copied into the repository.