# Keyboard UX Inventory & Wisperlow Gap Analysis

Date: 2026-10-17 · Public sources only (Google Help Center, Google blogs, Apple iPhone User Guide, Play Store listing). No app decompilation.

Sources actually fetched during this research run:

| # | Source | URL |
|---|--------|-----|
| G1 | Gboard — Use your keyboard | https://support.google.com/gboard/answer/2842292 |
| G2 | Gboard — Slide your finger to type | https://support.google.com/gboard/answer/2811346 |
| G3 | Gboard — Change theme, sound & vibration | https://support.google.com/gboard/answer/6102154 |
| G4 | Gboard — Get word suggestions & fix mistakes | https://support.google.com/gboard/answer/7068415 |
| G5 | Gboard — Copy & paste with the clipboard | https://support.google.com/gboard/answer/10742542 |
| G6 | Gboard — Type in a different language | https://support.google.com/gboard/answer/7068494 |
| G7 | Gboard — Type with your voice | https://support.google.com/gboard/answer/2781851 |
| G8 | Gboard — Proofread with "Fix it" | https://support.google.com/gboard/answer/14164531 |
| G9 | Gboard — Translate as you type | https://support.google.com/gboard/answer/7421372 (listed by search; body via search snippet) |
| G10 | Gboard — Use advanced voice typing features | https://support.google.com/gboard/answer/11197787 |
| G11 | Gboard — Fix problems with Gboard | https://support.google.com/gboard/answer/9058584 |
| G12 | Google blog — Indic languages & transliteration | https://blog.google/products-and-platforms/products/search/gboard-android-gets-new-languages-and-tools/ |
| G13 | Google blog — Emoji Kitchen | https://blog.google/products-and-platforms/platforms/android/emoji-kitchen-new-mashups-mixing-experience/ |
| G14 | Gboard Play Store listing (one-handed, floating, themes) | https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin |
| G15 | Gboard — Wikipedia (feature history) | https://en.wikipedia.org/wiki/Gboard |
| A1 | Apple — Use predictive text on iPhone | https://support.apple.com/en-in/guide/iphone/iphd4ea90231/ios |
| A2 | Apple — Type with the onscreen keyboard | https://support.apple.com/en-in/guide/iphone/iph3c50f96e/ios |
| A3 | Apple — Select, edit, and move text | https://support.apple.com/en-in/guide/iphone/iph1a9cae52c/ios |
| A4 | Apple — Dictate text | https://support.apple.com/en-in/guide/iphone/iph2c0651d2/ios |
| A5 | Apple — Add emoji, Memoji, and stickers | https://support.apple.com/en-in/guide/iphone/iph69df21ec5/ios |
| A6 | Apple — Translate text in apps | https://support.apple.com/en-in/guide/iphone/iphab4dcff1d/ios |
| A7 | Apple — Set up shorthand (Text Replacement) | https://support.apple.com/en-in/guide/iphone/iph6d01d862/ios |
| A8 | Apple — Smart Punctuation setting (Pages on iPhone) | https://support.apple.com/en-gu/guide/pages-iphone/tanad45f9cce/ios |

Not fetchable / not located this run (say-so items):
- Dedicated Gboard Help pages for **emoji search**, the **text editing panel**, and **incognito mode** were not found or not fetched. Emoji search and incognito are widely reported features; the "Block offensive words" toggle is documented only via Google's community threads (e.g. https://support.google.com/pixelphone/thread/246929683) and device settings paths (Gboard → Text correction). Treat those rows as lower-confidence.
- iOS "one-handed default" and keyboard sounds/haptics toggles come from A2/A3 and the Settings paths they describe.

Wisperlow evidence: all under `android/app/src/main/java/com/wisperlow/mobile/keyboard/` (K1 `TypingSession.kt`, K2 `KeyArea.kt`, K3 `WisperlowKeyboardService.kt`, K4 `Lexicon.kt`, K5 `KeyboardLayout.kt`, K6 `KeyboardTheme.kt`, K7 `Emoji.kt`/`EmojiPanel.kt`, K8 `ClipboardPanel.kt`/`ClipboardHistory.kt`, K9 `SmartSpacing.kt`, K10 `SettingsRepository.kt`).

---

# Part 1 — Feature Inventories

## 1.1 Gboard (Android)

### Core typing & keys

| Feature | Documented behaviour | Source |
|---|---|---|
| Key press preview | Shows enlarged character bubble above pressed key while typing | G1 |
| Long-press accents | Touch and hold a key to pick accented letters/alternate symbols | G1 |
| Long-press delay | Adjustable long-press duration in Gboard Preferences | G3 |
| Caps lock | Double-tap Shift to lock caps | G1 |
| Auto-capitalization | Sentence starts capitalized automatically (Text correction settings) | G4, G11 |
| Auto-correct | Auto-correction on by default, configurable in Text correction settings | G11, G4 |
| Undo autocorrect | Press Backspace right after an auto-correction to revert | G4 |
| Double-space period | Double-tap space inserts ". " (Text correction setting) | G11 |
| Auto-space after punctuation | Gboard inserts space after punctuation/suggestion acceptance | G1 (implied by typing flow; not a separate help section) |
| Smart quotes/dashes | iOS-only feature on Apple; Gboard does not document smart punctuation | n/a (see iOS §1.2) |
| Cursor via spacebar | Slide finger on spacebar to move cursor precisely | G1 |
| Spacebar language label | Current input language shown on spacebar | G1 |

### Corrections & suggestion strip

| Feature | Documented behaviour | Source |
|---|---|---|
| 3-slot suggestion strip | Center suggestion bolded/highlighted; strip shows next-word predictions too | G4 |
| Inline emoji/quote suggestions | Strip can suggest emoji and words in quotes; tap to insert | G4 |
| Drag suggestion to trash | Drag a strip suggestion into the trash icon to remove it from suggestions | G4 |
| Add to dictionary | Tap a suggestion entry-point to add word to user dictionary | G4 |
| Undo correction via backspace | Backspace restores the original typed word | G4 |
| Next-word prediction | Strip predicts the following word; tapping inserts + space | G4 |
| Spelling/grammar checks | "Spelling and grammar" text-correction options exist | G11 |
| Smart Compose | Draft-style auto-completions in supported messaging apps (US, English) | G4 |
| Proofread ("Fix it") | One-tap full-text grammar check via Google AI; Pixel 8+, US, English | G8 |
| Block offensive words | Toggle under Gboard Text correction (profanity blocked by default) | community-only (see "Not fetchable") |

### Gestures

| Feature | Documented behaviour | Source |
|---|---|---|
| Glide typing | Slide finger across letters to form a word; lift to commit | G2 |
| Gesture trail | Visual trail drawn under the finger while gliding | G2 |
| Gesture delete | Slide left from delete key to select-and-delete whole words/segments | G2 |
| Double-tap word delete | Double-tap a word (in glide context) to delete it | G2 |
| Spacebar cursor control | See "Cursor via spacebar" above | G1 |

### Layout & modes

| Feature | Documented behaviour | Source |
|---|---|---|
| One-handed mode | Pin keyboard to left or right edge on large screens | G14, G15 |
| Floating keyboard | Detachable, movable keyboard window | G14, G15 |
| Height adjust | Adjustable keyboard height (Preferences) | G3 |
| Number row option | Toggle a dedicated top number row | G1 |
| Themes | Theme picker with/without key borders, custom colors, wallpapers | G3 |
| Key borders | Themes can show or hide key borders | G3 |
| Toolbar | Plus/toolbar button exposes clipboard, translate, voice, etc. | G5, G7, G9 |
| Text editing panel | Arrow keys + select/copy/paste panel (in toolbar; Help page not located this run) | toolbar feature (say-so) |
| One-tap keyboard switch | Long-press spacebar to switch keyboard/language | G1 |

### Rich content & utilities

| Feature | Documented behaviour | Source |
|---|---|---|
| Emoji panel | Categories + recents; emoji/GIF/sticker tabs | G1, A-comparable |
| Emoji suggestions in strip | Typing a word suggests matching emoji in the strip | G4 (strip quotes/emoji), Pixel page for voice |
| Emoji search | Search field inside emoji panel (Help page not located this run) | say-so |
| Skin tone variants | Long-press emoji for skin tone variants | G1 (long-press variants) |
| GIFs | GIF search/insert via emoji panel | G1 |
| Stickers | Sticker tab and sticker search | G14 |
| Emoji Kitchen | Combine two emoji into mashup stickers via strip suggestion/panel | G13 |
| Clipboard history | Clipboard panel keeps recent copies, expires after ~1 hour default | G5 |
| Clipboard pin | Pin clips to keep them; paste part of a saved clip | G5 |
| Sensitive-clip guard | Password fields excluded from clipboard history | G5 |

### Voice & AI

| Feature | Documented behaviour | Source |
|---|---|---|
| Voice typing | Tap mic, speak; inserts punctuation automatically in supported langs | G7 |
| Auto punctuation | Voice typing adds commas/periods/question marks automatically | G7 |
| Voice commands | "scratch that", selection & replacement commands | G7, G10 |
| Advanced voice edits | "Detailed edits" send field text to servers; grammar/replace commands | G10 |
| Translate as you type | Type in your language, get live translation preview to send | G9 |
| Multilingual typing | Multiple languages active at once, mixed suggestions, no manual switching | G6 |
| Indic transliteration / Hinglish | Type "namaste" get Hindi script; Hinglish suggestions for 22 Indic languages | G12 |

### Privacy & accessibility

| Feature | Documented behaviour | Source |
|---|---|---|
| Incognito mode | When app field requests it, Gboard disables learning/personalization | say-so (Help page not fetched; consistent with G11 personalization toggle) |
| Personalization toggle | Turn off cloud personalization of suggestions | G11 |
| Sound & vibration tuning | Key sound on/off + volume; vibration on/off + strength | G3 |
| Handwriting input | Write with finger; predicted spaces | G1 |
| TalkBack support | Keys announce via Android accessibility (not detailed in fetched pages) | say-so |

## 1.2 iPhone (iOS)

| Feature | Documented behaviour | Source |
|---|---|---|
| Inline prediction | 3 top slots + **inline gray word completions inside the text field**; accept with space | A1 |
| Literal suggestion (quotes) | Suggestions shown in quotation marks keep exactly what you typed | A1 |
| Reject suggestion | Double-tap the same underlined suggestion 3× and iPhone stops offering it | A1 |
| Corrections when predictive off | Still displays correction under the typed word even with predictive off | A1 |
| QuickPath | Slide finger across letters to type (like glide) | A2 |
| Caps lock | Double-tap shift | A2 |
| Double-space period | Double-tap space inserts ". " | A2 |
| Accents | Touch-and-hold keys for accent variants | A2 |
| One-handed layouts | Left/right compressed keyboard layouts | A2 |
| Keyboard sounds/haptics | Typing feedback sounds + haptics toggles in Settings → Sounds & Haptics → Keyboard Feedback | A2 |
| Smart Punctuation | Curly quotes, em/en dashes, etc.; toggle in Settings → General → Keyboard → Smart Punctuation | A8 |
| Spacebar trackpad | Hold spacebar to turn keyboard area into trackpad; slide to move cursor | A3 |
| Magnify insertion point | Touch-and-hold text to magnify placement | A3 |
| Undo autocorrect | Tap underlined corrected word to see original | A3 |
| Shake to undo / undo-redo gestures | Shake or three-finger gestures | A3 |
| Dictation | On-device processing; keyboard stays open so typing+voice interleave; stops after 30 s silence | A4 |
| Dictation auto punctuation | Auto inserts punctuation in supported languages (toggle) | A4 |
| Dictation commands | "new line/paragraph", spell letters, "change X to Y", select/delete phrases, undo/redo | A4 |
| Dictation emoji | Say "heart emoji" etc. to insert emoji by voice | A4 |
| Emoji suggestions | Suggested emoji appear above keyboard while typing; tap to replace word | A5 |
| Emoji keyboard | Categories, recents, skin-tone long-press, Memoji & sticker tabs, sticker creation/search | A5 |
| Genmoji | Create custom emoji (Apple Intelligence) | A5 |
| Translate | Select text → Translate; Replace with Translation swaps text in field | A6 |
| Text Replacement (shorthand) | Phrase+shortcut substitution; iCloud sync; reset dictionary | A7 |
| Predictive-off inline behavior | See "Corrections when predictive off" above | A1 |
| Globe key / multilingual | Add keyboards per language, globe cycles them | A2/A4 |

---

# Part 2 — Wisperlow Coverage & Gap Analysis

## 2.1 Item-by-item status

Legend: ✅ present · 🟡 partial · ❌ missing

| Feature | Wisperlow status | Evidence |
|---|---|---|
| Key preview popup | ✅ | K2 (long-press popup + preview bubble) |
| Long-press accents/alternates | ✅ | K2 (320 ms long-press; digits then 450 ms accent delay), K5 |
| Caps lock | 🟡 | K2 — double-tap lock (350 ms) only; no hold-to-lock |
| Auto-capitalization | ✅ | K3 (cursorCapsMode-driven auto-cap) |
| Auto-correct | ✅ | K1 + K4 (touch-weighted Damerau correction, fix learning) |
| Undo autocorrect | ✅ | K1 (backspace reverts correction) |
| Double-space period | ✅ | K1 (450 ms window) |
| Auto-space after suggestion/punctuation | 🟡 | K9 — dictation spacing only; K1 hugging punctuation; no full auto-space after accepting suggestions |
| Smart quotes/dashes | ❌ | — |
| Suggestion strip (3 slots) | ✅ | K3 (3 slots + revert) |
| Inline (in-field) gray predictions | ❌ | strip-only; no composing-region inline text |
| Suggestion shown in quotes (literal accept) | ❌ | — |
| Reject-suggestion learning (3× rule) | ❌ | — |
| Drag suggestion to trash | ❌ | — |
| Emoji in strip | 🟡 | K3 — 2 slots, hardcoded ~130-word English map |
| Next-word prediction | ✅ | K4 (bigrams + previous-word context) |
| Personal dictionary learning | 🟡 | K4 (learn/trust/forget engine) but no user-facing dictionary editor UI |
| Glide typing | ❌ | K5 touch model used only for autocorrect paths |
| Gesture trail | ❌ | — |
| Gesture delete (swipe left from backspace) | 🟡 | K2 — hold-repeat (380 ms→55 ms) + word-delete after 20 repeats; no swipe gesture |
| Spacebar cursor control | 🟡 | K2 — space-drag cursor (9 dp/step); no selection/trackpad mode |
| One-handed mode | ❌ | — |
| Floating keyboard | ❌ | — |
| Height adjust | ✅ | K10 (heightScale 0.8–1.3) |
| Number row option | ✅ | K5, K10 |
| Themes / key borders | ❌ | K6 — single fixed light/dark lilac theme, no picker |
| Clipboard history | ✅ | K8 (3 min fresh, sensitive-clip guard, pin/delete, panel) |
| Paste part of clip | ❌ | K8 pastes whole clip only |
| Emoji panel | 🟡 | K7 (9 categories + recents) |
| Emoji search | ❌ | — |
| Skin tones (long-press) | ❌ | K7 |
| GIFs / stickers / Memoji | ❌ | — |
| Emoji Kitchen | ❌ | — |
| Text editing panel | ❌ | — |
| Translate | ❌ | — |
| Multilingual typing / globe key | 🟡 | K3 — space long-press keyboard switcher; English-only, no mixed suggestions |
| Indic transliteration / Hinglish | ❌ | — |
| Incognito mode | ✅ | K3 (IME_FLAG_NO_PERSONALIZED_LEARNING) |
| Voice typing | 🟡 | K3 — dictation + undo dictation + "scratch that"; no auto-punctuation, no select/replace commands |
| Block offensive words | ❌ | — (no profanity filter) |
| Smart Compose | ❌ | — |
| Proofread | ❌ | — |
| Sound & vibration tuning | 🟡 | K3/K10 — bool toggles only; no volume/strength sliders |
| Trackpad / magnifier editing | ❌ | — |
| Text replacement (shorthand) | ❌ | — |

## 2.2 Prioritized gaps

**P0 — hurts everyday typing feel**

1. **Glide typing** — biggest single absence for thumbs-on-glass; add a gesture recognizer over `Qwerty.kt` touch coordinates with the existing lexicon as the word candidate source (K4).
2. **Auto-space after accepting a suggestion/punctuation** — extend `SmartSpacing.kt` (K9) beyond dictation: insert space on suggestion commit and after `,.!?` unless next char is punctuation.
3. **Backspace swipe-to-delete-words** — replace the 20-repeat hack in `KeyArea.kt` with a left-drag gesture on backspace that selects whole words per step (G2/G15 pattern).
4. **Emoji strip breadth + emoji search + skin tones** — replace the hardcoded ~130-word map in `K3/K7` with a real emoji↔keyword index and a search field in `EmojiPanel.kt`; long-press skin tones.
5. **Spacebar trackpad/selection mode** — extend the existing space-drag cursor (`KeyArea.kt`) so entering it also supports selection anchors, matching iOS (A3).
6. **Smart punctuation** — curly quotes and dashes conversion hook in `TypingSession.kt` behind a setting (A8).

**P1 — expected features**

7. **One-handed mode** — shift layout X offset with an edge pin; layout math already parameterized in `KeyboardLayout.kt`.
8. **Text editing panel** — arrow/selection/copy-paste panel reachable from the toolbar row (G1; iOS editing gestures are field-level, so a panel is the practical IME equivalent).
9. **Caps-lock via shift long-press** and **themes/key borders picker** — `KeyArea.kt` state machine and `KeyboardTheme.kt` are already parameterized; add presets before custom colors.
10. **Suggestion-strip management** — drag-to-trash and reject-suggestion learning hooks exist naturally in `Lexicon.kt` (forget API already there).
11. **Sound volume / haptic strength sliders** — upgrade `KeyboardPrefs` bools to levels; service already routes effects centrally (`WisperlowKeyboardService.kt`).

**P2 — nice to have**

12. **Clipboard partial paste** — tap a pinned clip to open an editable preview before pasting (G5).
13. **GIFs / stickers / Emoji Kitchen / Genmoji-class content** — large scope; start with sticker/GIF tabs if any provider is planned.
14. **Translate as you type, Proofread, Smart Compose, advanced voice commands** — all cloud/AI-dependent; scope behind a backend decision.
15. **Multilingual typing / Indic transliteration / Hinglish** — blocked on per-language lexicons (currently English-only).
16. **Text replacement (shorthand) + dictionary editor UI** — `Lexicon.kt` storage exists; needs settings UI and expansion hook in `TypingSession.kt`.
17. **Inline in-field predictions** — requires composing-region cooperation from apps; significant platform work for partial payoff.
