# build_indic_pairs.py

Build small lowercase-Latin romanization pair tables (`hi.tsv`, `kn.tsv`)
for the Wisperlow transliteration research line
(`docs/keyboard/research/transliteration.md`). Pure Python 3 standard
library; no dependencies and no build step.

## Usage

```
python3 -I build_indic_pairs.py --out-dir DIR [--hi-zip hin.zip] [--kn-zip kan.zip] [options]
```

| Flag           | Default   | Meaning                                                   |
| -------------- | --------- | --------------------------------------------------------- |
| `--hi-zip`     | (none)    | `aksharantar-hin.zip` (contains `hin_train.json`)          |
| `--kn-zip`     | (none)    | `aksharantar-kan.zip` (contains `kan_train.json`)          |
| `--out-dir DIR`| (required)| Output directory; created if missing.                      |
| `--max-pairs N`| 100000    | Max pairs per language.                                    |
| `--max-bytes N`| 6000000   | Max output bytes per language (the 6 MB asset budget).     |
| `--split`      | train     | Only `train` is accepted; dev/test are never read.         |
| `--dry-run`    | off       | Report counts without writing files.                       |

Progress goes to stderr; the assets go to `<out-dir>/hi.tsv` and `kn.tsv`.

## Output format

UTF-8, NFC-normalized, tab-separated, one pair per data line plus a `#`-comment
provenance header (source URL, member sha256, split, licenses, generation
command):

```
roman<TAB>native<TAB>positive_count

namaste	नमस्ते	1
kal	कल	2
```

- `roman` — lowercase ASCII `a-z` only (the typing-engine key alphabet).
- `native` — Devanagari (hi) / Kannada (kn) word: letters and combining
  marks, ZWJ/ZWNJ removed, NFC.
- `count` — attestations of this exact pair in the train split (Dakshina-style
  frequency counts were verified absent from this dataset, so counts are
  attestation counts; all are ≥ 1).

Natives are ranked by total attestation frequency, ties lexicographic; within
a native the roman alternatives are ranked by count then alphabetically. At
most 8 roman alternatives per native are kept so one popular word cannot
flood the cap. The pipelines are deterministic: identical inputs and options
produce byte-identical files.

## Source data and licensing

Input is the Aksharantar train split (AI4Bharat IndicXlit project,
https://indicnlp.ai4bharat.org/aksharantar/, Madhani et al. 2022/2023). Rows
are used **only** when their `source` field is one of the mined CC0 sources —
Wikidata, Samanantar, IndicCorp — per the paper: *"all the mined data is
available under the CC0 license"*. Excluded on license grounds: `source
Dakshina` (CC BY-SA 4.0, third-party), `AK-Freq` and manual rows (CC BY 4.0,
also benchmark/evaluation data), and `Existing` (compilation of prior corpora
with unverifiable per-row licenses). Attribution/provenance ships in
`android/app/src/main/assets/licenses/indic-romanization.txt`.

Acceptance filters (unit-tested): lowercase Latin roman keys only; native
words consist of letters + combining marks inside the language's script
block; URLs, emails, dotted domains, control/format characters, digits,
punctuation, mixed-script words and malformed JSON rows are rejected and
counted. NFC is applied to both sides before filtering; every emitted native
idempotently NFC-normalizes.

The local Dakshina `dakshina_dataset_v1.0.tar` was found **truncated**
(unreadable after the `hi.lexicons` dev member; the Hindi train lexicon and
the whole Kannada tree are unrecoverable from the copy), so its
CC BY-SA-licensed 25k-word lexicon could not be used; the builder therefore
relies on Aksharantar mined rows alone. The minable downside: the mined
subsets are name/entity-heavy and contain very few core function words —
worth revisiting if a complete Dakshina copy with real attestation counts
ever arrives.

## Tests

```
python3 -m unittest test_build_indic_pairs -v
```

Covers format, filters, NFC, count aggregation, split discipline (dev/test
members are never read), caps and determinism against tiny in-memory zips.
