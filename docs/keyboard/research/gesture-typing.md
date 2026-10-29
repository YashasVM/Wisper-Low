# Gesture (swipe) typing: reference research and review of our `GestureDecoder`

Research for wiring up `android/keyboard-core/.../gesture/GestureDecoder.kt` (SHARK2-style
trie walk + spatial/LM scoring). Covers FlorisBoard's glide typing, the original SHARK2
system, Gboard's LSTM decoder, the UX surface, and concrete changes for us. No code was
built or run; everything below is from source reading (clone in `/tmp/rs-d2/`).

## 1. FlorisBoard glide typing (Apache-2.0)

Source: <https://github.com/florisboard/florisboard> (Apache-2.0). **Note the state of the
upstream code:** the classifier is active through tag `v0.4.6` (and byte-identical in
`v0.5.2`), but on `master` (v0.6 development) the whole
`StatisticalGlideTypingClassifier` is wrapped in a block comment, `GlideTypingManager`
holds `= Unit`, and the settings screen says glide typing "will be re-implemented from the
ground up". So cite/tag `v0.4.6` when reading; master is a graveyard.

- Classifier: [`v0.4.6` StatisticalGlideTypingClassifier.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/StatisticalGlideTypingClassifier.kt)
- Gesture detection: [`v0.4.6` GlideTypingGesture.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingGesture.kt)
- Suggestion/commit plumbing: [`v0.4.6` GlideTypingManager.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/gestures/GlideTypingManager.kt)
- Trail rendering + touch arbitration: [`v0.4.6` TextKeyboardLayout.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardLayout.kt)
- Prefs: [`v0.4.6` AppPrefs.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/app/AppPrefs.kt)
- Auto-space / backspace-undo: [`v0.4.6` EditorInstance.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/EditorInstance.kt),
  [`v0.4.6` KeyboardManager.kt](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyboardManager.kt)

FlorisBoard's algorithm is a direct port of Étienne Desticourt's AnySoftKeyboard
implementation, whose write-up lives in the PR that introduced it:
<https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870> (see the added
`gesturetyping/README.md` in the diff). The constants `SHAPE_STD = 22.08` and
`LOCATION_STD = 0.5109` were fitted on ASK's recorded gesture training set and carried
over unchanged.

### 1.1 Algorithm pipeline

1. **Gesture vs tap detection** (`GlideTypingGesture.Detector`, lines 42–135): a pointer
   becomes a glide only when it has travelled more than one key width **and** faster than
   `VELOCITY_THRESHOLD = 0.10 dp/ms`, evaluated within `MAX_DETECT_TIME = 500 ms`; a
   touch that stays slow for 500 ms is permanently a tap. Glides never start on
   `DELETE`, `SHIFT`, or `SPACE` keys. Once gliding, all active key presses are cancelled
   (no character is emitted mid-swipe), and pointer history samples are consumed.
2. **Point decimation** (`addGesturePoint`, line 103): points closer than `keyWidth / 4`
   to the previous point are dropped before entering the gesture buffer.
3. **Pruning** (`Pruner`, lines 288–432), two stages:
   - `pruneByExtremities`: a hash tree maps `(firstKey, lastKey)` → words. The 2 nearest
     keys to the gesture start and the 2 nearest to the end give 4 start×end pairs to
     look up. Specificity > 99% per the ASK write-up (400k-word dictionary → ~4k left),
     O(1).
   - `pruneByLength`: keep words whose ideal-gesture length is within
     `PRUNING_LENGTH_THRESHOLD = 8.42` × key size of the user gesture's raw length.
     Ideal lengths are memoized per word.
4. **Ideal gestures** (`Gesture.generateIdealGestures`, lines 443–508): polyline through
   the centre of every letter key; accented letters fall back to the NFD base character.
   For doubled letters it produces **two variants**: the plain polyline, and one with a
   small loop around the key (4 corner points at ±key/4) so "pool" ≠ "poll" ≠ "lull";
   both are scored and the better one wins.
5. **Resampling and normalization**: both user and ideal gesture are resampled to
   `SAMPLING_POINTS = 200` equidistant points (oversampling walk with cumulative
   rounding-error compensation), then shape-normalized by dividing by the **longest side
   of the bounding box** and translating the bbox centre to the origin
   (`normalizeByBoxSide`, lines 584–613).
6. **Scoring** (`unCachedGetSuggestions`, lines 198–248), per surviving word × per ideal
   variant:
   - shape distance = **summed** Euclidean point-to-point distance over the 200 points of
     the *normalized* gestures → Gaussian with `SHAPE_STD = 22.08` (its unit is
     "sum over 200 points in unit-longest-side space", i.e. ≈ 0.11 per-point mean);
   - location distance = **mean Manhattan** distance /2 over the *un-normalized*
     gestures → Gaussian with `LOCATION_STD = 0.5109` × key size (the code's "radius" is
     the full min(key height, key width), not a half-width);
   - word frequency from the NLP manager, range-scaled to `255f`;
   - `confidence = 1 / (shapeProb × locationProb × frequency)` (lower = better; an
     insertion-sorted top-N list keeps the best `maxSuggestionCount`, deduplicating words
     reached via multiple ideal gestures).
7. **Caching**: an LRU of 5 suggestion lists keyed on (gesture, count), so the release
   callback reuses the last live-preview computation; the Pruner is LRU-cached per
   subtype.

### 1.2 Constants (FlorisBoard v0.4.6 / AnySoftKeyboard #1870)

| Constant | Value | Meaning |
|---|---|---|
| `SAMPLING_POINTS` | 200 (Floris) / 300 (ASK) | equidistant resample count |
| `PRUNING_LENGTH_THRESHOLD` | 8.42 | max |userLen − idealLen| as a factor of key size (passed as a bare literal in ASK PR #1870) |
| `SHAPE_STD` | 22.08 | std of summed normalized shape distance, fitted on ASK training data |
| `LOCATION_STD` | 0.5109 | std of mean location distance, in key-size units |
| extremity pruning | 2 nearest start keys × 2 nearest end keys | > 99% specificity |
| point decimation | keyWidth/4 | min distance between stored gesture points |
| glide detection | > 1 key width, > 0.10 dp/ms, within 500 ms | gesture vs tap |
| suggestion cache / pruner cache | 5 / 5 | LRU sizes |
| `MAX_SUGGESTION_COUNT` | 8 | candidates fetched; top-1 committed |

### 1.3 Trail rendering ([TextKeyboardLayout.kt:192–213, 938–1010](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/text/keyboard/TextKeyboardLayout.kt))

- Points are stored as `(Position, timestamp)` pairs while gliding.
- The trail is a chain of filled circles drawn from the finger backwards: interpolated
  every `targetDist = 3 px`, starting at head `radius = 20 px`, each step multiplied by
  `radiusReductionFactor = 0.99` (exponential taper).
- Points older than `trailDuration` (default **200 ms**) are dropped at draw time, so the
  tail self-erases while you glide.
- On lift/cancel, the live point list is copied to `fadingGlide` and a
  `ValueAnimator.ofFloat(20f, 0f)` with `AccelerateInterpolator` over `trailDuration`
  shrinks `fadingGlideRadius` to zero. Colour comes from the theme slot `GlideTrail`.

### 1.4 UX behaviours

- **Live preview**: while gliding, `GlideTypingManager.onGlideAddPoint` re-decodes at most
  every `previewRefreshDelay` (default **150 ms**) with `maxSuggestionsToShow = 1` and
  pushes the top-1 into the suggestion strip (`nlpManager.suggestDirectly`); the strip is
  what shows the "current guess", not a floating bubble.
- **Commit**: on lift, fetch 8 candidates, `keyboardManager.commitGesture(suggestions.first())`,
  remaining 7 in the strip. A 5-entry LRU avoids recomputing the same gesture on release.
- **Capitalization**: `KeyboardManager.fixCase` — `CAPS_LOCK` → uppercase,
  `SHIFTED_MANUAL/AUTOMATIC` → titlecase, else lowercase ([KeyboardManager.kt:298–319](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/KeyboardManager.kt)).
- **Auto-space ("phantom space")**: `EditorInstance.commitGesture` asks the
  `PhantomSpaceState` whether a space is needed based on the character before the cursor
  (ignoring the current composing word), commits `" word"` or `"word"`, and leaves the
  result *composing* so the next gesture replaces it ([EditorInstance.kt:284–299](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/EditorInstance.kt)).
  A second gesture while phantom space is active commits `nextword` without a space and
  resolves the boundary afterwards — consecutive swipes chain correctly.
- **Backspace right after a gesture**: `deleteBackwards()` checks `phantomSpace.isActive`
  + `prefs.glide.immediateBackspaceDeletesWord` (default **true**) and upgrades a single
  backspace into `deleteWordBackwards()` — one backspace undoes the whole gestured word
  ([EditorInstance.kt:349–356](https://github.com/florisboard/florisboard/blob/v0.4.6/app/src/main/kotlin/dev/patrickgold/florisboard/ime/editor/EditorInstance.kt)).
- **Backspace swipe** (separate feature, `SwipeGesture`): swiping left on the delete key
  deletes words (configurable `SwipeAction.DELETE_WORD_PRECISELY` etc.), and space-bar
  swipes move the cursor — not part of the glide classifier but part of the same touch
  arbitration, which is why glides are forbidden from starting on those keys.

## 2. SHARK2 (Kristensson & Zhai, UIST 2004)

Per-Ola Kristensson & Shumin Zhai, *SHARK2: a large vocabulary shorthand writing system
for pen-based computers*, UIST '04, pp. 43–52.
<https://doi.org/10.1145/1029632.1029640> · PDF: <http://pokristensson.com/pubs/KristenssonZhaiUIST2004.pdf>
· retrospective: Zhai & Kristensson, *The word-gesture keyboard*, CACM 2012,
<https://pokristensson.com/pubs/ZhaiKristenssonCACM2012.pdf>.

Architecture (paper §"The architecture and algorithms of the gesture recognition engine"):

1. **Template pruning**: compare start-to-start and end-to-end distances between the
   normalized input gesture and each template; discard if either exceeds a threshold.
   Implemented as a linked list of (start, end) coordinates — O(1)-ish traversal even for
   20k templates. The paper quantifies why ends matter (Table 1, 20k lexicon, ATOMIK
   layout): shape alone leaves **1117** identical-shape confusion pairs; +start key → 519;
   +end key → 522; +both ends → 493 (58% of which are Roman numerals). QWERTY is worse
   (1461 / 609 / 589 / 537).
2. **Shape channel** ("proportional matching", Eq. 1): elastic matching hurt in crowded
   template spaces, so they use a strictly linear matcher — resample both patterns to N
   equidistant points, normalize scale (longest bounding-box side → fixed length L, Eq. 2)
   and translate the centroid to the origin, then take the mean point-to-point distance.
   Exactly the metric FlorisBoard (and our `shapeDistance`) inherits.
3. **Location channel** (Eqs. 3–6): the ideal template is the polyline connecting letter
   centres; distance is a point-wise sum where every point within **one key radius of the
   template polyline gets distance zero** (an invisible "tunnel" of one key width around
   the word's path), and only points outside the tunnel contribute. Points are weighted
   `α(i)`, lowest at the midpoint and rising linearly toward both ends, because users
   attend to the beginning and end of a gesture.
4. **Channel integration** (Eqs. 7–9): each channel's distance passes through a Gaussian
   PDF (µ=0, σ prescriptively set as the sensitivity knob; **candidates with x > 2σ are
   discarded**), probabilities are renormalized over survivors, then combined by Bayes'
   rule into a confidence ranking.
5. **Speed-adaptive weighting** (Eqs. 10–11): fast gestures are memory-driven (shape
   dominates), slow ones visually traced (location dominates). A Fitts'-law normative
   time `t_n(w)` (a = 83 ms, b = 127 ms per prior SK studies) is compared to actual
   gesture time `t_a`; when `t_a < t_n` the location channel's σ is inflated by
   `σ·(1 + γ·log2(t_n/t_a))`, flattening it. `γ = 2.0`.
6. **Language model** (Eqs. 12–13): smoothed **bigram** LM; `1 − P(w|w_prev)` is pushed
   through a Gaussian to put it on the classifier's scale, then Bayes-combined with the
   confidence to re-rank the N-best list. An optional sentence-level Viterbi pass picks
   the best word-stream across consecutive N-best lists.
7. **Evaluation numbers**: real-time on 2004 hardware — 20,000 sokgraphs searched in
   **20–40 ms on an 800 MHz PIII**; expert practice ceiling studied with a 7,777-word
   email-mined lexicon (speeds far above longhand). The authors summarize: pruning +
   indexing make large vocabularies tractable; shape alone is insufficient, location +
   language resolve it.

## 3. Gboard LSTM decoder (Alsharif et al., ICASSP 2015)

O. Alsharif, T. Ouyang, F. Beaufays, S. Zhai, T. Breuel, J. Schalkwyk, *Long Short Term
Memory Neural Network for Keyboard Gesture Decoding*, ICASSP 2015, pp. 2076–2080.
<https://research.google.com/pubs/archive/43461.pdf>

- Motivation: gestures exhibit speech/handwriting-like **high variability,
  co-articulation and elision** — a rigid ideal-path template model cannot express that
  users cut corners ("the" grazing h) or elide keys entirely.
- Approach: an LSTM maps the raw resampled (x, y) trajectory directly to the word's
  character sequence; its scores are decoded jointly with conventional **FST** machinery
  that carries the lexicon and language model, so neural recognition plugs into the
  existing finite-state decoder rather than replacing it.
- Results (abstract): **+4% absolute** over a shape-matching baseline on a small lexicon,
  **+22% absolute** on a large lexicon (real datasets), +2% on a synthetic large-scale
  dataset — i.e. the bigger the lexicon, the more the neural model wins.
- Context for sizing the effort: later work reports the CTC-LSTM at **95.2% gesture
  accuracy** (COLING 2020, <https://aclanthology.org/2020.coling-main.87.pdf> Table 4),
  and Gesture2Text (arXiv:2410.18099, <https://arxiv.org/html/2410.18099>) cites SHARK2 at
  **35.3% CER vs 5.4% CER** for neural decoders — while also showing a well-tuned SHARK2-style
  reimplementation beats a naive neural decoder on out-of-distribution layouts. Takeaway
  for us: the classical pipeline is competitive *if* tuned, and the neural path is a
  future model swap (ONNX on-device LSTM), not a rewrite of the pipeline around it.

## 4. Our `GestureDecoder` vs the references

File: `android/keyboard-core/src/main/kotlin/com/wisperlow/mobile/keyboard/gesture/GestureDecoder.kt`
(test: `.../test/.../gesture/GestureDecoderTest.kt`). It is a genuine SHARK2-family
design — trie walk constrained by extremities + in-order path hits, then Gaussian
shape/location channels + LM in log space — and structurally closer to SHARK2 than
FlorisBoard is (FlorisBoard has no in-order constraint at all; it scores a flat
pruned word list). Gaps, in priority order:

1. **No length pruning.** SHARK2 prunes by extremity distance, FlorisBoard/ASK by
   extremity tree *and* ideal-length gate (8.42 × key size). We rely purely on the
   in-order `passRadius` walk plus `maxNodes = 20_000` / `maxCandidates = 400` caps, so a
   sloppy swipe fans out the trie until the caps truncate it — potentially before the
   right long word is even reached. Add a per-word ideal length (memoized, as FlorisBoard
   does) and prune at walk time.
2. **Doubled letters are ambiguous by construction.** `idealPath()` collapses consecutive
   repeats (`if (c == prev) continue`, lines 145–148), so "pool", "poll" and "poull"
   produce identical ideal paths. FlorisBoard scores a second looped ideal gesture (4
   corner points around the key) and keeps the better score. This is the single cheapest
   accuracy win available.
3. **Hard binary pass gate, no tunnel softness.** Every letter must land within
   `passRadius = 0.8` key units of *some* resampled point, in order (lines 87–103).
   SHARK2 uses the same idea but as a **zero-cost zone inside one key radius with soft
   distance outside** (its tunnel, Eqs. 4–6), so a word can survive a mistyped middle
   segment; FlorisBoard skips per-letter gates entirely and lets the Gaussian distance
   decide. Ours is the strictest of the three: good precision, recall collapse on fast
   corner-cutting swipes.
4. **No endpoint weighting.** SHARK2's `α(i)` (midpoint least, ends most) encodes that
   users aim at the first/last key and improvise the middle. Our `meanDistance()` weights
   all 64 points equally.
5. **`sigmaShape = 0.2` is likely ~2× too flat.** ASK's fitted `SHAPE_STD = 22.08` is a
   std over a *sum* of 200 points in unit-longest-side space ≈ **0.11 per-point mean**;
   our `shapeDistance` is already a per-point mean over paths normalized to a unit
   *diagonal* (diagonal ≥ longest side, so slightly smaller again). A matched σ is
   ≈ 0.1, not 0.2. `sigmaLoc = 0.5` maps almost exactly onto `LOCATION_STD = 0.5109`
   × key size — keep, with one caveat: theirs fits a Manhattan metric, ours is Euclidean,
   which runs ~10–20% larger; 0.55 is the safer carry-over.
6. **Resampling at 64 points** (line 14) vs 200 (FlorisBoard) / 300 (ASK). 64 is fine
   for the in-order walk (it runs first on the raw-ish path) but coarse for the Gaussian
   channels — 128 is the right next step; SHARK2-classic used "a fixed number N" and its
   distance equations degrade gracefully, so this is cheap.
7. **LM plumbing is unigram-shaped in practice.** `score()` uses `model.logProb(id,
   context)` correctly, but with `lmWeight = 0.5` and our `UnigramModel` (log-probs in
   ≈ [−14, 0]), LM deltas (≈ 4–8 between common and rare words) dwarf spatial deltas
   (≈ 1–3 for typical σ settings) — the spatial channel barely decides anything. That is
   roughly Gboard's lesson (language matters a lot), so don't shrink `lmWeight`
   reflexively; do make sure the bigram path (`NgramTables`) is what feeds `context`
   before tuning, and consider SHARK2's softmax renormalization over the candidate set
   (Eq. 8) if we ever need a calibrated confidence (e.g. "commit silently vs. show strip").
8. **No gesture-vs-tap discrimination beyond `minPathLength = 1f`.** Both references gate
   on distance *and* speed/velocity, and forbid glides starting on space/backspace/shift.
   One key-unit of path is reachable by a slow sloppy tap-drag; the tap pipeline must own
   the arbitration (see §5.1).
9. **Minor**: start/end gates use a fixed 1.0-key-unit radius (≈3–8 keys on QWERTY);
   FlorisBoard's "2 nearest keys each end" is both more bounded and more permissive for
   sloppy endpoints. Worth switching. `CandidateKind.CORRECTION / edits = 0` for every
   result is fine today but the strip UI will want a kind that means "gestured".
10. **Performance is already in the right envelope**: the test suite asserts < 40 ms on a
    50k vocabulary — the same latency SHARK2 reported for 20k on 2004 hardware. The caps
    make worst case bounded; length pruning (item 1) makes the typical case much faster.

### 4.1 Recommended parameter changes (`GestureParams`)

| Param | Now | Recommended | Why |
|---|---|---|---|
| `points` | 64 | **128** | resolution for the Gaussian channels; walk cost unchanged |
| `passRadius` | 0.8 | 0.8 first pass, **fallback 1.2 if zero candidates** | keeps precision, rescues fast swipes (SHARK2-style soft survival) |
| `startRadius` / `endRadius` | 1.0 | **2 nearest keys** each end (FlorisBoard) | bounded fan-out, more forgiving endpoints |
| `sigmaLoc` | 0.5 | **0.55** | LOCATION_STD 0.5109 on a Manhattan metric; ours is Euclidean |
| `sigmaShape` | 0.2 | **0.1** (then fit) | ASK's 22.08/200 ≈ 0.11 per-point mean, our normalization is unit-diagonal |
| `lmWeight` | 0.5 | keep; **re-tune once NgramTables bigrams are wired** | current scale analysis shows LM dominates spatial |
| `maxCandidates` / `maxNodes` | 400 / 20k | keep | SHARK2 did 20k templates in 20–40 ms; caps are the safety net |
| `minPathLength` | 1.0 | keep in core; **enforce touchSlop + velocity in the app layer** | detector concerns don't belong in the decoder |
| — | — | **add** ideal-length pruning (threshold ≈ 8.42 × key size) | both references prune by length |
| — | — | **add** looped ideal gesture for doubled letters | pool/poll/lull |

Fit `sigmaLoc`/`sigmaShape` properly the way ASK did: log (user path, top candidate,
same-word?) pairs from real swipes, measure the distance distribution for *correct*
words, take the std. Everything above is a carry-over constant until then.

## 5. UX integration steps (concrete, for this repo)

Touch handling lives in `android/app/src/main/java/com/wisperlow/mobile/keyboard/KeyArea.kt`
(Compose `pointerInput`, per-pointer `Track` with `Mode.TAP/PRIMARY/...`, plus a
space-bar cursor-drag mode), the IME in `WisperlowKeyboardService.kt`, and the editing
state machine in `keyboard-core/.../TypingSession.kt` (`EditorField` interface). Nothing
calls `GestureDecoder` yet — the wiring is greenfield.

### 5.1 Gesture-vs-tap arbitration (in `KeyArea`)

Mirror FlorisBoard's detector on the existing pointer loop: on pointer move, once
`hypot(dx, dy)` exceeds **both** `ViewConfiguration.touchSlop` and ~1.2 key widths (via
`BoardUnits`), and either elapsed < 500 ms with velocity > ~0.1 dp/ms, promote the
pointer to GLIDE: cancel its `pressed` key (send the existing cancel path), suppress the
tap on release, and start the trail. Refuse promotion when the initial key is
space/backspace/shift (their drag gestures — cursor move, delete-word swipe — take
precedence, exactly like FlorisBoard's `SWIPE_GESTURE_KEYS`). Convert px → `Touch` with
`BoardUnits.touch` (already the decoder's coordinate system) and decimate points closer
than keyWidth/4.

### 5.2 Trail + floating preview (in `KeyArea`/`KeyboardPanel` draw layer)

Add a `drawWithContent` overlay as in FlorisBoard `TextKeyboardLayout.kt:192–213`: a
`(Touch, timestamp)` list from the glide pointer; draw circles backwards from the finger,
head radius ~20 dp tapering by ×0.99 per ~3 dp of interpolation, dropping points older
than ~200 ms; on lift, run a 200 ms `AccelerateInterpolator`-style fade (Compose
`Animatable`, easeIn) on the radius. Colour: a `GlideTrail` slot in `KeyboardTheme`.
Preview: throttle a `decode(path, context, max = 8)` call to every ~150 ms on
`Dispatchers.Default` while gliding and float the top-1 word above the trail head
(Gboard-style bubble anchored at the finger x, clamped to board bounds) *and* push 1/8
candidates into the suggestion strip on release; cancel the in-flight decode when a new
throttle window starts (our `decode` is pure, so this is safe).

### 5.3 Commit, auto-space, undo (service + `TypingSession`)

Extend `EditorField` with a `commitGesture(text)` that reproduces FlorisBoard's phantom
space: if the char before the cursor isn't whitespace, commit `" " + word`, then leave it
as the composing word (or set an explicit "gesture committed, next gesture appends
without space" flag); the next gesture replaces/extends it. Wire the existing
`TypingSession` undo concept so **one backspace right after a gesture deletes the whole
gestured word** (FlorisBoard `immediateBackspaceDeletesWord` default true), a left-swipe
from backspace deletes the previous word, and committed gesture words feed the same
word-pair learning path taps already use. Capitalization: reuse the service's existing
shift-state logic (titlecase when shifted, uppercase under caps-lock) exactly like
`KeyboardManager.fixCase`, then reset shift.

### 5.4 Settings + tests

Prefs mirroring FlorisBoard `AppPrefs.Glide`: enabled (default off until tuned), show
trail, trail fade duration (200 ms), show preview, preview refresh delay (150 ms),
immediate-backspace-deletes-word (true). In `keyboard-core`, extend
`GestureDecoderTest`: doubled-letter pairs ("pool"/"poll") must rank correctly once
loops land; length-pruning must cut a decoy long word; the existing < 40 ms / 50k test
stays the perf gate. No Gradle work was done for this note per the task constraints.
