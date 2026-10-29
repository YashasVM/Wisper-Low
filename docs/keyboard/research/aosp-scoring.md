# AOSP LatinIME: suggestion gathering, ordering, autocorrect thresholds and typing scores

Research input for Wisperlow's keyboard engine (`docs/keyboard/ENGINE.md`). Everything below is
read from AOSP LatinIME at commit `127336e9f29d69607eab55982324b210279ae8c5` (2025-02-26,
`platform/packages/inputmethods/LatinIME`, [android.googlesource.com](https://android.googlesource.com/platform/packages/inputmethods/LatinIME)).
Paths and line numbers are relative to that commit. LatinIME is Apache-2.0; quoted fragments
below are short and attributed.

Two different scores exist and must not be confused:

- the **native final score** `calculateFinalScore` — an integer in "suggestion interface" units
  (1,000,000 ≈ 1.0) that ranks candidates (section 2.1);
- the **Java normalized score** `calcNormalizedScore` — a float in ~[0, 1] that the autocorrect
  threshold is compared against (section 1.5).

## 1. Java side

### 1.1 Where the ranked list comes from

`Suggest.getSuggestedWordsForNonBatchInput` (`java/src/com/android/inputmethod/latin/Suggest.java:150-283`):

1. `typedWordString` = `wordComposer.getTypedWord()`; trailing single quotes are stripped into
   `consideredWord` (`Suggest.java:157-163`).
2. `mDictionaryFacilitator.getSuggestionResults(...)` (`Suggest.java:165-168`) returns a
   `SuggestionResults`. That class is a size-bounded `TreeSet` of `SuggestedWordInfo`
   (`java/src/com/android/inputmethod/latin/utils/SuggestionResults.java:35-73`).
   `DictionaryFacilitatorImpl.getSuggestionResults` merges the results of every dictionary in
   `ALL_DICTIONARY_TYPES` with `suggestionResults.addAll(...)`
   (`java/src/com/android/inputmethod/latin/DictionaryFacilitatorImpl.java:616-640`).
3. Capacity is `SuggestedWords.MAX_SUGGESTIONS = 18` (`java/src/com/android/inputmethod/latin/SuggestedWords.java:48`);
   the comparator (`SuggestionResults.java:83-94`) orders by **higher `mScore` first, then
   shorter `mCodePointCount`, then `mWord` lexicographically**.
4. `getTransformedSuggestedWordInfoList` applies the typed word's case to every candidate
   (`Suggest.java:164-167`, defined at `Suggest.java:113`), producing `suggestionsContainer`.
5. Only *after* the autocorrect decision is the typed word inserted **at index 0** with
   `KIND_TYPED` and `SuggestedWordInfo.MAX_SCORE` (`Suggest.java:249-259`;
   `SuggestedWords.java:242`). Therefore the auto-correction slot is index **1**:
   `SuggestedWords.INDEX_OF_AUTO_CORRECTION = 1` (`SuggestedWords.java:35`).

So the strip is: `[0]` = typed word (always highest score), `[1]` = the candidate space may
replace it with, `[2..]` = the rest by score. Kind constants: `KIND_TYPED = 0`,
`KIND_CORRECTION = 1`, `KIND_COMPLETION = 2`, `KIND_WHITELIST = 3`, `KIND_SHORTCUT = 7`
(`SuggestedWords.java:245-252`).

The native side emits every terminal as `KIND_CORRECTION | outputTypeFlags`
(`native/jni/src/suggest/core/result/suggestions_output_utils.cpp:180-182`), so the Java
`KIND` alone does not distinguish corrections from completions; the extra flags do
(`SuggestedWords.java:262`, `suggestions_output_utils.cpp:150-156`).

### 1.2 Whether the typed word counts as valid

`Suggest.java:180-181` de-duplicates and finds where the typed word already sits among the
dictionary candidates (`SuggestedWordInfo.removeDups`, `SuggestedWords.java:389`). Then
(`Suggest.java:278-279`):

```java
final boolean isTypedWordValid = firstOcurrenceOfTypedWordInSuggestions > -1
        || (!resultsArePredictions && !allowsToBeAutoCorrected);
```

The typed literal is treated as a valid word when it is itself a dictionary candidate, or when
autocorrect is disabled for this word anyway. Otherwise it is "invalid" and the strip is allowed
to replace it.

### 1.3 The autocorrect thresholds

`java/res/values/config-auto-correction-thresholds.xml`:

| Mode | Value | Line |
| --- | --- | --- |
| Off | `floatMaxValue` | 25 |
| Modest | `0.185` | 28 |
| Aggressive | `0.067` | 30 |
| Very aggressive | `floatNegativeInfinity` | 34 |
| (allowlist plausibility) | `0.065` | 41 |

`SettingsValues.readAutoCorrectionThreshold` (`java/src/com/android/inputmethod/latin/settings/SettingsValues.java:324-353`)
parses these; out-of-range → `Float.MAX_VALUE`, the two marker strings map to
`Float.MAX_VALUE` / `Float.NEGATIVE_INFINITY`. The raw mode index is chosen at
`SettingsValues.java:153-155`: when the "Auto-correction" switch is on the mode is **modest**,
otherwise **off** — so **0.185 is AOSP's default autocorrect threshold**. The threshold reaches
`Suggest` through `LatinIME.java:738-739` and `LatinIME.java:1051-1052`.

The plausibility threshold (0.065) is read at
`java/src/com/android/inputmethod/latin/settings/Settings.java:223` and is only used to decide
whether an allowlist entry from *another* language may overrule this one
(comment in the XML at lines 37-40). It is not a normal autocorrect gate.

### 1.4 The normalized score the threshold is compared against

`AutoCorrectionUtils.suggestionExceedsThreshold`
(`java/src/com/android/inputmethod/latin/utils/AutoCorrectionUtils.java:32-61`):

```java
if (suggestion.isKindOf(SuggestedWordInfo.KIND_WHITELIST)) return true;        // :35-37
if (!suggestion.isAprapreateForAutoCorrection()) return false;                 // :40-42
final float normalizedScore = BinaryDictionaryUtils.calcNormalizedScore(...);  // :46-47
if (normalizedScore >= threshold) return true;                                 // :56
```

It is native `AutocorrectionThresholdUtils::calcNormalizedScore`
(`native/jni/src/utils/autocorrection_threshold_utils.cpp:70-98`):

```
0                                   if input or suggestion is empty            // :73-77
0                                   if the suggestion is all spaces            // :83-85
0                                   if score <= 0 or distance >= afterLength   // :87-91
weight      = 1 - distance/afterLength                                         // :93
result      = (score / 1000000.0) * weight                                     // :96-97
```

`distance` is the **Damerau-Levenshtein** edit distance between the typed word and the suggestion
(`:32-36`, `:75`). `SUGGEST_INTERFACE_OUTPUT_SCALE = 1000000.0f` (`native/jni/src/defines.h:264`).

Two consequences worth copying:

- A suggestion whose edit distance reaches its own length can never autocorrect (`:87-91`).
- An all-space suggestion can never autocorrect (`:83-85`).

The `KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION` bit tested at `AutoCorrectionUtils.java:40` is set
by the native side only when the match has **no missing explicit accent**
(`suggestions_output_utils.cpp:148`, `:150-156`).

`AutoCorrectionUtils.java:44-45` carries the upstream TODO "when the normalized score of the
first suggestion is nearly equal to the normalized score of the second suggestion, behave less
aggressive" — i.e. **AOSP has the ambiguity intent but never implemented it.**

### 1.5 Every condition that turns autocorrect off

The decision block is `Suggest.java:200-248`.

`allowsToBeAutoCorrected` (`Suggest.java:196-198`):

```java
(SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION || whitelistedWord != null)
    || (consideredWord.length() > 1 && (sourceDictionaryOfRemovedWord == null))
```

`SHOULD_AUTO_CORRECT_USING_NON_WHITE_LISTED_SUGGESTION = false`
(`java/src/com/android/inputmethod/latin/define/DecoderSpecificConstants.java:36`). So the
condition reduces to: *the typed word is on the whitelist*, or *it is longer than one character
and is not itself a dictionary word* (`sourceDictionaryOfRemovedWord` is set when the typed word
was found among the suggestions at `Suggest.java:168-178`).

`hasAutoCorrection = false` when any of these holds (`Suggest.java:203-231`):

1. `!isCorrectionEnabled` — the Auto-correction setting is off, or the input type forbids it.
2. `!allowsToBeAutoCorrected` (see above).
3. `resultsArePredictions` = `!wordComposer.isComposingWord()` (`Suggest.java:192`).
4. `suggestionResults.isEmpty()`.
5. `wordComposer.hasDigits()` (`WordComposer.java:353-355`).
6. `wordComposer.isMostlyCaps()` = more than one uppercase character
   (`WordComposer.java:346-348`).
7. `wordComposer.isResumed()` (`WordComposer.java:412`) — a re-composition of committed text.
8. `!mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary()`.
9. the first suggestion `isKindOf(KIND_SHORTCUT)` (a text expansion).

If it survives, then (`Suggest.java:232-247`):

10. `mFirstSuggestionExceedsConfidenceThreshold && firstOcurrence != 0` → autocorrect. **Dead
    code in this AOSP version**: the flag is hard-coded `false`
    (`DictionaryFacilitatorImpl.java:622-623`).
11. else `AutoCorrectionUtils.suggestionExceedsThreshold(...)` false → no autocorrect.
12. else `isAllowedByAutoCorrectionWithSpaceFilter(firstSuggestion)` — for languages with a
    space limit, a long space-containing suggestion is rejected
    (`Suggest.java:395-408`). German is the only entry, limit **12**
    (`Suggest.java:58`, `:64-65`).

## 2. Native typing engine

### 2.1 The two-stage score

Traversal accumulates a **compound distance** `D` (lower is better) as
`spatial + language * weight`, where the typing weight is 1.0:

- `DicNodeStateScoring::addDistance` (`native/jni/src/suggest/core/dicnode/internal/dic_node_state_scoring.h:206-216`)
- `getCompoundDistance(weight) = mSpatialDistance + mLanguageDistance * weight`
  (`dic_node_state_scoring.h:98-104`); `TypingScoring::getAdjustedWeightOfLangModelVsSpatialModel`
  returns `1.0f` (`native/jni/src/suggest/policyimpl/typing/typing_scoring.h:40-44`).

At a terminal, `D` becomes an integer score
(`typing_scoring.h:45-94`):

```java
maxDistance = DISTANCE_WEIGHT_LANGUAGE + inputSize * TYPING_MAX_OUTPUT_SCORE_PER_INPUT;  // :48-49
score       = TYPING_BASE_OUTPUT_SCORE - compoundDistance / maxDistance;                 // :50
if (forceCommit) score += AUTOCORRECT_OUTPUT_THRESHOLD;                                  // :51-53
// exact-match promotion / case, accent, digraph penalties                              // :70-87
return (int)(score * SUGGEST_INTERFACE_OUTPUT_SCALE);                                    // :93
```

i.e. `score = 1.0 - D / (1.1214 + 0.1 * inputSize)`, then × 1,000,000. `forceCommit` is
`forceCommitMultiWords && hasMultipleWords` (`suggestions_output_utils.cpp:165-170`), and
`AUTOCORRECT_OUTPUT_THRESHOLD = 1.0` (`scoring_params.cpp:24`) — so a multi-word candidate gets a
full +1.0 added, purely to force autocorrection of multi-word splits.

### 2.2 All typing cost constants

All from `native/jni/src/suggest/policyimpl/typing/scoring_params.cpp`:

| Constant | Value | Line |
| --- | --- | --- |
| `MAX_SPATIAL_DISTANCE` | 1.0 | 21 |
| `THRESHOLD_NEXT_WORD_PROBABILITY` | 40 | 22 |
| `THRESHOLD_NEXT_WORD_PROBABILITY_FOR_CAPPED` | 120 | 23 |
| `AUTOCORRECT_OUTPUT_THRESHOLD` | 1.0 | 24 |
| `EXACT_MATCH_PROMOTION` | 1.1 | 26 |
| `PERFECT_MATCH_PROMOTION` | 1.1 | 27 |
| `CASE_ERROR_PENALTY_FOR_EXACT_MATCH` | 0.01 | 28 |
| `ACCENT_ERROR_PENALTY_FOR_EXACT_MATCH` | 0.02 | 29 |
| `DIGRAPH_PENALTY_FOR_EXACT_MATCH` | 0.03 | 30 |
| `MAX_CACHE_DIC_NODE_SIZE` | 170 | 33 |
| `MAX_CACHE_DIC_NODE_SIZE_FOR_SINGLE_POINT` | 310 | 34 |
| `MAX_CACHE_DIC_NODE_SIZE_FOR_LOW_PROBABILITY_LOCALE` | 50 | 35 |
| `THRESHOLD_SHORT_WORD_LENGTH` | 4 | 36 |
| `DISTANCE_WEIGHT_LENGTH` | 0.1524 | 38 |
| `PROXIMITY_COST` | 0.0694 | 39 |
| `FIRST_CHAR_PROXIMITY_COST` | 0.072 | 40 |
| `FIRST_PROXIMITY_COST` | 0.07788 | 41 |
| `INTENTIONAL_OMISSION_COST` | 0.1 | 42 |
| `OMISSION_COST` | 0.467 | 43 |
| `OMISSION_COST_SAME_CHAR` | 0.345 | 44 |
| `OMISSION_COST_FIRST_CHAR` | 0.5256 | 45 |
| `INSERTION_COST` | 0.7248 | 46 |
| `TERMINAL_INSERTION_COST` | 0.8128 | 47 |
| `INSERTION_COST_SAME_CHAR` | 0.5508 | 48 |
| `INSERTION_COST_PROXIMITY_CHAR` | 0.674 | 49 |
| `INSERTION_COST_FIRST_CHAR` | 0.639 | 50 |
| `TRANSPOSITION_COST` | 0.5608 | 51 |
| `SPACE_SUBSTITUTION_COST` | 0.33 | 52 |
| `SPACE_OMISSION_COST` | 0.1 | 53 |
| `ADDITIONAL_PROXIMITY_COST` | 0.37972 | 54 |
| `SUBSTITUTION_COST` | 0.3806 | 55 |
| `COST_SECOND_OR_LATER_WORD_FIRST_CHAR_UPPERCASE` | 0.3224 | 56 |
| `DISTANCE_WEIGHT_LANGUAGE` | 1.1214 | 57 |
| `COST_FIRST_COMPLETION` | 0.4836 | 58 |
| `COST_COMPLETION` | 0.00624 | 59 |
| `HAS_PROXIMITY_TERMINAL_COST` | 0.0683 | 60 |
| `HAS_EDIT_CORRECTION_TERMINAL_COST` | 0.0362 | 61 |
| `HAS_MULTI_WORD_TERMINAL_COST` | 0.3482 | 62 |
| `TYPING_BASE_OUTPUT_SCORE` | 1.0 | 63 |
| `TYPING_MAX_OUTPUT_SCORE_PER_INPUT` | 0.1 | 64 |
| `NORMALIZED_SPATIAL_DISTANCE_THRESHOLD_FOR_EDIT` | 0.095 | 65 |
| `LOCALE_WEIGHT_THRESHOLD_FOR_SPACE_SUBSTITUTION` | 0.99 | 66 |
| `LOCALE_WEIGHT_THRESHOLD_FOR_SPACE_OMISSION` | 0.99 | 67 |
| `LOCALE_WEIGHT_THRESHOLD_FOR_SMALL_CACHE_SIZE` | 0.99 | 68 |

Error-cost ranking, most to least punishing: terminal insertion 0.8128, insertion 0.7248,
transposition 0.5608, omission 0.467, substitution 0.3806, space substitution 0.33,
space omission 0.1. Insertions are ~1.55× an omission; a transposition is cheaper than an
insertion but dearer than a substitution.

### 2.3 Touch distance and sweet spots

The spatial cost of a matched character is `getMatchedCost`
(`native/jni/src/suggest/policyimpl/typing/typing_weighting.h:73-104`):

```
d2      = pointToKeyLength(inputIndex, nodeChar)             // :75-77
factor  = getSweetSpotFactor(enabled, d2)                    // :78-79
weighted = DISTANCE_WEIGHT_LENGTH * factor                   // :80  (0.1524 * factor)
cost    = isProximity ? (firstChar ? FIRST_CHAR_PROXIMITY_COST : PROXIMITY_COST) : 0   // :82-83
if (isProximity && proximityCorrectionCount == 0) cost += FIRST_PROXIMITY_COST        // :84-86
if (nodeCodePointCount == 2 && multiWord && firstCharUppercase) cost += 0.3224        // :87-95
return weighted + cost                                        // :96
```

`isProximityDicNode` compares the key's primary code point with the dictionary character
(`typing_weighting.h:105-113`), so an exact hit costs 0 and a hit on a neighbouring key costs the
proximity surcharge plus the distance term.

The distance itself (`native/jni/src/suggest/core/layout/proximity_info.cpp`, `getNormalizedSquaredDistanceFromCenterFloatG`):

```
d2 = squaredDistance(sweetSpotCentre(keyId), touch) / mostCommonKeyWidth²
```

The centre (`proximity_info.cpp:195-240`, `getKeyCenterXOfKeyIdG` / `getKeyCenterYOfKeyIdG`):

- X = `mSweetSpotCenterXs[keyId]` when touch-position-correction data exists, else the geometric centre;
- Y = `mSweetSpotCenterYs[keyId]` when the data exists (the `isGeometric=false` typing branch),
  else the geometric centre; bottom-row keys are extended to the keyboard edge when close to it.

Those sweet-spot centres come from `java/src/com/android/inputmethod/keyboard/ProximityInfo.java:182-203`:

```
sweetSpotCentreX = hitBox.exactCenterX + correctionX(row) * hitBox.width
sweetSpotCentreY = hitBox.exactCenterY + correctionY(row) * hitBox.height
sweetSpotRadius  = correctionR(row) * hypot(hitBox.width, hitBox.height)
```

with default radius `DEFAULT_TOUCH_POSITION_CORRECTION_RADIUS = 0.15` × the key diagonal
(`ProximityInfo.java:43`, `:181`). The per-row data is
`java/res/values/touch-position-correction.xml:40-56`:

| Row | correctionX | correctionY | correctionR |
| --- | --- | --- | --- |
| 1 | 0.0038756 | -0.0005677 | 0.1577026 |
| 2 | -0.0236678 | 0.0381731 | 0.1529972 |
| 3 | -0.0086827 | 0.0880847 | 0.1522819 |

Note: **both** X and Y corrections are applied in `ProximityInfo.java:198-200`. The per-row
`correctionR` is *not* used in the typing distance — `getNormalizedSquaredDistanceFromCenterFloatG`
divides by `mostCommonKeyWidth²` only; the radii matter to the gesture/proximity machinery.

`sweetSpotFactor` (`native/jni/src/suggest/core/layout/touch_position_correction_utils.h:28-56`)
is a piecewise-linear function of `d2` with `A = 0.0`, `B = 0.24`, `C = 1.20`,
`R0 = 0.0`, `R1 = 0.25`, `R2 = 1.0` (`:30-34`): 0 at the centre, rising to 0.24 by `R1`, then to
1.20 at `R2`, and capped at 1.20 beyond. `d2` is itself capped at `MAX_SPATIAL_DISTANCE = 1.0`
(`proximity_info_state.cpp:169-181`; `scoring_params.cpp:21`). When touch-position correction is
disabled the factor is `min(C, d2)` (`:38-40`).

So a full-key-away mis-hit contributes `0.1524 * 1.20 = 0.18288` of distance, plus at least
`0.0694 + 0.07788 = 0.14728` of proximity cost → **~0.33016**.

### 2.4 How language and spatial evidence are combined

The language model is **not** applied per character. `Weighting::getLanguageCost`
(`native/jni/src/suggest/core/policy/weighting.cpp:142-178`) returns `0.0f` for match, omission,
substitution, insertion, transposition and completion, and only computes a real value for
`CT_TERMINAL` (`:158-163`) and the two new-word space corrections (`:154-156`, `:166-168`).

At the terminal (`weighting.cpp:158-163`):

```
improbability = DicNodeUtils::getBigramNodeImprobability(...)      // (255 - probability) / 255
languageCost  = improbability * DISTANCE_WEIGHT_LANGUAGE           // * 1.1214
```

`getBigramNodeImprobability` (`native/jni/src/suggest/core/dicnode/dic_node_utils.cpp:69-89`)
returns `(MAX_PROBABILITY - probability) / MAX_PROBABILITY` with
`MAX_PROBABILITY = 255` (`defines.h:265`), using the bigram when a context is available and the
unigram otherwise; invalid, blacklisted or not-a-word multi-word nodes get
`MAX_VALUE_FOR_WEIGHTING = 10000000` (`dic_node_utils.cpp:74-79`; `defines.h:270`).
`getTerminalLanguageCost` multiplies by `DISTANCE_WEIGHT_LANGUAGE = 1.1214`
(`typing_weighting.h:178-181`).

The terminal *spatial* surcharge (`typing_weighting.h:40-53`):

```
0.3482 if the node has multiple words
+ 0.0683 if any proximity correction happened
+ 0.0362 if any edit correction happened
```

So the whole per-word budget is: one distance-weighted spatial term per character, plus one
terminal surcharge, plus one language term. For an `n`-character word the denominator is
`1.1214 + 0.1n`.

### 2.5 Terminal insertion and completion

- If the traversal has not consumed all input when it reaches a terminal, a terminal insertion
  cost of `TERMINAL_INSERTION_COST * (inputSize - inputIndex)` is added
  (`typing_weighting.h:183-189`; applied at `suggest.cpp:245-249`).
- Completions cost `COST_FIRST_COMPLETION = 0.4836` when the input index equals the input size,
  and `COST_COMPLETION = 0.00624` after that (`typing_weighting.h:167-176`) — the first
  completion is a one-off brake, later ones are almost free.

### 2.6 Beam and queue sizes

| What | Size | Where |
| --- | --- | --- |
| Traversal node cache, normal | 170 | `scoring_params.cpp:33` |
| Traversal node cache, single-point input | 310 | `scoring_params.cpp:34` |
| Traversal node cache, low-probability locale | 50 | `scoring_params.cpp:35` |
| Priority-queue pool, large dictionary | 310 | `dic_nodes_cache.cpp:27` |
| Priority-queue pool, small dictionary | 100 | `dic_nodes_cache.cpp:29` |
| Terminal queue | `MAX_RESULTS = 18` | `defines.h:36`, `dic_nodes_cache.h:39`, `typing_traversal.h:173-175` |

`TypingTraversal::getMaxCacheSize` (`typing_traversal.h:165-171`) picks 310 for `inputSize <= 1`,
50 when the locale weight is below `LOCALE_WEIGHT_THRESHOLD_FOR_SMALL_CACHE_SIZE = 0.99`, else
170. The large/small pool choice is made on dictionary size:
`DICTIONARY_SIZE_THRESHOLD_TO_USE_LARGE_CACHE_FOR_SUGGESTION = 256 * 1024`
(`dic_traverse_session.cpp:28-30`, used at `dic_traverse_session.h:44-47`).

Node ordering in the priority queue is `DicNode::compare`
(`native/jni/src/suggest/core/dicnode/dic_node.h:415-447`): exact matches first, then smaller
normalized compound distance (epsilon `0.000001`), then greater depth, then lexicographically
smaller code points, then pointer value for stability.

Java-side the terminal results are also capped at 18 (`SuggestedWords.java:48`), so the strip
never sees more than 18 candidates.

### 2.7 What bounds the errors

**There is no maximum-error or maximum-edit-count constant anywhere in LatinIME.** A grep for
edit/error budget names finds nothing; the counters are unbounded `int16_t`s
(`dic_node_state_scoring.h:71-86`, fields at `:194-196`) used only for the terminal surcharges.
What actually bounds corrections:

1. **The spatial edit gate.** `TypingTraversal::allowsErrorCorrections` returns
   `dicNode->getNormalizedSpatialDistance() < NORMALIZED_SPATIAL_DISTANCE_THRESHOLD_FOR_EDIT`
   = 0.095 (`typing_traversal.h:42-44`), where
   `getNormalizedSpatialDistance = mSpatialDistance / (getInputIndex(0) + 1)`
   (`dic_node.h:287-290`) — the *accumulated* spatial cost per consumed input character. If the
   user's touches are close to the intended keys this stays small and edits are allowed; if they
   are far off, no corrections at all are considered.
2. Match and proximity-char moves are always allowed; `ADDITIONAL_PROXIMITY_CHAR` and
   `SUBSTITUTION_CHAR` only when the gate passes; `UNRELATED_CHAR` is dropped
   (`suggest.cpp:180-199`). Omissions likewise need the gate or an intentional omission
   (`typing_traversal.h:45-66`; `CORRECT_OMISSION = true`, `typing_traversal.cpp:20`).
3. **Look-ahead is limited to the last input point.** `canDoLookAheadCorrection` is
   `inputIndex(0) < inputSize - 1` (`dic_node.h:196-198`), so insertions and transpositions are
   only generated while at least one touch point remains after the current one
   (`suggest.cpp:132-142`, `:222-224`).
4. **Output length cap.** `isTotalInputSizeExceedingLimit` aborts a branch once
   `totalCodePointCount > MAX_WORD_LENGTH - 3 = 45` (`dic_node.h:237-240`;
   `MAX_WORD_LENGTH = 48` at `defines.h:34`; checked at `suggest.cpp:123-125`).
5. **Cost sentinels.** A node whose compound distance reaches `MAX_VALUE_FOR_WEIGHTING`
   (10,000,000) is dropped (`suggest.cpp:229-233`, `:262`).
6. **Terminal filters.** Not a terminal / failed the bigram safety net / no matched-or-proximity
   code point → not output (`suggest.cpp:234-242`).
7. **Beam pruning** by the cache sizes above, and the `MAX_RESULTS` terminal queue.

## 3. Worked example: "thw" → "the"

Input `thw` (3 characters), the user's third touch lands on the `w` key while the intended
character is `e`. `MAX_POINTER_COUNT = 1` for typing (`defines.h:276`), so `inputSize = 3`.

**Spatial distance** (`typing_weighting.h:73-104`):

- `t` and `h`: exact hits → `isProximity` false, factor 0 → cost 0 each.
- third character: dictionary char `e`, touch primary `w` → `isProximity` true. Not the first
  pointer index, so `PROXIMITY_COST = 0.0694`; the node has no earlier proximity correction, so
  `FIRST_PROXIMITY_COST = 0.07788` is added. The distance term is
  `0.1524 * sweetSpotFactor(d2)`. With the touch on the neighbouring key, `d2 >= R2 = 1.0`, so
  the factor saturates at `C = 1.20`:

```
0.1524 * 1.20 + 0.0694 + 0.07788 = 0.18288 + 0.14728 = 0.33016
```

**Terminal spatial** (`typing_weighting.h:40-53`): single word (no 0.3482), one proximity
correction (+0.0683), no edit correction → `0.0683`.

**Language** (`weighting.cpp:158-163`, `dic_node_utils.cpp:69-89`): with unigram/bigram
probability `p = 200` for "the",

```
(255 - 200) / 255 = 0.215686  →  * 1.1214 = 0.241871
```

**Compound distance** `D = 0.33016 + 0.0683 + 0.241871 = 0.640331`.

**Final score** (`typing_scoring.h:45-94`):

```
maxDistance = 1.1214 + 3 * 0.1                     = 1.4214
score       = 1.0 - 0.640331 / 1.4214              = 0.549505
finalScore  = (int)(0.549505 * 1e6)                = 549505
```

**Java normalized score** (`autocorrection_threshold_utils.cpp:70-98`): Damerau-Levenshtein
distance between "thw" and "the" is 1, `afterLength = 3`:

```
weight         = 1 - 1/3                    = 0.666667
normalizedScore = (549505/1e6) * 0.666667   = 0.366337
```

`0.366337 >= 0.185` (modest) → `suggestionExceedsThreshold` returns true, so spacebar
auto-corrects to "the". The other gates (`Suggest.java:203-231`) all pass: correction enabled,
`consideredWord.length() > 1` and "thw" is not a dictionary word so
`allowsToBeAutoCorrected` is true, not a prediction, results non-empty, no digits, not mostly
caps, not resumed, main dictionary present, first suggestion is `KIND_CORRECTION` not
`KIND_SHORTCUT`, German space filter not applicable.

**Sensitivity.** The decision is genuinely marginal and depends on the probability of "the":

| `p` for "the" | language term | `D` | final score | normalized score | vs 0.185 |
| --- | --- | --- | --- | --- | --- |
| 230 | 0.110 | 0.5085 | 0.642 | 0.428 | passes |
| 200 | 0.2419 | 0.6403 | 0.5495 | 0.366 | passes |
| 128 | 0.5585 | 0.9570 | 0.3268 | 0.218 | passes |
| 64 | 0.8400 | 1.2384 | 0.1287 | 0.086 | **fails** |

**The path matters.** Whether `w`→`e` is a `PROXIMITY_CHAR` (→ `CT_MATCH`, the case above) or a
`SUBSTITUTION_CHAR` (→ `CT_SUBSTITUTION` = `SUBSTITUTION_COST + getMatchedCost`, `weighting.cpp:113-118`)
decides the outcome. On the substitution path the node also accrues an edit correction, so the
terminal surcharge becomes `0.0683 + 0.0362 = 0.1045`:

```
D = (0.3806 + 0.33016) + 0.1045 + 0.241871 = 1.057131
score = 1 - 1.057131/1.4214                = 0.256270
normalizedScore = 0.256270 * 0.666667      = 0.170847  <  0.185  →  no autocorrect
```

So the same "wrong key" can be corrected or not purely on whether the touch fell inside AOSP's
proximity zone for that key.

## 4. What Wisperlow should take

Wisperlow's contract (`android/keyboard-core/src/main/kotlin/com/wisperlow/mobile/keyboard/decoder/Decoding.kt:22-43`,
`docs/keyboard/ENGINE.md:23-36`) uses natural-log scores, higher is better, with
`UnigramModel.UNKNOWN_LOG_PROB = -20`. `Candidate.score` = spatial evidence + weighted LM + edit
penalties; `SuggestionPolicy` decides replacement from *margins* between candidates rather than
from an absolute normalized score (`SuggestionPolicy.kt:33-48`, `:105-116`). That design is
already closer to the right thing than AOSP's, so the recommendation is to copy AOSP's
*structure and costs*, not its absolute units.

**Do not port AOSP's absolute cost constants into Wisperlow's ln space.** AOSP's spatial term is
a bounded `sweetSpotFactor ∈ [0, 1.2]` multiplied by 0.1524, whereas Wisperlow's `SpatialModel`
already returns a calibrated `ln P(touch | key)` from a 2D Gaussian plus a uniform floor
(`spatial/GaussianSpatialModel.kt:81-97`, `spatial/SpatialModel.kt:12-18`). Adding AOSP's
`0.33016` on top of a 4-nat Gaussian log-likelihood difference would double-count the spatial
evidence.

### 4.1 `DecoderParams` (new, proposed)

Put a `DecoderParams` data class in `decoder/` next to `SuggestionPolicy`/`KeyResolver`,
mirroring `ScoringParams`. It should carry only what the probabilistic spatial model does *not*
already encode: the **edit-type priors**. AOSP's costs are a good source of the *ratios*; map a
cost `c` to a prior `exp(-k·c)` with `k = 4`, which turns AOSP's costs into plausible
per-opportunity edit priors while preserving their ratios exactly:

| Field | AOSP source | Value (nats) |
| --- | --- | --- |
| `substitutionCost` | 0.3806 | 1.52 |
| `omissionCost` | 0.467 | 1.87 |
| `omissionSameCharCost` | 0.345 | 1.38 |
| `omissionFirstCharCost` | 0.5256 | 2.10 |
| `intentionalOmissionCost` | 0.1 | 0.40 |
| `transpositionCost` | 0.5608 | 2.24 |
| `insertionCost` | 0.7248 | 2.90 |
| `insertionSameCharCost` | 0.5508 | 2.20 |
| `insertionAdjacentCost` | 0.674 | 2.70 |
| `insertionFirstCharCost` | 0.639 | 2.56 |
| `terminalInsertionCost` | 0.8128 | 3.25 |
| `spaceOmissionCost` | 0.1 | 0.40 |
| `spaceSubstitutionCost` | 0.33 | 1.32 |
| `firstCompletionCost` | 0.4836 | 1.93 |
| `completionCost` | 0.00624 | 0.025 |
| `lmWeight` | `DISTANCE_WEIGHT_LANGUAGE` 1.1214 | 1.0 |
| `maxInputLength` | `MAX_WORD_LENGTH` 48 | 48 |
| `maxCandidates` | `MAX_RESULTS` 18 | 18 |

Notes: AOSP's *terminal surcharges* (`HAS_PROXIMITY_TERMINAL_COST` 0.0683,
`HAS_EDIT_CORRECTION_TERMINAL_COST` 0.0362, `HAS_MULTI_WORD_TERMINAL_COST` 0.3482) should be
dropped — they exist to compensate for AOSP's non-probabilistic spatial measure. AOSP's
`k`-scaled priors are a *starting point*; tune them against a behavioural test set, not by
targeting AOSP's numbers.

### 4.2 `PolicyParams` (proposed changes)

The existing two-tier literal split (`unknownLiteral*` vs `knownLiteral*`,
`SuggestionPolicy.kt:41-45`) is exactly AOSP's structure and should stay.

| Parameter | Now | Proposed | Anchor |
| --- | --- | --- | --- |
| `unknownLiteralMargin` (MODEST) | 1.5 | **keep 1.5** | AOSP modest:aggressive ratio 0.185:0.067 = 2.76; Wisperlow's 1.5:0.5 = 3.0, close enough |
| `unknownLiteralAggressiveMargin` | 0.5 | **keep 0.5** | as above |
| `knownLiteralMargin` (MODEST) | 7.0 | **`Float.POSITIVE_INFINITY`** | AOSP default never auto-corrects a typed dictionary word: `Suggest.java:196-198` + `DecoderSpecificConstants.java:36` |
| `knownLiteralAggressiveMargin` | 5.0 | **keep 5.0** | stands in for AOSP's "off the invisible-ladder" very-aggressive mode |
| `shortWordExtraMargin` | 1.0, only `length == 3` | **derived, see below** | AOSP's edit weight `1 - d/L` (`autocorrection_threshold_utils.cpp:93`) |
| `ambiguityMargin` | 0.7 | **keep 0.7** | AOSP has the intent but not the code (`AutoCorrectionUtils.java:44-45`) |
| `completionBlockMargin` | 1.0 | **1.9** | AOSP `COST_FIRST_COMPLETION = 0.4836` → 1.93 nats at k = 4 |
| `taughtMargin` | 3.0 | **keep 3.0** | AOSP is *looser* here (whitelisted suggestions pass unconditionally, `AutoCorrectionUtils.java:36-37`); 3.0 is deliberately conservative |

**Derived short-word margin.** Replace the flat `shortWordExtraMargin` with the natural-log form
of AOSP's edit-distance weight. AOSP multiplies the candidate's score by `1 - d/L`, so the implied
penalty on the candidate is `-ln(1 - d/L) = ln(L / (L - d))`. For one edit:

```
L = 3  →  ln(3/2) = 0.405
L = 4  →  ln(4/3) = 0.288
L = 5  →  ln(5/4) = 0.223
```

Recommendation: in `requiredMargin` (`SuggestionPolicy.kt:126-133`) add
`candidate.edits * ln(len / (len - candidate.edits))` for every candidate with `edits > 0`,
instead of a fixed `+1.0` at `length == 3`. `Candidate.edits` already exists
(`Decoding.kt:35-36`). This automatically makes a 2-edit correction of a 3-letter word need
`ln(3/1) = 1.10` extra, and preserves the current protection for 3-letter words while extending
it to 4- and 5-letter ones with the correct, decaying weight.

### 4.3 Hard blocks to add to `SuggestionPolicy`

These AOSP conditions are not yet covered by `isProtected` / `mayReplace`
(`SuggestionPolicy.kt:105-138`) and cost nothing to add:

- **`edits >= candidate.word.length` → never replace** (`autocorrection_threshold_utils.cpp:87-91`).
- **A candidate whose word is all spaces → never** (`:83-85`).
- **A candidate containing a space longer than a per-language cap** — German is 12
  (`Suggest.java:58`, `:64-65`, `:395-408`); use `Int.MAX_VALUE` elsewhere.
- **A candidate that is a text expansion / shortcut → never** (`Suggest.java:230`); in
  Wisperlow terms, a candidate not present in the vocabulary.
- **Typed word is mostly caps → never**: AOSP blocks at more than one uppercase character
  (`WordComposer.java:346-348`). `isProtected` currently only blocks *inner* capitals, so
  "HELLO" is correctable today; add `typed.count { it.isUpperCase() } > 1` → block.
- **Typed word is a resumed/re-composed run** → never (`Suggest.java:218`); the existing
  `TypedKey.touch == null` case in `Decoding.kt:6` is the hook.
- **No initialised main dictionary** → never (`Suggest.java:227`); skip the whole decision when
  the vocabulary is empty.

Two AOSP mechanisms are *already* represented and need no work: digits and inner capitals
(AOSP `hasDigits()` at `Suggest.java:212`, Wisperlow `isProtected`), and the ambiguity guard
(AOSP has only the TODO).

### 4.4 Deliberately not ported

- The bounded `sweetSpotFactor` curve and the `0.1524 × factor` spatial weight — superseded by
  `GaussianSpatialModel`'s `ln P(touch | key)` (`spatial/GaussianSpatialModel.kt:81-97`).
- The `0.095` normalized-spatial edit gate (`typing_traversal.h:42-44`). It is cheap in AOSP
  because AOSP's spatial cost is a bounded, hand-scaled quantity; reproducing it with a Gaussian
  means tracking a per-input-character mean log-likelihood. If Wisperlow wants the same effect,
  the equivalent is "the mean `ln P(touch | landed key)` of the three most recent taps is within
  X nats of the best key's", but it should be validated before adopting.
- The `+1.0` `AUTOCORRECT_OUTPUT_THRESHOLD` force-commit promotion (`scoring_params.cpp:24`) —
  only used for multi-word splits.
- `mFirstSuggestionExceedsConfidenceThreshold` (`Suggest.java:234-236`) — dead upstream
  (`DictionaryFacilitatorImpl.java:622-623`).
- `MAX_VALUE_FOR_WEIGHTING` as a sentinel — use `Float.NEGATIVE_INFINITY` and drop candidates.
- The whitelist short-circuit (`AutoCorrectionUtils.java:36-37`) — Wisperlow's `taughtMargin`
  and `TypedFacts.userTrusted` (`SuggestionPolicy.kt:24-30`) express the same idea more carefully.

### 4.5 Summary of the concrete numbers

- Default modest threshold: **0.185**; aggressive **0.067**; off `Float.MAX_VALUE`; very
  aggressive `-Inf`; plausibility **0.065** (`config-auto-correction-thresholds.xml`).
- `DecoderParams` edit priors: substitution **1.52**, omission **1.87**, transposition **2.24**,
  insertion **2.90**, terminal insertion **3.25**, space omission **0.40**,
  first completion **1.93**, later completion **0.025** nats; `lmWeight` **1.0**;
  `maxInputLength` **48**; `maxCandidates` **18**.
- `PolicyParams`: keep `unknownLiteralMargin` 1.5 / 0.5, `ambiguityMargin` 0.7,
  `taughtMargin` 3.0; raise `knownLiteralMargin` (MODEST) to infinity;
  set `completionBlockMargin` 1.9; replace `shortWordExtraMargin` with
  `edits * ln(L/(L - edits))` (0.405 at L = 3, one edit).
