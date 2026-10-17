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
| `--out FILE`  | (required) | Output WLM1 binary; written atomically (tmp file + rename)    |
| `--vocab N`   | (required) | Vocabulary cap, 2..65535 (u16 word ids)                       |
| `--min1/2/3 C` | 1      | Estimator count floors (1 keeps every observed n-gram)         |

Progress goes to stderr, the report to stdout. Exit codes: 0 ok, 2 bad usage,
1 pipeline/verification failure. The pipeline is a pure function of the counts and the
floors, and the verification sample is seeded, so identical inputs give an identical
file, SHA-256 and report.

Example on the prepared chat corpus:

```
android/tools/lm/build_model.sh \
  --counts /home/yvm/kbdata/counts/en-chat-v1 \
  --out /tmp/en-chat-v1.wlm --vocab 60001
```

## What the verification checks (and why the model cannot claim quality from this)

The pipeline reads counts, estimates, writes, **reloads the written file** and compares
it against the unquantized `TablesModel` reference on a deterministic sample: ~150
contexts covering empty/sentence-start/ranked unigrams, listed bigram and trigram rows
(full high-order path), random unlisted pairs and triples (the backoff path), and
unknown ids (`Vocabulary.UNKNOWN`, always `-1`) in both context positions. Sampled
words include the unknown id, sentence start, fixed frequency ranks and the model's own
top predictions.

Reported per run: vocab and n-gram counts, byte size and SHA-256, timings, unigram mass
sum, max/mean quantization error (nats) and top-5 prediction agreement, plus
representative next-word predictions. Failures (non-finite probabilities, a vocabulary
or header mismatch, an unparseable or drift-beyond-threshold file) exit non-zero.

**Caveat on "quality"**: passing here means the file round-trips within the measured
quantization error and its unigram marginal is normalized. It does *not* mean the model
is good — perplexity, coverage and typing-level evaluation are separate work, and a
normalized model can still be poorly trained.

## How it builds without touching shared files

Gradle runs only through `scripts/gradle-locked.sh`. `src/tools/kotlin` and the
`compileModel` JavaExec task (heap `-Xmx768m` for this memory-tight machine) are added
by `compile-model.init.gradle` at invocation time, so `build.gradle.kts` stays untouched,
stays untouched and nothing new is downloaded. Under the hood the CLI arguments are
passed one per line via the `compileModelArgs` Gradle project property (see the init
script header), which is why quoted/odd paths survive.

## Requirements

- The committed engine dependencies: `NgramCounts`/`NgramEstimator` (estimator task) and
  `NgramModelWriter`/`NgramModel`/`TablesModel` (binary format task). When the init
  script is provided, the tool sources compile with the module's normal `main` sources,
  so they resolve whatever main has; without both dependency commits, compilation fails
  on unresolved references — that is the expected state until both land.
- The counter's TSVs from `count_ngrams.py` (the compiler never recounts).
