# count_ngrams.py

Count word unigrams, bigrams and trigrams from large text corpora for the
Wisperlow Kotlin language model. Pure Python 3 standard library; no
dependencies and no build step.

## Usage

```
python3 -I count_ngrams.py --out DIR [options] inputs...
```

`inputs...` are UTF-8 text files, plain or compressed with `.gz`, `.bz2` or
`.xz`. `-` reads standard input. Each line is one sentence or paragraph.

Options:

| Flag             | Default | Meaning                                                        |
| ---------------- | ------- | -------------------------------------------------------------- |
| `--out DIR`      | (required) | Output directory; created if missing.                       |
| `--max-lines N`  | none    | Stop after reading `N` lines (across all inputs).              |
| `--chunk-entries N` | 200000 | Spill the counting dicts after this many entries.           |
| `--vocab N`      | none    | Keep only the `N` most frequent words (`<s>` is always kept).  |
| `--min1 N`       | 2       | Drop unigrams with a smaller count.                            |
| `--min2 N`       | 3       | Drop bigrams with a smaller count.                             |
| `--min3 N`       | 3       | Drop trigrams with a smaller count.                            |
| `--temp-dir DIR` | temp    | Scratch directory for spilled chunk files.                      |
| `--keep-temp`    | off     | Keep chunk files for inspection.                               |
| `--progress-every N` | 100000 | Log progress every `N` lines to stderr.                   |

Output:

- `unigrams.tsv` — `surface<TAB>count`, sorted by count descending.
- `bigrams.tsv` — `w1<TAB>w2<TAB>count`.
- `trigrams.tsv` — `w1<TAB>w2<TAB>w3<TAB>count`.
- `stats.json` — lines, sentences, tokens, vocabulary size, n-gram row and
  occurrence counts, elapsed seconds.

Progress and diagnostics go to stderr, so stdout stays clean for scripting.

## Normalisation rules

1. **Unicode** — the whole line is NFC-normalised; curly apostrophes
   (`‘ ’ ‚ ‛ ′ ‵ ʼ ʻ`) become a plain `'`.
2. **Sentences** — split on `.` `!` `?` and line ends, plus the Devanagari and
   Kannada danda (`।` `॥`). Every sentence begins with the `<s>` token.
3. **Words** — maximal runs of letters and combining marks (Unicode
   categories `L*` and `M*`), in any script, with inner apostrophes kept:
   `don't`, `rock'n'roll`. Hyphens split words but do **not** break n-gram
   context, so `well-known` is the two words `well known`. Leading, trailing
   and doubled apostrophes are separators, not part of a word.
4. **Not words** — numbers (including decimals and thousands separators),
   URLs (`scheme://…`, `www.…`, `example.com`), emails, `@handles`, `#tags`,
   emoji and other symbols (`N*`/`S*`) break n-gram context: no n-gram spans
   them. Whitespace, punctuation and hyphens are plain separators.
5. **Casing** — n-grams are counted over lowercase keys. Each key tracks the
   surface forms of its non-sentence-initial occurrences and emits the most
   frequent one, so `the` beats `The` but `I`, `iPhone` and `Monday` keep
   their case. `<s>` is never cased. A key seen only at sentence starts falls
   back to its lowercase form.
6. **Memory** — counts live in bounded dicts; when `--chunk-entries` is
   reached they are sorted and spilled to the scratch directory and merged
   back with `heapq.merge`, summing counts. This keeps RAM flat on corpora far
   larger than physical memory; `--chunk-entries` trades memory for merge time.

## Tests

```
python3 -m unittest test_count_ngrams -v
```
