# English chat v2 training pipeline audit

This experiment fixes the lost discount evidence diagnosed in
[the v1 audit](en-chat-v1-evaluation.md). It changes training and compiler validation
only. No keyboard engine, decoder, lexicon, suggestion policy or app asset changed.
The behavior baseline is commit `a3c5d1a`; root's uncommitted word reconciliation and
Latin regional work are outside this checkout. No model is packaged in the app.

The count/discount/backoff defect is fixed, but neither v2 artifact meets the
spelling-repair acceptance target. Keep the unigram model as the production choice
until that remaining regression is understood. The measurements below use fresh
baselines on the same committed policy, not the older note's numbers.

## Method and training inputs

Recount exactly v1's three converted training sources, in the same order. The source
manifest and licenses are `/home/yvm/kbdata/README.md`. Inputs are training material
only; no typing-evaluation sentences or spelling pairs enter the counter.

| Input under `/home/yvm/kbdata/raw/en/` | SHA-256 of the compressed input |
| --- | --- |
| `tatoeba/tatoeba_cc0_eng.txt.gz` | `20a76c27ee6cc4cdd8f41985c1baece83ae20c158470997432c3dc923b6227d1` |
| `openassistant_oasst1/oasst1_eng_prompter.txt.gz` | `eae9c08f4554d2c7a818197f660c0b46924b5aba5c34ec4f71bb8ca46614b523` |
| `wildchat/wildchat_eng_user.txt.gz` | `62e893ec7df2a13944c96ec939e0f33a7938eb846e50539585db9419c3a5a3c6` |

The counter uses `--vocab 60000 --min1 2 --min2 1 --min3 1`. Its default higher-order
floors are now 1 and its metadata records input paths and pruning settings. The
result has 169,397 lines, 684,565 sentences and 12,320,523 word tokens, identical to
v1. Unigram TSV bytes match v1 exactly. Filtering the new higher-order TSVs to counts
at least 3 produces v1's corresponding TSVs byte for byte.

| Order | v1 rows | v2 rows | v2 n1 | v2 n2 | v2 n3 | v2 n4 | Fitted D1 / D2 / D3+ |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Bigram | 500,527 | 1,389,375 | 657,177 | 231,671 | 109,082 | 73,670 | 0.586493 / 1.171551 / 1.415615 |
| Trigram | 701,773 | 3,019,127 | 1,832,455 | 484,899 | 197,510 | 120,449 | 0.653922 / 1.200930 / 1.404855 |

Discount fitting now precedes estimator row floors. For undefined or invalid fits,
the estimator uses a whole-order fallback and the compiler names the affected order.
The fallback D1=0.5, D2=1, D3+=1.5 comes from
[KenLM's CLI](https://github.com/kpu/kenlm/blob/master/lm/builder/lmplz_main.cc).
Its [estimator](https://github.com/kpu/kenlm/blob/master/lm/builder/adjust_counts.cc)
also fits from pre-pruning statistics and substitutes the whole-order fallback when
a fit fails. Wisperlow additionally treats a nonpositive discount as a failed fit.
These are fixed training rules, not parameters fitted against this typing benchmark.

Lambda now equals removed count mass divided by retained context count mass. There
is no lambda floor that adds probability mass. The compiler rejects an order if at
least half of its stored backoff values have lambda at or below `1e-6`. That catches
the v1 collapse signature, while allowing a minority of high-count contexts to have
small interpolation weights. This gate does not establish typing quality.

This bounded fix retains the existing estimator's raw higher-order count convention
and continuation unigram base. It does not claim to implement KenLM's full adjusted
lower-order count algorithm. Vocabulary filtering still occurs before the fit.

## Artifacts and backoff distribution

All model paths are under `/home/yvm/kbdata/models/`.

| Model | Bytes | Bigram / trigram rows | SHA-256 |
| --- | ---: | --- | --- |
| v1, unchanged | 7,112,598 | 500,527 / 701,773 | `02cc4936d152a189e62957b42ef202ca6943bd8814933aa05f37ccdf5cdd6c0c` |
| v2, compiler floors 1/1/1 | 21,175,444 | 1,389,375 / 3,019,127 | `5ca5675a0a836dfe2a096cc7862ea3e45a96c6dff128e17f551b4cef04c8cc0a` |
| v2-pruned, compiler floors 1/3/3 | 7,112,598 | 500,527 / 701,773 | `863e540494787c09313b09eea4391f3d982e044aa16fd8c6d5fa11d35bb86b39` |

The counter floors are 2/1/1 for both v2 builds. Both fits use the same unpruned
count evidence. The pruned comparison keeps v1's topology and continuation unigram
base, isolating fitted discount/backoff effects from adding rare rows. It is a
controlled comparison, not a sweep over lambda values.

Reference-table lambda distributions, before artifact quantization:

| Model / stored order | Minimum | Median | 95th percentile | At/below 1e-6 |
| --- | ---: | ---: | ---: | ---: |
| v1 unigram backoff, decoded | 0.000001 | 0.000001 | 1 | 45,215 / 60,001 |
| v1 bigram backoff, decoded | 0.000001 | 0.000001 | 1 | 347,330 / 500,527 |
| v2 unigram backoff | 0.001241 | 0.364020 | 1 | 0 / 60,001 |
| v2 bigram backoff | 0.000670 | 0.653922 | 1 | 0 / 1,389,375 |
| v2-pruned unigram backoff | 0.001241 | 0.283123 | 1 | 0 / 60,001 |
| v2-pruned bigram backoff | 0.000670 | 0.351214 | 1 | 0 / 500,527 |

All maxima are 1. Decoded v2 artifact medians are 0.359336 and 0.650591,
respectively; neither decoded order has any weight at/below 1e-6. The distinction
between reference and decoded values is quantization, not a different estimate.

Both compiler runs verify 215 deterministic contexts. V2 checks 7,086 word/context
pairs, max/mean round-trip error 0.0766/0.0205 nats, loaded unigram mass 1.0004.
V2-pruned checks 7,016 pairs, max/mean error 0.0737/0.0212 nats, loaded mass 0.9987.
Reference unigram mass is 1.0000 for both. Neither uses the fallback discounts.
A second v2 build from the final code produces the same SHA-256.

## Same-input typing benchmark

Each run uses 40 sentences, 460 words, 416 misspelling pairs, one seed, and the
normal/sloppy presets. Input/config fingerprints match exactly after removing only
the intentionally different `model` identity field. The full source hashes are:

- Sentences: `5bd0a7fdc98f0e3594d641f1b8c224872f5f1247642b8fe60c770222a0264161`.
- Misspellings: `c2a4e961d8be548563d8d8558b402e3d35af839008992d8a0325d46cd7bc65b5`.
- Assets: `c56f158ef85efc21b828caff3368d68333b7816d178400f0d66cf9936e5ef122`.

All values below are percentages. Accuracy is no-strip exact word accuracy.
Next-word metrics use the harness's strip simulation. Spelling repair types each
wrong form exactly, bypassing touch noise, and is the same for both presets.

Normal preset:

| Model | Accuracy | False corrections | Mistyped recovery | Next-word top-1 | Next-word top-3 | Spelling repair | Clean spelling left alone |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| en-unigram | 91.96 | 1.90 | 78.62 | 6.22 | 12.68 | 70.19 | 99.76 |
| en-chat-v1 | 89.13 | 4.76 | 75.86 | 7.23 | 15.42 | 51.68 | 96.63 |
| en-chat-v2 | 92.83 | 0.63 | 78.62 | 7.45 | 15.14 | 55.53 | 99.76 |
| en-chat-v2-pruned | 93.26 | 1.59 | 82.07 | 7.23 | 14.94 | 58.89 | 99.52 |

Sloppy preset:

| Model | Accuracy | False corrections | Mistyped recovery | Next-word top-1 | Next-word top-3 | Spelling repair | Clean spelling left alone |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| en-unigram | 79.35 | 1.22 | 68.58 | 5.68 | 12.84 | 70.19 | 99.76 |
| en-chat-v1 | 74.13 | 7.93 | 64.19 | 7.29 | 14.32 | 51.68 | 96.63 |
| en-chat-v2 | 78.26 | 3.05 | 67.91 | 7.16 | 14.32 | 55.53 | 99.76 |
| en-chat-v2-pruned | 78.48 | 3.66 | 68.58 | 7.46 | 14.43 | 58.89 | 99.52 |

Unpruned v2 improves normal accuracy by 3.70 points, reduces false corrections by
4.13 points and improves mistyped recovery by 2.76 points versus v1. Its normal
next-word top-3 gain over unigram remains 2.46 points. However, exact spelling repair
is still 14.66 points below unigram. Under sloppy touches it also trails unigram in
accuracy, false corrections and recovery. V2-pruned narrows the spelling gap to 11.30
points but does not close it. Both fail the historical >=70.9% spelling target.

The old manual-lambda ablations do not predict the quality of fitted interpolation
weights. This run establishes that the collapse is gone; it does not establish that
this corpus and estimator yield an acceptable spelling model. No runtime policy,
lambda sweep, spelling-specific parameter or evaluation-derived training data was
used to improve the reported scores. Further investigation belongs in a separate
modeling/evaluation task before promotion.

## Validation evidence

- Python counter suite: 27 tests pass, including preserved singleton/doubleton rows
  and recorded pruning metadata.
- Full keyboard-core suite with compiler tests enabled: 262 tests pass. Estimator
  tests cover missing count buckets, invalid fits, fitting before pruning, exact
  fallback probabilities, and full-vocabulary normalization across every uni/bi
  context of small fitted, singleton and repeated-count distributions.
- Those distributions round-trip through the actual writer/loader, remain within
  0.1 nats, and retain full-context mass within 0.02 after quantization.
- Compiler gate tests reject a v1-style collapsed majority and accept sparse tiny
  lambdas/empty orders. A CLI probe separately verifies failure preserves a previous
  output file and leaves no candidate behind. The synthetic fixture and failure log
  are `/home/yvm/kbdata/en-chat-v2-gate-probe-8raz3rqi/`.

Logs and reports live at `/home/yvm/kbdata/en-chat-v2-{count,tests,python-tests,compile,final-compile}.log`,
`en-chat-v2-pruned-compile.log`, and the four `*-quick40.{json,md,log}` files in that
directory. Source hashes and TSV equivalence evidence are in
`en-chat-v2-input-sha256.json` and `en-chat-v2-count-audit.json`.

## Reproduction

Run from the checkout root. Gradle uses only the global-lock wrapper. Generated
counts, logs, JSON reports and model files stay outside the repository. Use a new
output directory/name if rerunning without replacing this experiment's outputs.

```sh
python3 -I android/tools/lm/count_ngrams.py \
  --out /home/yvm/kbdata/counts/en-chat-v2 \
  --vocab 60000 --min1 2 --min2 1 --min3 1 \
  /home/yvm/kbdata/raw/en/tatoeba/tatoeba_cc0_eng.txt.gz \
  /home/yvm/kbdata/raw/en/openassistant_oasst1/oasst1_eng_prompter.txt.gz \
  /home/yvm/kbdata/raw/en/wildchat/wildchat_eng_user.txt.gz

python3 -m unittest discover -s android/tools/lm -p 'test_*.py'
android/scripts/gradle-locked.sh -I tools/lm/compile-model.init.gradle :keyboard-core:test

android/tools/lm/build_model.sh \
  --counts /home/yvm/kbdata/counts/en-chat-v2 \
  --out /home/yvm/kbdata/models/en-chat-v2.wlm --vocab 60001

# Controlled topology ablation: v1's floors, but fit from all v2 count evidence.
android/tools/lm/build_model.sh \
  --counts /home/yvm/kbdata/counts/en-chat-v2 \
  --out /home/yvm/kbdata/models/en-chat-v2-pruned.wlm \
  --vocab 60001 --min2 3 --min3 3

for model in en-chat-v1 en-chat-v2 en-chat-v2-pruned; do
  android/scripts/gradle-locked.sh :keyboard-core:typingEval \
    "-PevalArgs=--engine=smart --model=/home/yvm/kbdata/models/$model.wlm --quick --limit=40 --seeds=1 --out=/home/yvm/kbdata/$model-quick40.json"
done
android/scripts/gradle-locked.sh :keyboard-core:typingEval \
  '-PevalArgs=--engine=smart --quick --limit=40 --seeds=1 --out=/home/yvm/kbdata/en-unigram-quick40.json'
```
