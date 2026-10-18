# Latin regional dictionaries

These tables support Hindi/Kannada words typed and kept in English letters.
They contain no native-script output and do not define a canonical romanization.
Alternative spellings and unknown words must remain typeable unchanged. The
user's supplied phrases are validation examples, never training overrides.

`build_latin_regional.py` uses Python 3 standard-library modules and a temporary
SQLite database to stream exact source counts without retaining a corpus in RAM.
It emits UTF-8 files with `#` provenance comments:

- `dict/{hi,kn}_latn.tsv`: `lowercase_ascii_word<TAB>positive_score`
- `dict/{hi,kn}_latn_bigrams.tsv`: `previous<TAB>following<TAB>positive_score`

Scores are **ranking evidence, not raw token frequencies**. A word receives
a score of `min(4 * real_count + min(synthetic_count, 3), 999999)` if absent from
conversational data. Conversational words instead receive
`1000000 + min(16 * conversation_count + min(real_count, 3) + min(synthetic_count, 3), 999999)`.
This makes conversational words rank ahead of web-only empty-context suggestions
and prevents web popularity from overwhelming conversation counts. A
train-lexicon word absent from all corpora receives membership floor 1. Dakshina annotator
counts are discarded. Words seen only once in synthetic text are excluded unless
also attested in the train lexicon. When conversational inputs are supplied, bigrams use **only conversational
training occurrences**, scoring each occurrence 16. They require both words in
the output vocabulary. Web/synthetic text cannot introduce next-word pairs. The
optional corpus-only CLI mode requires two real or five synthetic occurrences.
Within each sentence, repeats contribute at most three counts per word/pair.

The default bounds are 300,000 real corpus lines and 100,000 synthetic lines,
30,000 words and 50,000 bigrams per language. Kannada's real shard has only
62,306 lines, so that entire shard is processed. Sorting is score descending,
then alphabetically; identical source files/options produce identical bytes.
Comments record exact source-file hashes, consumed-line counts and filters.
Case/whitespace-normalized identical conversational comments are deduplicated
across both Kannada train sources. Pairs consisting only of the top 2,000
English-frequency words are removed when `--english` is supplied; those remain
typeable and English suggestions still come from the separate English lexicon.

## Rebuild

Run from the repository root, replacing `/home/yvm/kbdata` if needed:

```bash
python3 -I android/tools/indic/build_latin_regional.py \
  --language kn \
  --real /home/yvm/kbdata/derived/kn/fineweb2_kan_Latn_sentences.txt.gz \
  --synthetic /home/yvm/kbdata/derived/kn/sangraha_synthetic_latn_sentences.txt.gz \
  --fallback /home/yvm/kbdata/raw/kn/dakshina/lexicons/kn.translit.sampled.train.tsv \
  --conversation-train /home/yvm/kbdata/raw/kn/conversational/train_hope.csv \
  --conversation-train /home/yvm/kbdata/raw/kn/conversational/kannada_offensive_train.csv \
  --english android/app/src/main/assets/dict/en.txt \
  --out-dir android/app/src/main/assets/dict

python3 -I android/tools/indic/build_latin_regional.py \
  --language hi \
  --real /home/yvm/kbdata/derived/hi/fineweb2_hin_Latn_sentences.txt.gz \
  --synthetic /home/yvm/kbdata/derived/hi/sangraha_synthetic_latn_sentences.txt.gz \
  --fallback /home/yvm/kbdata/raw/hi/dakshina/lexicons/hi.translit.sampled.train.tsv \
  --conversation-train /home/yvm/kbdata/raw/hi/conversational/TN_train.csv \
  --english android/app/src/main/assets/dict/en.txt \
  --out-dir android/app/src/main/assets/dict

python3 -m unittest discover -s android/tools/indic -p test_build_latin_regional.py -v
```

The only Dakshina input accepted is `*.translit.sampled.train.tsv`. The
`romanized/` full-sentence files are validation data and are never inputs.
No eval/test/dev file or keyboard benchmark phrase trains these assets.
The builder does not read native-script Wikipedia, Aksharantar or native tables.

Conversational CSV schemas are deliberately explicit: KanHope `train_hope.csv`
uses `sentence` for classes `Hope`/`Not-Hope` (excludes `not-Kannada`);
CodeMix `kannada_offensive_train.csv` is tab-separated and uses only
`Not_offensive` rows; COMI-LINGUA `TN_train.csv` uses the original `Sentences`
column, **not** any canonicalized normalization target/annotator column. The
builder refuses other conversational filenames. Input labels are not tokens.

## Sources and limits

- **KanHope**, Kannada hope speech train comments, Hande et al.,
  <https://zenodo.org/records/5006517>, CC BY 4.0 (Zenodo API metadata verified).
- **DravidianCodeMix**, Kannada offensive-language train comments,
  Chakravarthi et al., <https://zenodo.org/records/4750858>, CC BY 4.0
  (Zenodo API metadata verified). Only `kannada_offensive_train.csv` is extracted
  from `DravidianCodeMix-2020.zip`; no dev/test/all-data CSV is read.
- **COMI-LINGUA**, IIT Gandhinagar, Hindi-English normalization train input,
  <https://github.com/lingo-iitgn/COMI-LINGUA>. The README prose says CC BY 4.0,
  while the README badge and actual `LICENSE.txt` specify CC BY-SA 4.0. These
  derivatives retain the stricter **CC BY-SA 4.0** notice. Only `TN_train.csv`
  original input text is used.
- HuggingFaceFW **FineWeb2**, Hindi/Kannada Latin-script train shards,
  <https://huggingface.co/datasets/HuggingFaceFW/fineweb-2>, ODC-By 1.0.
- AI4Bharat **Sangraha**, synthetic Hindi/Kannada Latin Wikipedia shards,
  <https://huggingface.co/datasets/ai4bharat/sangraha>, CC BY 4.0.
- Google Research **Dakshina v1.0**, Hindi/Kannada **train word lexicons**,
  <https://github.com/google-research-datasets/dakshina>, CC BY-SA 4.0.

Attribution and modification notices ship in
`app/src/main/assets/licenses/latin-regional.txt`. The combined derivative tables
are distributed under CC BY-SA 4.0, with the other sources' notices retained.
The application code remains under its existing license.

These are starter suggestion dictionaries. FineWeb's Kannada Latin shard is
strongly biased towards stories, lyrics, English text and explicit material;
synthetic Wikipedia favors formal spellings and names. The builder excludes
lines containing a small explicit-token/phrase list observed in this dump. It
also breaks word sequences at mixed-script/number tokens, sentence punctuation,
URLs and emails. That list is an auditable first filter, not a complete content
classifier. Conversational sources are predominantly public social-media movie
comments, which adds chat spellings but still has topic and speaker bias. The
filter does not affect literal typing or personal words. English and
formal vocabulary remains in the tables; they are not a clean conversational
Kanglish/Hinglish corpus and should not drive aggressive autocorrection.

Synthetic contribution caps prevent a frequent artificial spelling from gaining
unbounded confidence. They do not establish that an unknown spelling is wrong.
A large corpus count must never authorize native-script conversion or silently
replace a person's valid phonetic spelling.
