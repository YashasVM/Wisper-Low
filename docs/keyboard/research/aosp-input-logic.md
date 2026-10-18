# AOSP LatinIME input logic — space states, punctuation, backspace, auto-caps

Source: `/tmp/rs-b/LatinIME` (AOSP `platform/packages/inputmethods/LatinIME`, Apache-2.0), shallow clone.
Primary files:

| File | Path in clone |
| --- | --- |
| `InputLogic.java` | `java/src/com/android/inputmethod/latin/inputlogic/InputLogic.java` (2353 lines) |
| `SpaceState.java` | `java/src/com/android/inputmethod/latin/inputlogic/SpaceState.java` (54 lines) |
| `CapsModeUtils.java` | `java/src/com/android/inputmethod/latin/utils/CapsModeUtils.java` (357 lines) |
| `SpacingAndPunctuations.java` | `java/src/com/android/inputmethod/latin/settings/SpacingAndPunctuations.java` (155 lines) |
| `RichInputConnection.java` | `java/src/com/android/inputmethod/latin/RichInputConnection.java` |
| `KeyboardState.java` | `java/src/com/android/inputmethod/keyboard/internal/KeyboardState.java` |
| `RecapitalizeStatus.java` | `java/src/com/android/inputmethod/latin/utils/RecapitalizeStatus.java` |
| Symbol-spacing resources | `java/res/values/donottranslate-config-spacing-and-punctuations.xml`, `java/res/values/config-common.xml` |

Wisperlow files compared:

- `android/keyboard-core/src/main/kotlin/com/wisperlow/mobile/keyboard/TypingSession.kt` (344 lines)
- `android/app/src/main/java/com/wisperlow/mobile/keyboard/WisperlowKeyboardService.kt` (750 lines)
- `android/app/src/main/java/com/wisperlow/mobile/keyboard/SmartSpacing.kt` (39 lines)

All AOSP line references below are to the cloned tree. Short excerpts are quoted under
Apache-2.0 with attribution.

---

## 1. The five space states

`SpaceState.java` defines exactly five states and its class comment states the contract:
"At any given time, the input logic is in one of five possible space states" (`SpaceState.java:22-26`).

| Constant | Value | Meaning (from `SpaceState.java:28-49`) | Set at |
| --- | --- | --- | --- |
| `NONE` | 0 | "the most 'standard' … no automatic input is added or removed. In this state, all self-inserting keys only insert themselves, and backspace removes one character." | `InputLogic.java:153` (`startInput`), `:255` (after text input), `:364` (cursor move), `:766` (any non-special character), `:992` (backspace), `:1854` (editor action) |
| `DOUBLE` | 1 | "the user pressed space twice quickly, which LatinIME resolved as period-space. In this state, pressing backspace will undo the double-space-to-period insertion: it will replace `". "` with `"  "`." | `InputLogic.java:937` |
| `SWAP_PUNCTUATION` | 2 | "a weak space and a punctuation from the suggestion strip have just been swapped. In this state, pressing backspace will undo the swap: the characters will be swapped back, and the space state will go to WEAK." | `InputLogic.java:941` |
| `WEAK` | 3 | "a space that should be swapped only by suggestion strip punctuation. Weak spaces happen when the user presses space, accepting the current suggestion… In this state, pressing a punctuation from the suggestion strip inserts it before the space (while it inserts it after the space in the NONE state)." | `InputLogic.java:867` (non-separator swap), `:945` (space pressed) |
| `PHANTOM` | 4 | "a not-yet-inserted space that should get inserted on the next input, character provided it's not a separator. If it's a separator, the phantom space is dropped. Phantom spaces happen when a user chooses a word from the suggestion strip. In this state, non-separators insert a space before they get inserted." | `InputLogic.java:328` (manual pick), `:530` (batch/gesture start), `:972` (separator that keeps it), `:1708` (revert autocorrect with space separator), `:2047` (batch tail) |

### 1.1 Transitions

```
                   pick suggestion (word)            ┌──────────────────────────────┐
        ┌──────────────────────────────────► PHANTOM ─┤ any letter / text insert     │
        │                                      │       │  → insert real space, NONE │
        │                                      │       ├─ word connector ( ' - )   │──► NONE
        │                                      │       │  → NO space (see §3.4)     │
        │                                      │       └─ separator: see §3        │
        │                                      │                                 │
 pick punctuation from strip / type separator   │                                 │
        │                                      │  ' , ) ; : ] ! ? … (preceded-by-space)
        │                                      ▼                                 │
        │                                   NONE ◄────────────────────────────────┘
        │
        ├─ space ──► WEAK  (InputLogic.java:943-946)
        │              └─ suggestion-strip punctuation ──► swap ⇒ SWAP_PUNCTUATION (:940-942)
        ├─ space, space ──► DOUBLE (:936-938)
        └─ revert autocorrect whose separator was space ──► PHANTOM (:1646, :1707-1708)

onUpdateSelection (any cursor move) ──► NONE (:364)   — deliberately kills all special handling
```

Key detail: the transaction captures the space state **at the start of the event**
(`new InputTransaction(..., mSpaceState, ...)` at `InputLogic.java:442-444`), and the space state is
always updated **before** the shift update is requested — the comment "Space state must be updated
before calling updateShiftState" appears at `:254`, `:327` and `:2046`, because `PHANTOM` feeds
auto-caps (`getCurrentAutoCapsState` passes `SpaceState.PHANTOM == mSpaceState` as `hasSpaceBefore`,
`:1766-1767`).

### 1.2 Phantom space after picking a suggestion

Entering `PHANTOM` (`:328`, "Don't allow cancellation of manual pick" at `:326` because
`mLastComposedWord.deactivate()` is called first) means the space is *not* in the editor yet. Three
consumers:

1. **Next word code point / text insert** — `onTextInput` (`:248-250`) and
   `handleNonSeparatorEvent` (`:805-812`) call `insertAutomaticSpaceIfOptionsAndTextAllow` before
   inserting the character, then set `NONE` (`:766`).
2. **Next separator** — `handleSeparatorEvent` (`:917-934`): in `PHANTOM` a separator inserts a real
   space first **only if** `isUsuallyPrecededBySpace(codePoint)` (with two exceptions, §3.2).
   Otherwise the phantom space is simply dropped.
3. **Caps mode** — `PHANTOM` makes `getCursorCapsMode` report `CAP_MODE_CHARACTERS|CAP_MODE_WORDS`
   even though the editor has no trailing space (`RichInputConnection.java:368-376`, called with
   `hasSpaceBefore = PHANTOM == mSpaceState`, `:1766-1767`).

Auto-space itself is gated (`InputLogic.java:2020-2026`):

```java
if (settingsValues.shouldInsertSpacesAutomatically()
        && settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
        && !mConnection.textBeforeCursorLooksLikeURL()) {
    sendKeyCodePoint(settingsValues, Constants.CODE_SPACE);
}
```

Manual pick also *skips* the phantom space when the picked word is empty, when in batch mode, or when
the picked text starts with a separator that is not "usually preceded by space" (`:297-306`).
Gesture/batch start sets `PHANTOM` when the code point before the cursor is a letter/digit or a
"usually followed by space" symbol, and refreshes the shift state unless the user manually overrode
auto-shift (`:525-539`).

---

## 2. Double-space → period

**Timeout:** `config_double_space_period_timeout` = **1100 ms** (`java/res/values/config-common.xml:28`),
read into `SettingsValues.mDoubleSpacePeriodTimeout` (`SettingsValues.java:59`, `:158`). The countdown
is a single timestamp of the *previous* space: `startDoubleSpacePeriodCountdown` stores
`inputTransaction.mTimestamp` (`:1296-1298`), and `isDoubleSpacePeriodCountdownActive` returns
`timestamp - start < mDoubleSpacePeriodTimeout` (`:1304-1307`). Any **non-space** key cancels the
countdown (`:458-460`); backspace cancels it (`:1072`); a successful conversion cancels it (`:1349`).

**Transformation** (`tryPerformDoubleSpacePeriod`, `:1325-1359`) — all four conditions must hold:

1. `settingsValues.mUseDoubleSpacePeriod` is on, **and** `event.mCodePoint == CODE_SPACE`, **and** the
   countdown is still active (`:1329-1332`).
2. Read 3 chars before the cursor (3 because the preceding code point may be a surrogate pair) and
   require length ≥ 2 with the last char a space (`:1336-1342`).
3. The code point before that space passes `canBeFollowedByDoubleSpacePeriod` (`:1348`).
4. Then: `deleteTextBeforeCursor(1)`, commit `mSentenceSeparatorAndSpace` (`.` + space for English,
   `SpacingAndPunctuations.java:64-65`), and force a shift update + suggestions update
   (`:1349-1356`).

**Allowed preceding characters** (`:1372-1385`, an allowlist — "TODO: This should probably be a
denylist"):

```java
return Character.isLetterOrDigit(codePoint)
        || codePoint == Constants.CODE_SINGLE_QUOTE
        || codePoint == Constants.CODE_DOUBLE_QUOTE
        || codePoint == Constants.CODE_CLOSING_PARENTHESIS
        || codePoint == Constants.CODE_CLOSING_SQUARE_BRACKET
        || codePoint == Constants.CODE_CLOSING_CURLY_BRACKET
        || codePoint == Constants.CODE_CLOSING_ANGLE_BRACKET
        || codePoint == Constants.CODE_PLUS
        || codePoint == Constants.CODE_PERCENT
        || Character.getType(codePoint) == Character.OTHER_SYMBOL;
```

So `,,` `!!` `..` do **not** become `. `, but `(a)  ` and `100%  ` do. The resulting state is
`DOUBLE` (`:937`) and the suggestion strip refreshes (`:938`).

---

## 3. Punctuation swapping and auto-spacing

### 3.1 The symbol tables (`donottranslate-config-spacing-and-punctuations.xml`)

| Resource string | Line | Value (English default) |
| --- | --- | --- |
| `suggested_punctuations` | `:24` | `!,?,\,:,;,\",(,),\',-,/,@,_` |
| `symbols_preceded_by_space` | `:26` | `([{&<` |
| `symbols_followed_by_space` | `:28` | `.,;:!?)]}&<` |
| `symbols_clustering_together` | `:30` | *(empty for English)* |
| `symbols_word_separators` | `:33` | tab, space, `\n`, NBSP, `"`, `'`, `(`, `)`, `[`, `]`, `{`, `}`, `*`, `<`, `>`, `+`, `=`, `|`, `.`, `,`, `;`, `:`, `!`, `?`, `/`, `_`, `"` |
| `symbols_word_connectors` | `:35` | `' -` (apostrophe, hyphen) |
| `sentence_separator` | `:38` | 46 (`.`) |
| `abbreviation_marker` | `:41` | 46 (`.`) |
| `symbols_sentence_terminators` | `:43` | `.?!` |
| `current_language_has_spaces` | `:45` | true |

All are loaded once per language into sorted code-point arrays for binary search
(`SpacingAndPunctuations.java:48-59`) and exposed as `isWordSeparator`, `isWordConnector`,
`isWordCodePoint` (= letter or connector, `:103-105`), `isUsuallyPrecededBySpace`,
`isUsuallyFollowedBySpace`, `isClusteringSymbol`, `isSentenceTerminator`, `isAbbreviationMarker`,
`isSentenceSeparator` (`:95-129`). Language also sets `mUsesAmericanTypography` / `mUsesGermanRules`
(`:70-71`), which the caps logic uses (§5).

### 3.2 Which punctuation takes a space before or after

**Auto-space before** (only from `PHANTOM`, `handleSeparatorEvent:914-934`):

- `isUsuallyPrecededBySpace` → yes: `(`, `[`, `{`, `&`, `<`.
- `"` (U+0022) is treated as preceded-by-space **unless** we are already inside a double quote or
  the previous char is a digit: `needsPrecedingSpace = !isInsideDoubleQuoteOrAfterDigit`
  (`:914-923`). That helper (`StringUtils.isInsideDoubleQuoteOrAfterDigit`,
  `common/.../StringUtils.java:511-536`) scans back for an unbalanced quote: a `"` followed by
  whitespace is a closing quote, a `"` preceded by whitespace is an opening quote, a digit before
  the cursor also returns true.
- Clustering exception (`:924-927`): if the character is a clustering symbol and the previous
  character is also a clustering symbol, no space (English has an empty clustering table, so this
  is inert there).

**Auto-space after**: `isUsuallyFollowedBySpace` = `.,;:!?)]}&<`. Used in two places:
`onStartBatchInput:526-527` (gesture start after a letter or a followed-by-space symbol) and the
`PHANTOM` retention rule below.

**Word connectors** — `'` and `-` are *not* separators; they are word code points
(`SpacingAndPunctuations.java:99-105`) so they extend the composing word, but they never start one
(`InputLogic.java:848`, "word connectors … in the first position … should not start composing a
word"), and a connector typed in `PHANTOM` gets **no** auto-space (`:805-806`
`!settingsValues.isWordConnector(codePoint)`). Apostrophes and hyphens are also allowed *before* a
double-space period (§2, `canBeFollowedByDoubleSpacePeriod`).

### 3.3 The swap mechanism (weak space + strip punctuation)

`tryStripSpaceAndReturnWhetherShouldSwapInstead` (`:1273-1294`):

```java
if (Constants.CODE_ENTER == codePoint && SpaceState.SWAP_PUNCTUATION == inputTransaction.mSpaceState) {
    mConnection.removeTrailingSpace();   // Enter right after a swap: drop the space
    return false;
}
if ((SpaceState.WEAK == inputTransaction.mSpaceState
        || SpaceState.SWAP_PUNCTUATION == inputTransaction.mSpaceState)
        && isFromSuggestionStrip) {
    if (inputTransaction.mSettingsValues.isUsuallyPrecededBySpace(codePoint)) return false; // keep space
    if (inputTransaction.mSettingsValues.isUsuallyFollowedBySpace(codePoint)) return true;  // swap
    mConnection.removeTrailingSpace();   // stripper: no space on the right → just delete it
}
```

Then `trySwapSwapperAndSpace` (`:1254-1265`) deletes the trailing space and commits
`text + " "` — i.e. `"you" + [,] from the strip` yields `you, `. Callers set `WEAK`
(`:866-867`, for a non-separator code point) or `SWAP_PUNCTUATION` (`:940-942`, separators) and clear
the suggestion strip (`:942`, `:979`).

Note the taxonomy AOSP uses, spelled out at `:968-971`:

> The case is a little different if the separator is a space stripper. Such a separator does not
> normally need a space on the right (that's the difference between swappers and strippers), so we
> should not stay in phantom space state if the separator is a stripper.

- **Swapper** (space after): `.,;:!?)]}&<` — space moves to the right of the punctuation.
- **Stripper** (no space after, e.g. `'`, `-`, `,`, `@`, `_` from the strip): the weak space is
  deleted entirely.
- **Opener** (space before): `([{&<` — space stays where it was.

### 3.4 Punctuation typed in `PHANTOM` stays in `PHANTOM`

`:957-973`: after a separator, if the previous state was `PHANTOM` **and** the separator is
followed-by-space, or it is `"` while inside a quote/after a digit, the state **stays `PHANTOM`** so
the space is still inserted before the *next* word. The comment gives the canonical case: type
"Good dat", pick "day", insert a comma, then type — the space must still appear before the next
word.

### 3.5 Straight from the keyboard, not the strip

With `NONE` (e.g. the user typed space, then pressed `,` on the keyboard), nothing is moved: the
punctuation is simply sent (`:975`). AOSP's design avoids `you ,` because the space before `,` is
normally *the separator the user just typed*; the swap only applies to the suggestion strip's
punctuation, where the space came from accepting a suggestion.

---

## 4. Backspace

`handleBackspaceEvent` (`:990-1190`) is an ordered cascade; `mSpaceState = NONE` immediately
(`:992`), and shift is updated now — except during key repeat away from position 0, where it is
deferred to avoid flicker (`SHIFT_UPDATE_LATER`, `:995-1005`).

| Order | Case | Lines | Effect |
| --- | --- | --- | --- |
| 0 | Cursor front/middle of composing word (re-correction) | `:1007-1016` | Unlearn the word, `resetEntireInputState`, then fall through with no composition |
| 1 | Composing word | `:1017-1036` | `applyProcessedEvent` (one char out); `setComposingTextInternal` or `commitText("")` when the word empties; refresh suggestions |
| 2 | **Revert autocorrect** | `:1038-1058` | `mLastComposedWord.canRevertCommit()` → `revertCommit` (`:1634-1723`): delete `committedWord + separator`, unlearn the committed word, re-commit `typedWord` (+ separator unless it was a space, in which case **restore `PHANTOM`**, `:1646`, `:1705-1709`), re-attach a `SuggestionSpan` with the old suggestions (`:1671-1703`), then **restart suggestions** so the eventual fix is learned (`:1043-1056`) |
| 3 | Multi-character input (emoji key, `.com`) | `:1059-1070` | If text before cursor equals `mEnteredText`, delete exactly that many chars and return |
| 4 | **Revert double-space period** | `:1071-1082` | `cancelDoubleSpacePeriodCountdown()`; `revertDoubleSpacePeriod` (`:807-828`) verifies `". "` is really before the cursor, deletes 2 chars, commits a **single** space; sets composing caps mode to `CAPS_MODE_OFF` |
| 5 | **Revert swapped punctuation** | `:1083-1089` | `revertSwapPunctuation` (`:830-850`) verifies a space sits after the punctuation, deletes 2 chars, commits `" " + punctuation`; state stays the one reset at `:992` (i.e. `NONE`) |
| 6 | Selection exists | `:1095-1109` | Get selected text, unlearn it, collapse selection to its end, delete `selEnd - selStart` chars |
| 7 | `TYPE_NULL` / pre-JellyBean / unknown cursor | `:1112-1138` | Send a real `KEYCODE_DEL` event; if `mDeleteCount > DELETE_ACCELERATE_AT` (**20**, `common/.../Constants.java:182`) delete a second char and unlearn the word being left behind |
| 8 | Normal | `:1139-1172` | Delete 1 UTF-16 unit (2 for supplementary code points); accelerate past 20 deletes |
| 9 | Learning | `:1174-1178` | `unlearnWordBeingDeleted` unless already done |
| 10 | Resume suggestions | `:1179-1188` | If not a slow connection, suggestions enabled, language has spaces, and the cursor is **not** followed by a word character → `restartSuggestionsOnWordTouchedByCursor` |

`revertCommit` is gated by `mLastComposedWord`, which `deactivate()`s when a key other than
delete/shift/capslock/alpha-symbol follows a commit that did *not* autocorrect (`:482-488`) — so
revert only works immediately after a real autocorrect.

---

## 5. Auto-caps

### 5.1 Caps pipeline

`getCurrentAutoCapsState` (`InputLogic.java:1758-1768`):

```java
if (!settingsValues.mAutoCap) return CAP_MODE_OFF;
final EditorInfo ei = getCurrentInputEditorInfo();
if (ei == null) return CAP_MODE_OFF;
final int inputType = ei.inputType;
return mConnection.getCursorCapsMode(inputType, settingsValues.mSpacingAndPunctuations,
        SpaceState.PHANTOM == mSpaceState);
```

Two things matter here:

1. **`reqModes == ei.inputType`.** The caps bits are *masked by the field's own inputType*
   (`RichInputConnection.java:392`). Email (`TYPE_TEXT_VARIATION_EMAIL_ADDRESS`), URL/URI
   (`TYPE_TEXT_VARIATION_URI`) and search fields carry no `TYPE_TEXT_FLAG_CAP_SENTENCES` bit, so
   `getCapsMode` returns 0 for them (`CapsModeUtils.java:118-122` early-returns whenever
   `(reqModes & (WORDS|SENTENCES)) == 0`) and auto-caps is off with no special-casing code. AOSP
   contains **no** `isEmailField`/`isUrlField` helper anywhere in the tree — the inputType mask *is*
   the mechanism.
2. **Composing text short-circuits everything** (`RichInputConnection.java:368-376`): with a
   composition, the result is `CAP_MODE_CHARACTERS` (plus `WORDS` if `PHANTOM`). The committed-text
   cache before the composition is what `getCapsMode` sees (`:392-393`), reloaded from the editor when
   the cache is empty and the cursor is not at 0 (`:382-387`).

`getActualCapsMode` (`:1732-1745`) combines manual and auto:

```java
if (keyboardShiftMode != WordComposer.CAPS_MODE_AUTO_SHIFTED) return keyboardShiftMode; // manual wins
final int auto = getCurrentAutoCapsState(settingsValues);
if (0 != (auto & TextUtils.CAP_MODE_CHARACTERS)) return WordComposer.CAPS_MODE_AUTO_SHIFT_LOCKED;
if (0 != auto) return WordComposer.CAPS_MODE_AUTO_SHIFTED;
return WordComposer.CAPS_MODE_OFF;
```

So `CAP_MODE_CHARACTERS` (all-caps field) maps to *shift-locked*, word/sentence modes map to
*automatic shift* (one capital), otherwise off. `getKeyboardShiftMode`
(`KeyboardSwitcher.java:484-500`) maps keyboard ids back: shift-locked and shift-lock-shifted →
`CAPS_MODE_MANUAL_SHIFT_LOCKED`, manual-shifted → `CAPS_MODE_MANUAL_SHIFTED`, automatic-shifted →
`CAPS_MODE_AUTO_SHIFTED`, anything else → off.

`KeyboardState.onUpdateShiftState` (`KeyboardState.java:431-485`) is the state machine:
recapitalize wins (`:467-471`), update is ignored while the shift key is held/chording (`:472-476`),
auto-caps only applies while not shift-locked (`:477`).

### 5.2 `CapsModeUtils.getCapsMode` (`CapsModeUtils.java:103-328`)

Stated as a rule comment at `:105-115`:

```
CAP_MODE_CHARACTERS is always on.
CAP_MODE_WORDS is on if there is some whitespace before the cursor.
CAP_MODE_SENTENCES is on if there is some whitespace before the cursor, and the end
  of a sentence just before that.
```

1. **`CAP_MODE_CHARACTERS` is unconditional** (`:117-122`), intersected with `reqModes`.
2. **Skip start punctuation** walking back from the cursor (`:130-140`): `isStartPunctuation`
   (`:75-80`) is Unicode `START_PUNCTUATION` plus `"`, `'`, `¿`, `¡`. If `hasSpaceBefore` (the
   `PHANTOM` case) the scan is skipped entirely.
3. **Paragraph start** (`:146-178`): skip spaces/tabs backwards; if nothing but whitespace is found
   (or the text is empty), all three modes. German carve-out (`:159-172`): if the previous line ends
   with a comma and there was a newline, return `CHARACTERS|WORDS` only — a line after a comma is
   not a sentence.
4. **No whitespace immediately before** (`:179-183`) → `CHARACTERS` only; if sentences weren't
   requested → `CHARACTERS|WORDS` (`:184-189`).
5. **American typography** (`:201-212`): for English, skip closing punctuation *and* quotes before the
   terminator, because `"let's go home?"` puts `?` inside the quotes. Other languages skip this.
6. **Terminator** (`:223-227`): if the char is in `symbols_sentence_terminators` (`.?!`) and is
   **not** the abbreviation marker, all three modes. (`.` is *both*, hence the branch.)
7. **Period that is not a sentence separator, or nothing before it** (`:234-236`) →
   `CHARACTERS|WORDS`.
8. **Abbreviation state machine** for a real `.` (`:238-328`), documented in full at `:243-265`.
   States `START, WORD, PERIOD, LETTER, NUMBER`; `NUMBER` is only reachable in German
   (`:286-287`, "11.11." must not capitalise). Transitions:

   | State | input | next / result |
   | --- | --- | --- |
   | `START` | letter | `WORD` |
   | `START` | whitespace | **no caps** (standalone `.`) |
   | `START` | digit (German only) | `NUMBER` |
   | `START` | anything else | **caps** (several periods/symbols in a row) |
   | `WORD` | letter | `WORD` |
   | `WORD` | `.` | `PERIOD` |
   | `WORD` | else | **caps** ("yes." ends a sentence) |
   | `PERIOD` | letter | `LETTER` |
   | `PERIOD` | else | **caps** |
   | `LETTER` | letter | `LETTER` |
   | `LETTER` | `.` | `PERIOD` |
   | `LETTER` | else | **no caps** (it's an abbreviation) |
   | `NUMBER` | letter | `WORD` |
   | `NUMBER` | digit | `NUMBER` |
   | `NUMBER` | else | **no caps** |
   | end of line | — | `START` or `LETTER` → **no caps**; else **caps** (`:328`) |

   Consequence: `e.g. `, `i.e. `, `Mr. `, `etc. ` do **not** capitalise (`(\w\.){2,}`), while
   `Yes. ` and `done!. ` do. A single-letter word with a period (`A. `) ends in state `WORD` → caps,
   which is why `Mr. ` works via the `LETTER`+`WORD` path only when there are two dots.

### 5.3 Shift on a selection: recapitalize cycling

`performRecapitalization` (`InputLogic.java:1391-1422`): requires a selection, `mRecapitalizeStatus`
enabled (it is *disabled* at `startInput` (`:154`) and enabled the first time the cursor moves
(`:415`)), and not already started at that exact selection. First press captures
`selectionStart/End` and the text; a repeat at the same range `rotate()`s the mode and re-commits the
recapitalized string with the new selection.

Modes and their rotation order (`RecapitalizeStatus.java:27-40`):

```java
CAPS_MODE_ORIGINAL_MIXED_CASE = 0, CAPS_MODE_ALL_LOWER = 1,
CAPS_MODE_FIRST_WORD_UPPER = 2, CAPS_MODE_ALL_UPPER = 3;   // rotation order
```

`KeyboardState.updateShiftStateForRecapitalize` (`:450-463`) shows the shift the keyboard shows for
each mode: `ALL_UPPER` → shift-lock-shifted, `FIRST_WORD_UPPER` → automatic shift, lower/mixed →
unshift. `onPressShift` returns early while recapitalizing, "including importantly the double tap
timer" (`:487-491`) — so shift cycling is disabled during recapitalization; the *typing* shift cycles
the case, the shift key does not. `getCurrentRecapitalizeState` (`:1770-1778`) reports
`NOT_A_RECAPITALIZE_MODE` unless the recapitalization is started *at the current selection*.

---

## 6. Cursor moves and external edits

`onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, settingsValues)`
(`InputLogic.java:353-421`):

1. **Ignore our own late updates**: `isBelatedExpectedUpdate` (`:355-357`,
   `RichInputConnection.java:852-…`) compares against the expected selection.
2. **`mSpaceState = SpaceState.NONE`** (`:364`) — "after a cursor move, we don't want the space
   state-related special processing to kick in".
3. Decide between *keeping* the composition and *resetting* (`:366-412`):
   `selectionChangedOrSafeToReset = selChanged || !isComposingWord()`; `hasOrHadSelection`;
   `moveAmount = newSelStart - oldSelStart`. Reset the whole input state when there is (or was) a
   selection, when suggestions are off, or when `selectionChangedOrSafeToReset && !moveCursorByAndReturnIfInsideComposingWord(moveAmount)`
   — i.e. a small move *inside* the composing word keeps the composition alive. The long comment at
   `:382-395` explains why no composing span is set for re-correction: it would fight with the
   framework and cause selection havoc on double-tap.
4. When resetting: `resetEntireInputState(newSelStart, newSelEnd, clearSuggestionStrip = false)` —
   deliberately *not* clearing the strip, to avoid visible jank while `postResumeSuggestions` runs
   (`:391-396`). If a word was being corrected by the cursor, learn it first (`:399-404`).
5. When keeping: `resetCachesUponCursorMoveAndReturnSuccess(newSelStart, newSelEnd, shouldFinishComposition = false)`.
6. Always: enable recapitalization, `postResumeSuggestions(true /* shouldDelay */)` (`:417`),
   stop the last recapitalization, forget `mWordBeingCorrectedByCursor` (`:419-420`).

**Re-correction when the cursor lands in a word** — `restartSuggestionsOnWordTouchedByCursor`
(`:1514-1619`), run from the post-resume-suggestions handler and after a reverted commit:

- Bail out entirely if the app is broken by recorrection, the language has no spaces, suggestions
  are off, a batch input is running, there is a selection, or the cursor position is unknown
  (`:1521-1537`) — the strip goes neutral.
- `isCursorTouchingWord(checkTextAfter = true)`; if not touching a word, show **predictions** with
  composing caps forced off (`:1539-1545`).
- `getWordRangeAtCursor`; bail on `range.mHasUrlSpans` ("we just do not resume because it's safer",
  `:1556-1559`) and on implausible positions (`:1560-1561`).
- Put the **typed word itself first** in the strip (`:1562-1569`), then the suggestions found in the
  word's `SuggestionSpan`s, marked `KIND_RESUMED` (`:1574-1587`).
- `isResumableWord` (`:1819-1824`) is the gate: first code point must be a word code point and must
  **not** be `'` or `-`.
- Recreate the composing word and cursor position within it, and set the composing region to the
  whole word (`:1588-1597`). If no spans existed, compute fresh suggestions (`:1598-1607`); otherwise
  show the recovered ones with `willAutoCorrect = false` and `typedWordValid = false`
  (`:1613-1616`).

Other cursor-move details: `WordComposer.moveCursorByAndReturnIfInsideComposingWord` is the
"am I still inside the word?" primitive, and every event handler starts by checking
`isCursorFrontOrMiddleOfComposingWord()` — if the user parked the cursor inside a re-corrected word,
the handler first unlearns and `resetEntireInputState`s so the new character lands in the right place
(`:771-783`, `:814-823`, `:889-897`, `:1007-1016`). `startInput` also repairs a lying cursor position
(`tryFixLyingCursorPosition`, `:158`; implementation `RichInputConnection.java:924-936`).

---

## 7. Wisperlow comparison

Files: `TypingSession.kt`, `WisperlowKeyboardService.kt` (plus `SmartSpacing.kt` for dictated text).

Legend: **present** = behaviour and shape match; **partial** = works for the common case but misses a
listed condition or takes a different mechanism; **missing** = not implemented.

| # | Behaviour | AOSP rule | Wisperlow | Status | Exact rule to implement |
| --- | --- | --- | --- | --- | --- |
| 1 | `NONE` state | "all self-inserting keys only insert themselves, backspace removes one character" (`SpaceState.java:28-30`) | No state machine; `word.isEmpty()` is the only notion (`TypingSession.kt:88`, `:148`) | **partial** | Introduce `enum class SpaceState { NONE, DOUBLE, SWAP_PUNCTUATION, WEAK, PHANTOM }` in `TypingSession` and set `NONE` on: any code point insert, any backspace, any cursor move (`WisperlowKeyboardService.kt:223-248`). Today `undo` doubles as a partial stand-in and is cleared in the wrong places (`forgetHistory` at `:236`). |
| 2 | `PHANTOM` after picking a suggestion | space is **not** inserted; inserted before the next non-connector (`InputLogic.java:328`, `:805-812`) | `pick` commits `"$suggestion "` — a **real** space (`:200`, `:213`) | **missing** (eager space) | `pick()` should record `SpaceState.PHANTOM` and commit only the word. In `type()`, insert a real space before the first non-connector code point and drop it for `'`/`-` (`isWordChar` already encodes the connector rule, `:341-342`). Expected win: no `pick → backspace` space deletion, no `"word ("` openers. |
| 3 | `PHANTOM` before openers | space inserted before `( [ { & <` (`InputLogic.java:917-934`) | none | **missing** | With `PHANTOM` in place, add a `PRECEDED_BY_SPACE = "([{&<"` table and insert the phantom space for those (and for `"` unless inside a quote/after a digit). |
| 4 | `PHANTOM` + separators keeps `PHANTOM` | followed-by-space separator keeps the phantom (`:957-973`) | `"day,"` then typing yields `"day,word"` because the space was already committed | **missing** | Add `FOLLOWED_BY_SPACE = ".,;:!?)]}&<"`; after a separator in `PHANTOM`, restore `PHANTOM` instead of `NONE`; for strippers (no space right) go to `NONE`. |
| 5 | Weak space + strip punctuation swap | `"you" + [,] → "you, "`; state `SWAP_PUNCTUATION` (`:863-870`, `:940-942`, `:1254-1265`) | `HUGGING_PUNCTUATION = ". , ! ? ; :"` deletes the preceding space (`:98-100`) — the swap is modelled as a delete | **partial** | Model it as a real swap: `deleteBefore(1)` + `commit(punct + " ")` (as AOSP), set `SWAP_PUNCTUATION`, and keep openers (`([{&<`) from consuming the space. Extend the table to `) ] } & <` and route every suggestion-strip punctuation through one `trySwap` helper. |
| 6 | Strippers delete the weak space | `' - , @ _` from the strip: strip the space (`:1291`) | `HUGGING_PUNCTUATION` misses `' - @ _`; `,`/`!`/`?` are treated as swappers, so `"you 's"` keeps a space | **partial** | Add a stripper set (`' - , @ _ /`) whose rule is "delete the trailing space, then insert". |
| 7 | `"` behaves both ways | preceded-by-space unless inside a quote/after a digit; followed-by-space when inside (`:914-923`, `:959-960`) | `"` is in `CORRECTING_PUNCTUATION` (`:329`) only | **missing** | Port `isInsideDoubleQuoteOrAfterDigit` (quote-followed-by-space = closing, quote-preceded-by-space = opening, digit before cursor = true) and branch `"` on it in both the preceding and the `PHANTOM`-retention rules. |
| 8 | Word connectors `'` `-` | extend a word, never start one, never get a phantom space (`:805-806`, `:848`) | `isWordChar(c, inWord)` allows `'` and `’` mid-word but **not** `-` (`:341-342`) | **partial** | Add `-` (and `–`/`—`) to the mid-word connector set; keep `'`/`-` out of the word-start set, which `isWordChar(c, word.isNotEmpty())` at `:88` already does. |
| 9 | Clustering symbols | no space between two clustering symbols (`:924-927`) | table empty in AOSP English | **present** (inert) | No action unless a non-English table is added. |
| 10 | Double-space → `. ` | timeout **1100 ms** (`config-common.xml:28`); last char must be a space; preceding char in allowlist (`:1325-1359`, `:1372-1385`); state `DOUBLE` | `DOUBLE_SPACE_MS = 450L` (`:324`); allowed set `isLetterOrDigit() || in "\")'"` (`:118`) | **partial** | Raise the timeout to 1100 ms; replace the ad-hoc set with `Character.isLetterOrDigit(cp) || cp in '\'"%+' || cp in ')]}>' || Character.getType(cp) == OTHER_SYMBOL`. Also `mLastKeyTime`-style cancellation: `space()` sets `lastSpaceAt` unconditionally at `:129`, so it does double-space after *any* previous space, even 10 s earlier — the `now - lastSpaceAt` check saves it, but a non-space key should reset `lastSpaceAt = 0` (AOSP `:458-460`). |
| 11 | Revert double-space period | `. ` → single space (`:1071-1082`, `RichInputConnection.java:807-828`) | none; backspace just deletes the space, leaving `.` (`:154`) | **missing** | Add `SpaceState.DOUBLE` + `undo = Undo(kind = DOUBLE_SPACE)`; on backspace, if `field.before(2) == ". "`, `deleteBefore(2)` then `commit(" ")`. |
| 12 | Revert swapped punctuation | `", "` (punct then space) becomes `" ,"` (space then punct): delete 2 chars, commit `" " + punct` (`:1083-1089`, `RichInputConnection.java:830-850`) | none | **missing** | Store the punct in `Undo` when swapping; on backspace verify `field.before(2)[1] == ' '`, `deleteBefore(2)`, `commit(" " + punct)`, restore `WEAK`. |
| 13 | Revert autocorrect | delete `committed + separator`, re-commit typed, **restore `PHANTOM`** if the separator was a space (`:1038-1058`, `:1646`, `:1705-1709`) | `backspace()` restores `u.typed` as composing (`:135-147`) — but `undo` is dropped whenever `pick`/`insert`/`finishWord` runs, and `space()` keeps `Undo(it.first, it.second, " ")` whose `replacement` includes the space, so backspace deletes the space too and re-composes the word | **partial** | Keep the space out of the deleted span when the trailing was a space: `deleteBefore(corrected.length)`, `setComposing(typed)`, and set `SpaceState.PHANTOM` so the space is re-inserted on the next key (AOSP `:1646`). Today `replacement = corrected + trailing` (`:53`) conflates the two. |
| 14 | Restart suggestions after a revert | explicit, "required for the final corrected word to be learned" (`:1043-1056`) | `afterEdit()` → `refreshSuggestions()` (`:317-322`) fires on every key | **present** | No action. |
| 15 | Backspace deletes a selection | unlearn selection, collapse to end, delete `selEnd-selStart` (`:1095-1109`) | delegated to the app via `KEYCODE_DEL` (`:154`, `:690`) | **partial** | Equivalent visible behaviour, but `TypingSession` state (`word`, `undo`, `last`) is cleared *before* the app acts (`:133-134`), so if the app does something unexpected the session is desynced. Consider reading back the selection before committing to the reset. |
| 16 | Delete acceleration | second delete after `DELETE_ACCELERATE_AT = 20` (`Constants.java:182`) | `WORD_DELETE_AFTER = 20` held-backspace → `deleteWord()` (`WisperlowKeyboardService.kt:300`, `KeyArea.kt:512`) | **partial** | AOSP accelerates at 20 *within the same press* (per-delete double step), then deletes a whole word. Keep the word threshold, add the in-press double-delete after 20 repeats. |
| 17 | Unlearn the word being deleted | `unlearnWordBeingDeleted` (`:1091`, `:1174-1178`) | `lexicon()?.trust(u.typed)` only on revert (`:142`); no unlearn on ordinary deletion | **partial** | When a delete crosses a word boundary (detected in `recomposeWordBeforeCursor`, `:300-317`), call an unlearn on the word just deleted. |
| 18 | Resume suggestions when backspacing into a word | `restartSuggestionsOnWordTouchedByCursor` (`:1514-1619`) | `recomposeWordBeforeCursor` (`:300-317`) — word must end at cursor (`after(1)` not a word char, `:303`), length ≤ `MAX_WORD` (32), must contain a letter | **partial** | AOSP uses `getWordRangeAtCursor` and works mid-word too; Wisperlow bails when there is a word character after the cursor, so `foo|bar` never re-composes. Drop the `:303` early-return and slice the range around the cursor instead of to its end. |
| 19 | Resume when the cursor **moves** into a word | same method, driven from `onUpdateSelection` (`:417`) | `onUpdateSelection` only *resets* when the cursor leaves the word (`:238-245`); it never resumes | **missing** | In `WisperlowKeyboardService.onUpdateSelection`, when the field changes and `typing.composing.isEmpty()`, call a new `TypingSession.resumeAtCursor()` that re-composes the word around the cursor exactly like `restartSuggestionsOnWordTouchedByCursor` (including `isResumableWord`: first code point is a word code point and not `'`/`-`, `:1819-1824`). |
| 20 | External edit / belated update detection | `isBelatedExpectedUpdate` compares against the expected selection (`RichInputConnection.java:852+`) | `OWN_EDIT_WINDOW_MS = 300L` time heuristic + `candidatesEnd`/`field.before` checks (`:235`, `:240-243`) | **partial** | Keep an explicit `expectedSelection` in `ConnectionField` after each batch and compare in `onUpdateSelection`; the time window races with slow apps (AOSP's whole `hasSlowInputConnection()` machinery exists for this). |
| 21 | Cursor move kills space-state special handling | `mSpaceState = NONE` on any accepted selection change (`:364`) | no state to reset; `undo` cleared only when `word.isEmpty() && (selection or >300 ms)` (`:235-237`) | **partial** | Once (1) lands, reset the space state in `onUpdateSelection` before anything else. |
| 22 | Learn the word being corrected when the cursor moves away | (`:399-404`) | `rewriting` + `learnFix` (`:203`, `:258`, `:276-286`) covers the edit-then-fix path; no learning on cursor-move-away | **partial** | On the reset branch of `onUpdateSelection`, feed `mWordBeingCorrectedByCursor`-equivalent (the `last.typed`/`last.out` pair) into `learnFix` before clearing. |
| 23 | Auto-caps: word/sentence modes | `getCapsMode` steps 1-4 (`:103-189`) | delegated to the framework's `getCursorCapsMode` (`:343`) | **partial** | Behaviour matches for whitespace/newline, but the caps bits come from `ei.inputType` on both sides — email/URL/URI are handled implicitly (AOSP has no helper either). Keep `info.inputType != TYPE_NULL` (`:342`). |
| 24 | Auto-caps: abbreviations (`e.g.`, `i.e.`, `Mr.`) | 5-state machine (`:238-328`) | **not handled** — the framework's `TextUtils.getCapsMode` has no abbreviation logic, so `e.g. ` capitalises | **missing** | Port `CapsModeUtils.getCapsMode` (states `START/WORD/PERIOD/LETTER/NUMBER`, §5.2) and call it with a locally cached `textBeforeCursor` plus `hasSpaceBefore = spaceState == PHANTOM`, mirroring `RichInputConnection.getCursorCapsMode` (`:362-393`). This is the single most visible caps bug. |
| 25 | Auto-caps: `"` / `)` before the terminator | American typography skips closing punctuation and quotes (`:201-212`) | framework-dependent | **missing** | In the ported `getCapsMode`, skip `END_PUNCTUATION` plus `"`/`'` before the terminator for English; keep the German comma-after-newline carve-out (`:159-172`) out unless German is supported. |
| 26 | Auto-caps: after a newline / paragraph start | all three modes (`:146-178`) | framework handles it | **present** | No action (will be covered by the ported function). |
| 27 | Email / URL fields: no auto-caps | inputType mask only (`RichInputConnection.java:392`, `CapsModeUtils.java:118-122`) | `fieldKind` classifies EMAIL/URI (`:713-716`), `applyFieldOptions` keeps suggestions on for them but caps comes from `getCursorCapsMode(info.inputType)` (`:342-343`) | **present** | No action; do **not** add an `isEmailField` special case — AOSP deliberately does not have one. |
| 28 | `CAP_MODE_CHARACTERS` → shift-locked | `getActualCapsMode` (`:1738-1740`) | `updateShift()` only ever sets `ONCE`/`OFF` (`:344`) | **missing** | If `capsMode and CAP_MODE_CHARACTERS != 0`, set `ShiftState.LOCKED` (all-caps field) instead of `ONCE`. |
| 29 | Manual shift beats auto-caps | `if (keyboardShiftMode != CAPS_MODE_AUTO_SHIFTED) return keyboardShiftMode` (`:1734-1736`) | `updateShift` returns early only for `LOCKED`; a manually-set `ONCE` can be cleared by the next `afterEdit()` (`:340`) | **partial** | Track `manualShift: Boolean` set by `toggleShift()` (`:324-335`); skip `updateShift()` while it is set, and clear it on the next character insert — matching `getActualCapsMode`. |
| 30 | Auto-shift ignored while the shift key is held (chording) | `if (!mShiftKeyState.isReleasing()) return` (`KeyboardState.java:472-476`) | `KeyKind.SHIFT` only arrives on tap (`WisperlowKeyboardService.kt:280`) | **partial** | If the panel can report key-down/up, do not change shift state between press and release. |
| 31 | Shift cycling on a selection (recapitalize) | 4 modes rotating on repeat presses at the same selection (`:1391-1422`, `RecapitalizeStatus.java:27-40`); keyboard shows `LOCKED`/`ONCE`/`OFF` per mode (`KeyboardState.java:450-463`) | none | **missing** | Add a `RecapitalizeStatus(selectionStart, selectionEnd, text, modeIndex)`; on shift tap with a selection and no composing word, cycle `mixedCase → allLower → firstWordUpper → allUpper`, `commitText` the new case, restore the selection, and drive `ShiftState` from the mode (upper → `LOCKED`, firstWordUpper → `ONCE`, else `OFF`). Disable the shift double-tap timer while recapitalizing (`KeyboardState.java:487-491`). |
| 32 | Auto-space suppressed before URLs | `!mConnection.textBeforeCursorLooksLikeURL()` (`:2023`) | `SmartSpacing.NO_SPACE_AFTER` contains `/` for dictation (`:6`); typed text has no URL guard | **partial** | Reuse the "before looks like a URL" heuristic in `insertAutomaticSpaceIfOptionsAndTextAllow` once (2) introduces automatic spaces. |
| 33 | Auto-space suppressed where the setting is off | `settingsValues.shouldInsertSpacesAutomatically()` (`:2021`) | `TypingOptions` has no `autoSpace` flag (`:20-25`) | **missing** | Add `autoSpace: Boolean = true` to `TypingOptions`, wire it to a preference, and check it before every automatic (not typed) space insertion. |

### Notable design difference (not a bug)

AOSP deliberately does **not** swap or strip a space that the user typed themselves — the weak-space
machinery only fires for punctuation coming from the suggestion strip
(`tryStripSpaceAndReturnWhetherShouldSwapInstead:1276`, `:1284`). Wisperlow has no strip at all, so
its `HUGGING_PUNCTUATION` delete (`:98-100`) also fires for a space the user just typed
(`"you ,"` → `"you,"`). If the suggestion strip is ever added, port the `isSuggestionStripPress()`
condition rather than keeping the unconditional delete.

---

## 8. Constants quick reference

| Constant | Value | Location |
| --- | --- | --- |
| Double-space period timeout | 1100 ms | `java/res/values/config-common.xml:28` |
| Delete acceleration threshold | 20 | `common/src/com/android/inputmethod/latin/common/Constants.java:182` |
| Sentence separator | `.` (46) | `donottranslate-config-spacing-and-punctuations.xml:38` |
| Abbreviation marker | `.` (46) | `…xml:41` |
| Sentence terminators | `.?!` | `…xml:43` |
| Word connectors | `' -` | `…xml:35` |
| Preceded by space | `([{&<` | `…xml:26` |
| Followed by space | `.,;:!?)]}&<` | `…xml:28` |
| Recapitalize rotation | mixedCase, allLower, firstWordUpper, allUpper | `RecapitalizeStatus.java:35-40` |
| Wisperlow double-space timeout | 450 ms | `TypingSession.kt:324` |
| Wisperlow shift double-tap | 350 ms | `WisperlowKeyboardService.kt:740` |
| Wisperlow own-edit window | 300 ms | `WisperlowKeyboardService.kt:741` |
| Wisperlow word-delete threshold | 20 repeats | `KeyArea.kt:512` |
| Wisperlow `MAX_WORD` | 32 | `TypingSession.kt:325` |