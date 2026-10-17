# Evaluation data

Everything here is for the typing simulator (`:keyboard-core:typingEval`) and its
unit tests only. **Never train the engine on these files, and never ship them in the
app**: the word list and language model must keep learning from real text, and this
corpus is deliberately small and biased towards the sentences the eval checks.

- `*.txt` — sentences, one per line. Blank lines and lines starting with `#` are
  skipped. Every `.txt` in this directory is loaded on every run, sorted by file
  name, so adding a corpus (`everyday.txt`, `prompting.txt`, `texting.txt`, ...)
  changes the run. The English directory currently holds `everyday.txt`,
  `prompting.txt`, `sample.txt` and `texting.txt`.
- `*misspellings*.tsv` — `wrong<TAB>right` pairs, one per line, `#` comments allowed.
  The right side must be a single word. The spelling metric types the wrong side
  exactly, and the noise presets may type it in place of the right word. Pairs are
  de-duplicated and sorted by `(wrong, right)` so a run's pair order is stable.

## Reproducibility

Every run writes a **fingerprint** describing exactly what its numbers mean: SHA-256
of the ordered sentences used and the ordered pairs, the seed count, the full preset
noise parameters, the engine, the model and the SHA-256 of the word-list assets. The
runner prints deltas against a prior report **only when the fingerprints match**;
otherwise it suppresses them and says why. This is what stops an old `sample.txt`
baseline from being diffed against the current `everyday/prompting/texting` corpus.
Strip latency is a single-machine measurement, is never diffed, and is reported as a
limitation rather than a comparison.

## The reports

The runner commits its numbers next to itself in `android/keyboard-core/eval/`
(`baseline.json`, `baseline.md`); those are results, not data. The metric names in a
report are honest about what was measured:

- **false autocorrections** — of words whose raw typed literal (hit-tested with no
  dictionary) matched the intended word, the share the engine changed anyway. A tap
  that lands on the wrong key is a touch miss, never a false autocorrection.
- **raw typed-wrong rate** — share of intended words whose raw literal was wrong for
  any reason: a planned slip, a misspelling substitution, or a touch mislanding.
- **of which touch mislandings** — the inside view: raw literal differs from the
  planned literal because the touch missed its key.
- **`exact` preset** — the true zero-noise control (sigma 0, no mistakes). `precise`
  is NOT zero noise: it still draws Gaussian noise at sigma 0.15.
