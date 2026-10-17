# Why the first English chat n-gram model makes typing worse

**Question:** `en-chat-v1.wlm` (the first English chat n-gram model) regresses every
typing metric against the `en.txt`+`en_modern.txt` unigram supervisor — more false
corrections, worse mistyped recovery, and a 19-point collapse in exact-misspelling
repair (70.9% → 51.9%). This note identifies the cause with controlled ablations and
says what to change next.

**Answer in one line:** the build pipeline dropped every count-1/count-2 bigram and
trigram (`count_ngrams.py` defaults `--min2 3 --min3 3`), so the estimator's
count-of-counts discount fit degenerates to all-zero discounts and the modified-KN
interpolation weight `lambda` collapses to its floor `1e-6` **for every context that
has any listed continuation**. Any word that is not a listed continuation of the
context is then penalised `ln(1e-6) = -13.8155` nats on backoff. Since 75% of
contexts carry that floor, most corrections are crushed ~14 nats and lose to the raw
literal. No engine bug is required to explain the regression.

Measured on the committed 40-sentence / 460-word corpus, fingerprint sentences
`5bd0a7fdc98f`, misspellings `c2a4e961d8be`, assets `c56f158ef85e`, 1 seed.

## What was measured

| metric (normal preset) | legacy lexicon | smart unigram | ngram `en-chat-v1` | ngram, backoff fixed |
| --- | --- | --- | --- | --- |
| no-strip word accuracy | 92.2% | 89.3% | 86.3% | **90.2%** |
| false autocorrections | 0.0% | 5.4% | 8.3% | **4.1%** |
| recovery on mistyped words | 75.2% | 77.9% | 74.5% | **77.9%** |
| misspellings typed exactly, fixed | 77.4% | 70.9% | 51.9% | **68.3%** |
| next-word top-1 / top-3 | 3.4% / 6.3% | 6.2% / 12.5% | 7.2% / 15.5% | 7.2% / 15.4% |

"Backoff fixed" is the *same model file* with only the stored backoff weights changed
to `ln(0.1)`, `ln(0.3)` or `0` — nothing else, no retrain (see Repro). Sloppy preset
moves the same way (accuracy 72.2 → 76.5%, false corrections 12.8 → 7.9%, recovery
63.9 → 67.9%, spelling 51.9 → 68.3%). Any sane interpolation weight recovers the
regression and keeps the bigram's next-word gain.

## Finding 1 — the dominant cause is a collapsed modified-KN backoff, not the engine

`NgramEstimator` fits Chen & Goodman discounts from the count-of-counts buckets
`n1..n4` and sets `base = n1 + 2*n2`
(`android/keyboard-core/src/main/kotlin/com/wisperlow/mobile/keyboard/lm/NgramEstimator.kt`).
When `base == 0` every discount is 0, so `discountedShare = count/total`, `listedMass
= 1.0` for the context, and

```kotlin
val lambda = (1.0 - listedMass).coerceIn(LAMBDA_MIN, 1.0)   // LAMBDA_MIN = 1e-6
```

pins `lambda` at `1e-6`. `logProb` for an unlisted continuation is then
`unigramBackoff[context] + unigramLogProb(w) = ln(1e-6) + uniL = -13.8155 + uniL`.

The counts directory this model was built from has **no count-1 or count-2 rows at
all**, because the counter floors them:

* `android/tools/lm/count_ngrams.py`: `DEFAULT_MIN1 = 2`, `DEFAULT_MIN2 = 3`,
  `DEFAULT_MIN3 = 3` (documented defaults).
* `/home/yvm/kbdata/counts/en-chat-v1`: `min(unigram)=4`, `min(bigram)=3`,
  `min(trigram)=3`; `n1 = n2 = 0` for both higher orders.

So `base = 0` by construction and the interpolation weight is always the floor. The
compiled model carries the evidence directly: the unigram-backoff and bigram-backoff
codebooks both span exactly `[-13.8155, 0.0]`, i.e. the only values present are the
floor and `lambda = 1` (contexts with no listed row at all):

* **45,215 / 60,001** context words have `lambda = 1e-6` (75%).
* **347,330 / 500,527** bigram rows have `lambda = 1e-6` (69%).

The behavioural signature matches exactly: for the 135 of 416 misspelling targets
that are *not* listed after `<s>`, `bigram(<s>, w) - unigram(w) = -13.816` nats.

## Finding 2 — the spelling metric splits cleanly on the floor

`typingEval`'s spelling pass types each wrong form exactly with a fresh engine, so its
context is always `[<s>]` and its decision turns on `bigram(<s>, right)` — the one
place the floor bites hardest:

| group (target after `<s>`) | pairs | fixed, current model | fixed, unigram-only context |
| --- | --- | --- | --- |
| listed (bigram count ≥ 3) | 281 | 70.8% | 66.9% |
| **unlisted** | **135** | **6.7%** | **62.2%** |

When the target *is* a listed successor the bigram actually helps (70.8% beats the
unigram's 66.9%). When it is not, the `-13.8` nat backoff makes a correct repair
essentially impossible: 6.7% instead of 62.2%. The aggregate 51.9% is the mix of a
good listed signal and a broken backoff. Against the model's own `<s>` successor set,
32% of the misspelling targets are unlisted, and 15 of the 40 sentence first words
are too.

## Finding 3 — the unigram *base* and the vocabulary are not the problem

An LM-level ablation over the same 416 pairs (fresh engine, no accumulated learning,
so numbers read lower than the metric's cumulative 51.9%/70.9%) separates the factors:

| variant (chat vocabulary unless noted) | pairs fixed |
| --- | --- |
| A. full n-gram (`en-chat-v1`) | 50.0% |
| B. continuation unigram only (context ignored) | 65.4% |
| C. chat **frequency** unigram only | 63.9% |
| D. word-list unigram (real smart baseline) | 68.3% |

* **Bigram/trigram content is the whole gap:** A → B recovers ~15 points. Removing
  the higher orders removes the floor.
* **The continuation-vs-frequency unigram hypothesis is falsified:** B (continuation
  base) is 65.4% and C (chat word frequency) is 63.9% — the continuation base is if
  anything slightly better. The modified-KN unigram base is not what hurts.
* **Vocabulary coverage is a minor term:** only 9 of 416 targets are missing from the
  chat vocabulary (365 of the *mistyped* forms are, which is harmless — an unknown
  literal already scores `-20`). D beats B by ~3 points, consistent with vocabulary
  and prior differences, not with the regression.

## Finding 4 — accumulated learning explains ~2 points, not the gap

The spelling metric reuses one engine across all 416 pairs and calls `learn()` on each
commit, so user words accumulate. Re-running the same decision without `learn`:

* `en-chat-v1`: 50.0% fresh → 51.9% accumulated.
* backoff-fixed model: 62.5% fresh → 68.3% accumulated.

So learning contributes 1.9–5.8 points and directionally helps; the 19-point
regression is not an artefact of shared engine state.

## Finding 5 — this is not the resolver/policy defect Agent I28 owns

`type()` in the spelling pass bypasses the key resolver and the greedy resolver that
I28 is fixing, so the 51.9% figure is a pure decoder + `SuggestionPolicy` result driven
by LM scores. The false-correction and recovery regressions do run through the
resolver, but the backoff floor moves them too (false corrections 8.3% → 4.1%,
recovery 74.5% → 77.9% with only the backoff changed), so a large part of the
sentence-level damage attributed to the LM is this same floor. Fixing the LM floor and
the I28 resolver defect are independent, additive wins.

## Repro

Data: `/home/yvm/kbdata/counts/en-chat-v1/{unigrams,bigrams,trigrams}.tsv`,
`/home/yvm/kbdata/models/en-chat-v1.wlm`
(SHA-256 `02cc4936d152a189e62957b42ef202ca6943bd8814933aa05f37ccdf5cdd6c0c`).

**1. Show the floor in the counts** (no build needed):

```python
import collections
for name, arity in [("bigrams.tsv", 2), ("trigrams.tsv", 3)]:
    c = collections.Counter(int(l.split("\t")[arity])
                            for l in open("/home/yvm/kbdata/counts/en-chat-v1/" + name))
    print(name, "min", min(c), "n1", c[1], "n2", c[2])   # min 3, n1 0, n2 0
```

**2. Show the floor in the model** (edit the selector once, then run the eval):

`NgramModel` stores a 256-entry float codebook per order and one 1-byte code per
context/row; the layout is `magic|version|v|b|t|vocab|5 codebooks|...`. Setting the
`uniBoOff`/`biBoOff` byte ranges to the code whose decoded value is `ln(0.1)` produced
`en-chat-v1-l0.10.wlm`; `ln(0.3)` and `0.0` produced the other two variants. Run each
through the standard harness (the locked wrapper, alternate `--model` only):

```sh
android/scripts/gradle-locked.sh :keyboard-core:typingEval \
  -PevalArgs="--engine=smart --model=/tmp/en-chat-v1-l0.10.wlm --quick --limit=40 --seeds=1"
```

**3. Confirm the split** (Findings 2/3): join the model's `<s>` successor set from
`bigrams.tsv` against the per-pair spelling decisions and reuse the LM wrappers
(context-ignoring / frequency / word-list) to reproduce the A–D table.

No corpus was modified, no training data was touched, and nothing was retrained; the
eval corpus is the committed one (`c2a4e961d8be`). Raw corpora are present at
`/home/yvm/kbdata/raw/en` (tatoeba + oasst1 prompter turns + wildchat user turns,
1.4 GB) but were not re-read.

## Next step

The fix is in the count→estimate pipeline, not the decoder:

1. **Recount for a KN model with `--min2 1 --min3 1`** (keep `--min1 2`), or
   count twice — a small `min2=1` pass only for the discount fit. That restores
   count-1/2 rows, `base > 0`, and a real `lambda` in ~`0.1–0.5` rather than the
   floor.
2. **Make the estimator robust instead of silently degenerate:** when `base == 0`,
   do not clamp `lambda` to `1e-6`; fall back to absolute discounting or to
   `lambda = 1` and record the condition.
3. **Add a build gate:** fail or warn when any order's median `lambda` hits the
   floor (the existing compile gate checks quantization, unigram mass and top-5
   agreement — all green here — but never backoff sanity or perplexity).

Acceptance: recompile, then rerun `:keyboard-core:typingEval` and require the
misspellings-fixed rate back at or above the 70.9% unigram line, false corrections at
or below 5.4%, and recovery at or above 77.9%, while retaining the n-gram's next-word
top-3 gain (≥15%). The backoff-fixed models above are a realistic target.
