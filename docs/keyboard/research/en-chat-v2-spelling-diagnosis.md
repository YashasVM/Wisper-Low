# English chat v2 spelling diagnosis

Measured 2026-10-18 against main engine sources at `10f49ae` and the unchanged
v2 artifacts from [the training audit](en-chat-v2-evaluation.md). No production
source, model, policy threshold, decoder weight, or training input changed.

The remaining regression is not target vocabulary coverage. All 69 fresh-state
repairs that unigram gets and v2 misses have their intended target in v2's normal
12-candidate pool. Of those losses, 51 are policy decisions and 18 are ranking.
There is also a reproducible inconsistency in unknown-literal scoring. Correcting
that score in a temporary policy-only probe helps, but introduces a clean-word
false correction and leaves a gap. Neither artifact should be promoted on this
evidence.

## Method and reproduced baseline

Use all 416 committed English misspelling pairs, exact typed characters without
touch noise. The normal harness capitalizes the first character and supplies
`<s>` context. Its `reset()` resets the field and session, but retains the engine's
learned lexicon. Published results reproduce exactly:

| Model | Published harness, retained learning | Fresh state, sentence start | Fresh state, lowercase start | Fresh state, no context |
| --- | ---: | ---: | ---: | ---: |
| Word-list unigram | 292 / 416 | 284 / 416 | 284 / 416 | 284 / 416 |
| v2 | 231 / 416 | 225 / 416 | 225 / 416 | 244 / 416 |
| v2-pruned | 245 / 416 | 229 / 416 | 229 / 416 | 268 / 416 |

Fresh-state calls never learn or trust a word. Reusing an engine for these calls
only reuses its immutable model/index and caches. Each pair therefore sees an
empty personal lexicon. Case-insensitive exact surface comparison matches the
spelling metric. The no-context control maps an unknown preceding word to
`Vocabulary.UNKNOWN`, which backs off to the model's unigram probabilities.
Lowercase start preserves `<s>` context. These controls change no scoring weights.

Retained learning raises the published result by 8, 6 and 16 repairs respectively.
Those numbers are harness-order effects, not independent spelling capability.
Future independent spelling comparisons should explicitly disable learning or
construct a fresh personal state per pair.

## Coverage, ranking and fixed policy gates

Exclusive fresh sentence-start outcomes follow this order: fixed, expressive
protection, target OOV, search missing, pool missing, target available only as a
completion, ranking, policy. Expressive protection means three repeated letters;
the engine intentionally preserves all 20 such inputs in every model.

| Outcome | Unigram | v2 | v2-pruned |
| --- | ---: | ---: | ---: |
| Fixed | 284 | 225 | 229 |
| Expressive protection | 20 | 20 | 20 |
| Target OOV | 6 | 4 | 4 |
| Target absent from expanded search output | 5 | 4 | 4 |
| Target outside normal 12-candidate pool | 5 | 7 | 8 |
| Target available only as a completion | 17 | 16 | 16 |
| Another replacement candidate ranks higher | 32 | 63 | 51 |
| Target is best replacement, policy blocks | 47 | 77 | 84 |

The expanded search uses the unchanged beam/search parameters with a large output
limit. It distinguishes output truncation from search coverage; it does not prove
that a wider beam could never find a missing word.

V2 has exact target vocabulary coverage for 412 pairs versus unigram's 410. Its
four OOV pairs are the three spellings targeting `governmentally` and
`neices -> nieces`. None accounts for an incremental v2 loss against unigram.
V2 loses 69 unigram successes and gains 10. Pruned loses 68 and gains 13.
Every incremental loss for both artifacts has the intended target in the normal
pool. Pruned's losses split into 49 policy decisions and 19 ranking decisions.

The first failing policy gate for those incremental losses is:

| Gate | v2 | v2-pruned |
| --- | ---: | ---: |
| Known-literal margin | 29 | 16 |
| Unknown-literal margin | 13 | 20 |
| Ambiguous rival | 4 | 6 |
| Completion blocker | 5 | 7 |

The LM vocabulary makes 57 misspelled inputs known, compared with 25 in the
word-list engine. There are 37 newly known inputs and five that stop being known.
Of v2's incremental losses, 27 involve a newly known literal. Membership includes
both corpus misspellings and case-insensitive acronym/name collisions, so it is
not sound to delete all these entries as typos.

Selected raw counts illustrate the corpus and probability distinction:

| Literal / target | Selected training token counts | v2 literal unigram log-odds | Target-minus-literal decoder margin |
| --- | --- | ---: | ---: |
| `acheive / achieve` | 4 / 1,252 | -9.2103 | 2.3324 |
| `accomodation / accommodation` | 7 / 116 | -8.4428 | 0.7543 |
| `adress / address` | 25 / 4,485 | -7.2367 | 4.8250 |

All three intended targets are the best replacement candidate, but the known-word
margin is 7 nats. The literal is not rare under the existing -10.2 log-odds gate.
These unigram probabilities use continuation evidence, not the displayed token
counts. A low raw token count therefore does not imply the policy regards a
literal as rare. Counts here come from the selected unigram TSV; absence there
does not prove absence from the complete original corpus.

Context also changes rivals and completions. For example, `adavnce -> advance`
is blocked because `advanced` has a completion score 1.5341 nats above `advance`,
exceeding the fixed 1-nat blocker. `animel -> animal` instead ranks `anime` higher.
Removing `<s>` recovers 27 of v2's 69 incremental losses, and 38 of pruned's 68,
while also changing other outcomes. Context pressure is real, separate from OOV.

## Unknown-literal score consistency probe

`BeamTypingDecoder.literalCandidate()` assigns an OOV literal a fixed -20 score,
bypassing `model.logProb(UNKNOWN, context)`. Known correction candidates pay the
context's backoff. At `<s>`, the model returns -23.4904 for the unknown word in v2
and -23.8578 in pruned. The literal therefore receives an unintended advantage of
3.4904 or 3.8578 nats relative to the conditional model score.

A separate probe replaces only an UNKNOWN LITERAL candidate's `lmScore` with
`model.logProb(UNKNOWN, context)` and adjusts its total score by that difference.
It runs the ordinary policy with unchanged candidates, thresholds and weights,
and preserves the engine's expressive-word protection. This is a score
consistency ablation, not a fitted parameter.

| Fresh sentence-start check | Unigram | v2 | v2-pruned |
| --- | ---: | ---: | ---: |
| Spelling repairs, before | 284 | 225 | 229 |
| Spelling repairs, conditional UNKNOWN literal | 284 | 255 | 272 |
| Clean checks left unchanged, before | 416 | 415 | 414 |
| Clean checks left unchanged, conditional UNKNOWN literal | 416 | 412 | 411 |

The probe adds 30 correct repairs for v2 and 43 for pruned, losing no previously
correct repair. It also converts 13 and eight previously preserved misspellings
to an incorrect different word, respectively. The added clean false correction
is one distinct OOV word, `governmentally -> governmental`, repeated by three
pair-based clean checks. Broader clean/OOV safety is not established by these
416 checks.

For example, v2 gives `acceptible -> acceptable` a 0.6803-nat margin over the
fixed literal floor. The conditional UNKNOWN price raises that margin to 4.1707,
passing the existing 1.5-nat gate. It cannot fix the low known-literal margins
above, where the policy deliberately prices the matching known spelling.

## Next step and reproduction

First isolate the unknown-literal conditional score correction in its own change
with clean OOV regression checks. It belongs in decoder scoring, not discount
training or a policy-threshold sweep. The evidence does not justify shipping it
without checking the added clean-word substitution.

The remaining gap needs a separate corpus/model-to-lexicon investigation. Audit
which corpus surfaces qualify as known literals, and the continuation unigram
probabilities that drive rarity and candidate ranking. The training audit already
limits its algorithm claim to raw higher-order counts with a continuation base;
this experiment does not establish full modified Kneser-Ney lower-order count
correctness. These results support investigating those choices before weakening
known-word, completion or ambiguity protections to meet this spelling benchmark.
Do not inject benchmark words or tune lambda/policy against these pairs.

Diagnostic source and evidence are archived in `/home/yvm/kbdata/diagnostics/en-v2-spelling-20261018/`: `src/`,
`probe.init.gradle`, `rows.json`, `classified.json`, `ablation.json`, and logs.
The probe compiles current main engine/eval sources using only the worker
checkout's generated build output. No tracked source in either checkout changed.
Run from `/home/yvm/.t3/worktrees/Wisper-Low/t3-ec605e13`:

```sh
android/scripts/gradle-locked.sh -I /home/yvm/kbdata/diagnostics/en-v2-spelling-20261018/probe.init.gradle \
  :keyboard-core:spellingDiagnosis -Pkotlin.incremental=false
android/scripts/gradle-locked.sh -I /home/yvm/kbdata/diagnostics/en-v2-spelling-20261018/probe.init.gradle \
  :keyboard-core:spellingDiagnosis -Pkotlin.incremental=false \
  -PprobeMain=diagnosis.AblationKt
```

Both runs pass. The primary probe covers 3,744 pair/model/context combinations
plus the three published-harness reproductions. The second covers 2,496
pair/model/clean combinations. Model SHA-256 values remain
`5ca5675a0a836dfe2a096cc7862ea3e45a96c6dff128e17f551b4cef04c8cc0a`
and `863e540494787c09313b09eea4391f3d982e044aa16fd8c6d5fa11d35bb86b39`.

## Current-engine held-out check

After integrating the training fix as `10f49ae`, reran both models against 120
sentences outside the original quick40 set, with one seed and the current
word-boundary validation. The set has 933 words and the same 416 spelling pairs.
Sentence selection and regeneration are documented in
[word-boundary-validation.md](word-boundary-validation.md). The sentence hash is
`8fd995fa5779a2c57f7688c6e48987075dc48bcba755b96f46889815f8d1128c`;
English asset and spelling-pair fingerprints match the training audit.

All values below are percentages. Accuracy uses the harness's normalized
`accuracyNoStrip` measure; prediction uses its ideal-strip simulation.

| Metric | Shipped unigram, normal / sloppy | v2, normal / sloppy |
| --- | ---: | ---: |
| Word accuracy | 91.00 / 73.10 | 90.35 / 72.35 |
| False corrections | 2.15 / 3.33 | 2.30 / 1.82 |
| Mistyped recovery | 75.18 / 60.20 | 73.40 / 58.21 |
| Next-word top-3 | 13.36 / 11.34 | 17.93 / 15.00 |
| Spelling repair, retained learning | 70.19 | 55.53 |

V2 improves next-word prediction and reduces sloppy-input false corrections, but
loses word accuracy and recovery in this set. The comparison supports keeping the
current app model. It does not justify promoting v2 based on quick40 alone.
Raw reports are `/home/yvm/kbdata/{unigram,en-chat-v2}-current-heldout.{json,md}`.
Reproduce after regenerating the held-out fixture:

```sh
android/scripts/gradle-locked.sh -q :keyboard-core:typingEval \
  '-PevalArgs=--quick --engine=smart --seeds=1 --data=/tmp/word-boundary-heldout --assets=/home/yvm/code/Wisper-Low/android/app/src/main/assets --out=/home/yvm/kbdata/unigram-current-heldout.json'
android/scripts/gradle-locked.sh -q :keyboard-core:typingEval \
  '-PevalArgs=--quick --engine=smart --seeds=1 --data=/tmp/word-boundary-heldout --assets=/home/yvm/code/Wisper-Low/android/app/src/main/assets --model=/home/yvm/kbdata/models/en-chat-v2.wlm --out=/home/yvm/kbdata/en-chat-v2-current-heldout.json'
```

The integrated checkout also passed 279 core/compiler tests, 82 app tests and
27 Python counter tests. A clean recompilation reproduced v2 SHA-256
`5ca5675a0a836dfe2a096cc7862ea3e45a96c6dff128e17f551b4cef04c8cc0a`.
