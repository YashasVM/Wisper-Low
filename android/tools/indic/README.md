# build_indic_pairs.py

Build small lowercase-Latin romanization pair tables (`hi.tsv`, `kn.tsv`)
for the Wisperlow transliteration research line
(`docs/keyboard/research/transliteration.md`). Pure Python 3 standard
library; no dependencies and no build step.

## Usage

```
python3 -I build_indic_pairs.py --out-dir DIR \
    [--hi-zip hin.zip] [--kn-zip kan.zip] \
    [--hi-dakshina hi.translit.sampled.train.tsv] \
    [--kn-dakshina kn.translit.sampled.train.tsv] [options]
```

At least one input flag is required. A language is built when either its
`--*-zip` or its `--*-dakshina` input is given, so the table can be produced
from Aksharantar alone, from Dakshina alone, or from both.

| Flag              | Default   | Meaning                                                   |
| ----------------- | --------- | --------------------------------------------------------- |
| `--hi-zip`        | (none)    | `aksharantar-hin.zip` (contains `hin_train.json`)          |
| `--kn-zip`        | (none)    | `aksharantar-kan.zip` (contains `kan_train.json`)          |
| `--hi-dakshina`   | (none)    | Dakshina v1.0 Hindi word lexicon `hi.translit.sampled.train.tsv` |
| `--kn-dakshina`   | (none)    | Dakshina v1.0 Kannada word lexicon `kn.translit.sampled.train.tsv` |
| `--out-dir DIR`   | (required)| Output directory; created if missing.                      |
| `--max-pairs N`   | 100000    | Max pairs per language.                                    |
| `--max-bytes N`   | 6000000   | Max output bytes per language (the 6 MB asset budget).     |
| `--split`         | train     | Only `train` is accepted; dev/test are never read.         |
| `--dry-run`       | off       | Report counts without writing files.                       |

Progress goes to stderr; the assets go to `<out-dir>/hi.tsv` and `kn.tsv`.

### Rebuilding the committed assets

The committed `hi.tsv`/`kn.tsv` are built from the Aksharantar per-language
zips **and** the Dakshina v1.0 word-lexicon train TSVs (train split only):

```
python3 -I android/tools/indic/build_indic_pairs.py \
    --hi-zip /path/to/hin.zip \
    --kn-zip /path/to/kan.zip \
    --hi-dakshina /path/to/dakshina/hi/lexicons/hi.translit.sampled.train.tsv \
    --kn-dakshina /path/to/dakshina/kn/lexicons/kn.translit.sampled.train.tsv \
    --out-dir android/app/src/main/assets/indic \
    --max-pairs 100000 --max-bytes 6000000 --split train
```

The Dakshina files come from `dakshina_dataset_v1.0.tar`
(`<lang>/lexicons/<lang>.translit.sampled.train.tsv`). The builder refuses any
Dakshina path that is not a `train` lexicon (dev/test lexicons and the
full-sentence `romanized/` tree are rejected at the CLI and in the library),
so no evaluation data can reach the asset.

## Output format

UTF-8, NFC-normalized, tab-separated, one pair per data line plus a `#`-comment
provenance header (source URLs, input sha256s, split, licenses, generation
command):

```
roman<TAB>native<TAB>positive_count

namaste	नमस्ते	1
kal	कल	2
```

- `roman` — lowercase ASCII `a-z` only (the typing-engine key alphabet).
- `native` — Devanagari (hi) / Kannada (kn) word: letters and combining
  marks, ZWJ/ZWNJ removed, NFC.
- `count` — positive attestation count of this exact pair **within its
  source** (Aksharantar rows counted by aggregation, Dakshina rows carrying
  the lexicon's attestation count). The two sources come from different
  collection methods, so their counts are never summed or compared; on the
  (unobserved) collision of an identical pair the Dakshina count is kept.

Rows are emitted by **source priority**: every valid Dakshina train-lexicon
pair is placed first (natives ordered by Dakshina count, descending, ties
lexicographic; within a native, Dakshina roman alternatives ranked by count
then alphabetically), then the Aksharantar mined pairs fill the remaining
budget (natives ordered by mined count, descending). At most 8 roman
alternatives per native are kept so one popular word cannot flood the cap.
The pipelines are deterministic: identical inputs and options produce
byte-identical files.

## Source data and licensing

Two openly licensed sources feed the tables; the combined asset is distributed
under **CC BY-SA 4.0**.

- **Aksharantar train split** (AI4Bharat IndicXlit project,
  https://indicnlp.ai4bharat.org/aksharantar/, Madhani et al. 2022/2023). Rows
  are used **only** when their `source` field is one of the mined CC0
  sources — Wikidata, Samanantar, IndicCorp — per the paper: *"all the mined
  data is available under the CC0 license"*. Excluded on license grounds:
  `source Dakshina` (CC BY-SA 4.0, third-party), `AK-Freq` and manual rows
  (CC BY 4.0, also benchmark/evaluation data), and `Existing` (compilation of
  prior corpora with unverifiable per-row licenses). These rows remain
  available from their source under CC0 1.0.
- **Dakshina v1.0 train lexicon** (Google Research,
  https://github.com/google-research-datasets/dakshina, Roark et al., LREC
  2020, pp. 2413–2423). The dataset README licenses it **CC BY-SA 4.0**. The
  word-lexicon `*.train.tsv` files are used (25,000 native word types each for
  Hindi and Kannada); dev/test lexicons and the full-sentence `romanized/` tree
  are never read.

Because the combined file incorporates Dakshina (CC BY-SA 4.0), the asset as a
whole is **CC BY-SA 4.0** with attribution to both datasets and a modification
note; the Aksharantar-derived rows are still credited under their CC0 source.
Attribution/provenance ships in
`android/app/src/main/assets/licenses/indic-romanization.txt` and in each TSV
header. The surrounding Wisperlow application stays MIT-licensed and is not
relicensed by this data asset.

Acceptance filters (unit-tested): lowercase Latin roman keys only; native
words consist of letters + combining marks inside the language's script
block; URLs, emails, dotted domains, control/format characters, digits,
punctuation, mixed-script words, malformed JSON rows and malformed Dakshina
rows (wrong column count, non-integer/zero/negative count) are rejected and
counted. NFC is applied to both sides before filtering; every emitted native
idempotently NFC-normalizes.

## Tests

```
python3 -m unittest test_build_indic_pairs -v
```

44 tests cover format, filters, NFC, count aggregation and dedup, source
priority (Dakshina coverage ahead of the mined fill), caps, determinism, and
TRAIN-only split discipline (Aksharantar dev/test members and Dakshina
dev/test/sentence files are never read) against tiny in-memory zips and
fixture TSVs. A committed-asset acceptance test also checks the attested
Dakshina pairs (for example `hai→है`, `namaste→नमस्ते`, `mattu→ಮತ್ತು`,
`namaskara→ನಮಸ್ಕಾರ`) are present with positive, NFC-normalized natives.
