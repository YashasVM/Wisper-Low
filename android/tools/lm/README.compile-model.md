# compile-model: counts TSVs -> verified compact language model

Turns the counter tool's output (`count_ngrams.py`, see README.md in this directory)
into the compact WLM1 binary the engine loads (`NgramModel`), and **proves the written
file is loadable and numerically sound before the command exits**. Estimation is
interpolated modified Kneser-Ney (`NgramEstimator`); the file is quantized `NgramTables`
written by `NgramModelWriter`.

## Usage

```
android/tools/lm/build_model.sh --counts DIR --out FILE --vocab N [--min1 C] [--min2 C] [--min3 C]
```

| Flag          | Default | Meaning                                                        |
| ------------- | ------- | -------------------------------------------------------------- |
| `--counts DIR` | (required) | Directory with `unigrams.tsv`, `bigrams.tsv`, `trigrams.tsv` |
| `--out FILE`  | (required) | Output WLM1 binary; verified as a candidate, then published only after all checks pass |
| `--vocab N`   | (required) | Vocabulary cap, 2..65535 (u16 word ids)                       |
| `--min1/2/3 C` | 1      | Estimator count floors (1 keeps every observed n-gram)         |

Progress goes to stderr, the report to stdout. Exit codes: 0 ok, 2 bad usage,
1 pipeline/verification failure. The pipeline is a pure function of the counts and the
floors, and the verification sample is seeded, so identical inputs give an identical
file, SHA-256 and report.

Example on the prepared chat corpus:

```
android/tools/lm/build_model.sh \
  --counts /home/yvm/kbdata/counts/en-chat-v2 \
  --out /tmp/en-chat-v2.wlm --vocab 60001
```

## What the verification checks (and why the model cannot claim quality from this)

The pipeline reads counts, estimates, writes, **reloads the written file** and compares
it against the unquantized `TablesModel` reference on a deterministic sample: ~150
contexts covering empty/sentence-start/ranked unigrams, listed bigram and trigram rows
(full high-order path), random unlisted pairs and triples (the backoff path), and
unknown ids (`Vocabulary.UNKNOWN`, always `-1`) in both context positions. Sampled
words include the unknown id, sentence start, fixed frequency ranks and the model's own
top predictions.

Reported per run: vocab and n-gram counts, byte size and SHA-256 (of the verified
candidate bytes, which are the published bytes), timings, unigram mass of both the
reference estimate and the loaded artifact (explicitly labeled; measured ~0.9987
loaded vs ~1.0000 reference on the en-chat-v1 audit), max/mean quantization error
(nats) and top-5 prediction agreement, plus representative next-word predictions.
Failures (non-finite probabilities, a vocabulary or header mismatch, an unparseable
file, drift past the gate) exit non-zero **after** discarding the candidate, so a
previously published output is left byte-identical. The gate bound is
**0.1 nats** (documented in ModelCompiler.kt): the audited artifact measures
0.0527 nats, so the bound accepts that measured error and rejects larger drift.

**Caveat on "quality"**: passing here means the file round-trips within the measured
quantization error and its unigram marginal is normalized. Perplexity, coverage and typing-level evaluation are separate work, and a
normalized model can still be poorly trained.

The compiler also reports each order's backoff lambda minimum, median and 95th
percentile. It rejects an order when at least half its stored backoff weights have
lambda at or below `1e-6`, the collapse signature measured in en-chat-v1. This is a
pipeline sanity gate, not a typing-quality threshold. A minority of very frequent
contexts may legitimately have tiny lambda. Empty orders and ordinary backoff-only
contexts pass. The gate runs before writing the candidate.

Discounts are fitted from all supplied rows before estimator pruning. If n1, n2 or
n3 is missing, or any fitted discount is nonpositive or exceeds its count bucket,
the compiler reports the affected order and the estimator uses the whole-order fallback D1=0.5, D2=1, D3+=1.5. These are KenLM's
[documented fallback values](https://github.com/kpu/kenlm/blob/master/lm/builder/lmplz_main.cc),
with the same whole-order fallback structure as its
[discount fit](https://github.com/kpu/kenlm/blob/master/lm/builder/adjust_counts.cc).
Rejecting zero discounts is an additional guard against losing all lower-order mass.
Lambda is the sum of discounts divided by retained context counts, with no fixed
lower bound that adds probability mass. Fallback keeps sparse/artificial corpora
usable; it does not recover statistics already lost through counter pruning.

Compiler gate tests are enabled with the tool init script:

```sh
android/scripts/gradle-locked.sh -I tools/lm/compile-model.init.gradle :keyboard-core:test
```

## How it builds without touching shared files

Gradle runs only through `scripts/gradle-locked.sh`. `src/tools/kotlin` and the
`compileModel` JavaExec task (heap `-Xmx768m` for this memory-tight machine) are added
by `compile-model.init.gradle` at invocation time, so `build.gradle.kts` stays untouched
and nothing new is downloaded. Under the hood the CLI arguments are
passed one per line via the `compileModelArgs` Gradle project property (see the init
script header), which is why quoted/odd paths survive.

## Requirements

- The committed engine dependencies: `NgramCounts`/`NgramEstimator` (estimator task) and
  `NgramModelWriter`/`NgramModel`/`TablesModel` (binary format task). When the init
  script is provided, the tool sources compile with the module's normal `main` sources,
  so they resolve whatever main has; without both dependency commits, compilation fails
  on unresolved references — that is the expected state until both land.
- The counter's TSVs from `count_ngrams.py` (the compiler never recounts).
