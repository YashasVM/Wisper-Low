# Indic (Hindi / Kannada) data research for Wisperlow keyboard models

**Goal:** collect open-licensed Hindi (hi) and Kannada (kn) text to build (1) native-script language models for word/n-gram statistics and next-word prediction, (2) Hinglish and romanized-Kannada Latin-script models, and (3) romanization pair resources for transliteration. English precedent: `docs/keyboard/research/data-english.md`.

**License policy (hard rule):** derived counts/lexicons ship inside an MIT app on Google Play, so each source must allow that.

- **OK:** CC0, CC-BY, CC-BY-SA, ODC-By, Apache-2.0, MIT.
- **Reject:** NC, ND, research-only, unclear, or unverifiable licenses.

Downloads live only under `/home/yvm/kbdata/raw/{hi,kn}/<source>/`, never in the repo. Every download is treated as untrusted data: streamed with `python -I`, never executed, converted to NFC one-sentence-per-line `.txt.gz`. Full download log (URLs, dates, bytes, line counts): `/home/yvm/kbdata/README.md`. Budget: ≤ 10 GB downloads; ~1–3 GB native text per language. **Total Indic downloads: 5,772,071,811 bytes stored (+~111 MB FineWeb-2 native row groups fetched remotely) ≈ 5.88 GB.**

> Note: the transliteration asset builder consumes the Dakshina **train** lexicon TSVs directly from the raw paths below and owns its inputs; this document records provenance only — do not modify `assets/` or the builder from here. The builder must consume train-only inputs.

---

## Source-by-source evaluation and verdict (license quotes verified at source)

| Source | License & primary-source citation (verified) | Verdict | Why / notes |
|---|---|---|---|
| **Dakshina** (Google) | CC BY-SA 4.0 — official README License section, https://github.com/google-research-datasets/dakshina#license: *"The dataset is licensed under CC BY-SA 4.0. Any third party content or data is provided 'As Is' without any warranty, express or implied."* | **ACCEPT** | On allowlist; full tar downloaded (2,008,340,480 B). Lexicons + native wiki text + romanized wiki text. |
| **FineWeb-2** (Hugging Face) | ODC-By 1.0 — dataset's published license statement: README metadata `license: odc-by` at https://huggingface.co/datasets/HuggingFaceFW/fineweb-2 (license text: https://opendatacommons.org/licenses/by/1-0/) | **ACCEPT** | On allowlist; native `hin_Deva` / `kan_Knda` read via HTTP range; Latin shards downloaded. |
| **IndicCorp v2** (AI4Bharat) | CC0 — dataset README, https://huggingface.co/datasets/ai4bharat/IndicCorpV2 (License section): *"All the datasets created as part of this work will be released under a CC-0 license and all models & code will be release under an MIT license"* | **ACCEPT** | Public domain; largest clean native corpus per language. |
| **Sangraha** (AI4Bharat) | CC BY 4.0 — dataset README, https://huggingface.co/datasets/ai4bharat/sangraha, metadata `license: cc-by-4.0` (https://creativecommons.org/licenses/by/4.0/) | **ACCEPT** | Verified + synthetic splits used; unverified kn raw kept but its conversion excluded from the native budget. |
| **Aksharantar** (AI4Bharat/IITM/Microsoft) | Mixed CC-BY / CC0 — AI4Bharat paper *"Aksharantar: Open Indic-language Transliteration datasets and models for the Next Billion Users"* (Madhani et al.), license statement in §1 with footnotes to opensource.org/licenses/MIT, creativecommons.org/licenses/by/4.0 and creativecommons.org/publicdomain/zero/1.0: *"The code and models are available under an MIT license, the Aksharantar benchmark and all data we created manually are available under the CC-BY license, whereas all the mined data is available under the CC0 license."* Dataset: https://huggingface.co/datasets/ai4bharat/Aksharantar | **ACCEPT** | Romanization pairs for hi/kn; rows are **mixed provenance** (see pairs section) — CC0 applies only to the mined portion. |
| **Wikipedia (hi, kn)** | CC BY-SA 4.0 + GFDL — Wikimedia Foundation Terms of Use §7, https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use: *"When you submit text to which you hold the copyright, you agree to license it under: Creative Commons Attribution-ShareAlike 4.0 International License ("CC BY-SA 4.0"), and GNU Free Documentation License ("GFDL") … Reusers may comply with either license or both."* | **ACCEPT** | Official dumps from dumps.wikimedia.org. |
| **IndicXlit** (AI4Bharat) | MIT — AI4Bharat IndicXlit paper (github.com/AI4Bharat/IndicXlit): *"The code and models are available under an MIT license"* (models trained on Aksharantar) | **ACCEPT (reference)** | Not downloaded; license context for Aksharantar-derived work. |
| CulturaX | Gated/restricted; no license metadata published | **REJECT** | License unclear → not verifiable at source. |
| L3Cube-HingCorpus | CC BY-NC-SA 4.0 — github.com/l3cube-pune/code-mixed-nlp: *"L3Cube-HingCorpus, and L3Cube-HingLID is licensed under a Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License."* | **REJECT** | NC is not on the allowlist. |
| Samanantar | CC BY-NC 4.0 (`cc-by-nc-4.0` on the dataset card, confirmed) | **REJECT** | NC; standalone exclude (its mined rows inside Aksharantar are released CC0 by the Aksharantar authors). |
| Leipzig Corpora | Conflicting CC BY vs CC BY-NC statements on official pages; wortschatz/corpora pages behind Anubis bot protection | **REJECT** | Cannot verify license at source → unclear. |

---

## What was downloaded and where

All under `/home/yvm/kbdata/raw/{hi,kn}/<source>/` (never the repo). Converted outputs under `/home/yvm/kbdata/derived/{hi,kn}/`. Connectors: `venv/bin/python -I tools/conv_*.py` (textnorm = NFC + danda/!?/dot sentence split).

### Raw downloads — Hindi (`raw/hi/`)

| Source | File | License | Bytes | Notes |
|---|---|---|---:|---|
| Dakshina (shared hi+kn) | `dakshina/dakshina_dataset_v1.0.tar` | CC BY-SA 4.0 | 2,008,340,480 | sha256 `83530bec211893d9df429298f42e63c350c25ad56f9f5172f85edb0575ee5b03`, `tar -tf` OK (351 members); subtrees extracted: `lexicons/` 1,579,951 B, `native_script_wikipedia/` 267,393,228 B, `romanized/` 20,290,345 B |
| Aksharantar | `aksharantar/hin.zip` | CC-BY / CC0 | 33,100,386 | |
| IndicCorp v2 | `indicorp_v2/hi_indicorp_chunk_00..05.txt` | CC0 | 805,306,368 (6×134,217,728) | from `data/hi-1.txt` |
| Sangraha verified | `sangraha/verified_hin_data-0.parquet` | CC BY 4.0 | 378,091,240 | native Devanagari |
| Sangraha synthetic | `sangraha/synthetic_hin_Latn_wiki_0000.parquet` | CC BY 4.0 | 180,430,131 | Latin script (Hinglish) |
| FineWeb-2 Latin | `fineweb2/hin_Latn_train_0000.parquet` | ODC-By 1.0 | 198,462,618 | |
| FineWeb-2 native | *(remote ranges only, no local raw file)* | ODC-By 1.0 | ~55,000,000 | `data/hin_Deva/train/000_00000.parquet`, converted on the fly |
| Wikipedia | `wikipedia/hiwiki-latest-pages-articles.xml.bz2` | CC BY-SA 4.0 + GFDL | 241,701,076 | |

### Raw downloads — Kannada (`raw/kn/`)

| Source | File | License | Bytes | Notes |
|---|---|---|---:|---|
| Dakshina (shared, extracted) | `dakshina/lexicons/` 2,389,790 B; `native_script_wikipedia/` 157,603,425 B; `romanized/` 21,746,303 B | CC BY-SA 4.0 | — | from the shared tar above |
| Aksharantar | `aksharantar/kan.zip` | CC-BY / CC0 | 87,634,360 | |
| IndicCorp v2 | `indicorp_v2/kn_indicorp_chunk_00..05.txt` | CC0 | 805,306,368 (6×134,217,728) | from `data/kn.txt` |
| Sangraha verified | `sangraha/verified_kan_data-0.parquet` | CC BY 4.0 | 368,175,508 | native Kannada |
| Sangraha unverified | `sangraha/unverified_kan_data-0.parquet` | CC BY 4.0 | 365,535,828 | **raw kept, conversion excluded** from native budget (3,263,687-sentence derived file deleted) |
| Sangraha synthetic | `sangraha/synthetic_kan_Latn_wiki_0000.parquet` | CC BY 4.0 | 181,940,527 | Latin script (romanized Kannada) |
| FineWeb-2 Latin | `fineweb2/kan_Latn_train_0000.parquet` | ODC-By 1.0 | 2,406,737 | |
| FineWeb-2 native | *(remote ranges only, no local raw file)* | ODC-By 1.0 | ~56,000,000 | `data/kan_Knda/train/000_00000.parquet` |
| Wikipedia | `wikipedia/knwiki-latest-pages-articles.xml.bz2` | CC BY-SA 4.0 + GFDL | 115,640,184 | |

### Derived outputs (`derived/{hi,kn}/`, NFC, one sentence per line)

| File (per language) | hi lines | kn lines | hi bytes | kn bytes |
|---|---:|---:|---:|---:|
| `dakshina_native_wikipedia_sentences.txt.gz` | 1,102,041 | 716,148 | 64,258,411 | 43,014,365 |
| `dakshina_romanized_sentences.txt.gz` (validation-page origin, **eval/analysis only**) | 31,815 | 30,869 | 1,322,001 | 1,345,834 |
| `indicorp_v2_sentences.txt.gz` | 3,162,472 | 3,284,421 | 187,191,124 | 183,504,988 |
| `sangraha_verified_sentences.txt.gz` | 4,003,300 | 3,888,621 | 208,525,796 | 194,595,513 |
| `sangraha_synthetic_latn_sentences.txt.gz` | 2,560,005 | 2,499,703 | 105,422,525 | 105,075,296 |
| `fineweb2_hin_Deva / fineweb2_kan_Knda_sentences.txt.gz` | 701,274 | 808,121 | 38,627,255 | 39,971,327 |
| `fineweb2_hin_Latn / fineweb2_kan_Latn_sentences.txt.gz` | 3,210,692 | 62,306 | 182,595,348 | 2,054,990 |
| `wikipedia_sentences.txt.gz` | 3,637,523 (215,954 pages → 277 MB text) | 1,974,112 (36,969 pages) | 140,021,749 | 87,949,273 |

Pair files: `dakshina_lexicon_pairs.tsv` (hi 53,064 / kn 60,698 rows; **mixed splits — analysis only**), `dakshina_sentence_pairs.tsv` (21,567 / 20,209; **eval/analysis only**, validation-page origin), `dakshina_token_pairs.tsv` (353,042 / 221,354; **eval/analysis only**, validation-page origin), `hin_aksharantar_pairs.tsv` (1,315,624), `kan_aksharantar_pairs.tsv` (2,925,133; production-eligible only after CC0-mined source filter, see below).

---

## File layouts

### Dakshina transliteration lexicon (the production resource)

Path: `/home/yvm/kbdata/raw/{hi,kn}/dakshina/lexicons/{hi,kn}.translit.sampled.{train,dev,test}.tsv` — **tab-separated, 3 columns, no header, no empty lines, every row has exactly 3 fields** (verified):

| Column | Meaning | Example |
|---|---|---|
| 1 | native-script word (Devanagari / Kannada) | `अंकगणित` / `ಅಂಕ` |
| 2 | romanization | `ankganit` / `anka` |
| 3 | **attestations** — number of human annotators who agreed on this exact (word, romanization) pair (an agreement count per pair), *not* corpus word frequency | `3` / `3` |

**Count semantics:** the train lexicon covers **25,000 native word types** (official Dakshina README, lines 251–275), but the train TSV has more lines than types because a single word can have several accepted romanizations (hi train: **44,204 lines / 1,326,377 B**; kn train: **50,624 lines / 2,004,624 B**). Dev and test lexicons hold 2,500 word types each (hi dev 4,358 lines / 125,512 B, hi test 4,502 / 128,062 B; kn dev 5,027 / 190,913 B, kn test 5,047 / 194,253 B).

**Split rule (hard):** production training uses **only** `*.train.tsv`. Dev/test are evaluation-only and must never enter production data; the builder must consume train-only inputs. Likewise `derived/*/dakshina_lexicon_pairs.tsv` concatenates all splits with a **4th split column** (`train`/`dev`/`test`) — analysis only, never a production input.

**Romanized-sentence origin (hard fact, official Dakshina README lines 251–275):** **all** romanized sentences in Dakshina originate from **validation pages** and are then randomly split into dev/test — there is **no romanized-sentence training portion**. Consequently every romanized-text artifact derived from them (`dakshina_romanized_sentences.txt.gz`, `dakshina_sentence_pairs.tsv`, `dakshina_token_pairs.tsv`) is **analysis/eval-only** and must never enter production training.

### Other pair files

- `dakshina_sentence_pairs.tsv` — **analysis/eval only**: `native sentence \t romanized sentence \t tag` (tag `rejoined`). Derived from the rejoined/aligned romanized files, which originate from **validation pages** (official README lines 251–275); there is no romanized-sentence training portion.
- `dakshina_token_pairs.tsv` — **analysis/eval only**: `native token \t romanized token \t tag` (tag `aligned`), same validation-page origin.
- `{hin,kan}_aksharantar_pairs.tsv`: `native word \t romanized word \t source` where source ∈ {Dakshina, AK-Freq, Existing, Wikidata, Samanantar, IndicCorp, AK-NEF, AK-NEI}. **Mixed provenance — not uniformly CC0.** Row counts: hi {IndicCorp 958,614; Samanantar 154,110; Existing 131,773; Wikidata 25,012; Dakshina 31,295; AK-Freq 12,806; AK-NEI 1,188; AK-NEF 826}; kn {IndicCorp 2,830,544; Dakshina 51,117; Samanantar 36,923; AK-Freq 4,213; AK-NEI 1,274; AK-NEF 878; Wikidata 184}. Under the paper's scheme, only the **mined** rows are CC0; rows sourced from `Wikidata`/`Samanantar`/`IndicCorp` are the mined subset (the builder filters to exactly this subset). Provenance of the non-mined rows: `Dakshina`-sourced rows duplicate Dakshina content (CC BY-SA 4.0); `AK-Freq` rows are manually collected (CC BY 4.0); `Existing` rows have **unverified mixed third-party origin** and are **excluded** as unclear — do not assign them a license. De-duplicate against the Dakshina train lexicon when merging.

---

## Build pipelines

All converters run isolated: `/home/yvm/kbdata/venv/bin/python -I tools/<script>.py …` (streamed, batch-wise, ~1 GB RAM).

### 1. Hindi native-language model

Inputs (native Devanagari only): `derived/hi/{dakshina_native_wikipedia, indicorp_v2, sangraha_verified, wikipedia, fineweb2_hin_Deva}_sentences.txt.gz` → **12,606,610 sentences**. Already NFC, one sentence/line. (Do not add `dakshina_romanized_sentences.txt.gz`: validation-page origin, eval-only.)

```bash
zcat derived/hi/{dakshina_native_wikipedia,indicorp_v2,sangraha_verified,wikipedia,fineweb2_hin_Deva}_sentences.txt.gz \
  | gzip > hi_native_corpus.txt.gz        # then count n-grams / train KenLM-style model
```

### 2. Kannada native-language model

Inputs: `derived/kn/{dakshina_native_wikipedia, indicorp_v2, sangraha_verified, wikipedia, fineweb2_kan_Knda}_sentences.txt.gz` → **10,671,423 sentences**. Excludes `sangraha_unverified` (raw kept, conversion intentionally dropped to respect the 3 GB native cap).

### 3. Hinglish LM (Latin-script Hindi input)

Inputs: `derived/hi/{fineweb2_hin_Latn, sangraha_synthetic_latn}_sentences.txt.gz` → **5,770,697 sentences**. Note `sangraha_synthetic_latn` is Wikipedia translated+transliterated — good spelling coverage, so down-weight vs. crawled `fineweb2_hin_Latn` if style matters. Dakshina's romanized text (`raw/hi/dakshina/romanized/`, `derived/hi/dakshina_romanized_sentences.txt.gz`) has **no training portion** (validation-page origin) — usable only as an eval set for Hinglish spelling.

### 4. Romanized-Kannada LM

Inputs: `derived/kn/{sangraha_synthetic_latn, fineweb2_kan_Latn}_sentences.txt.gz` → **2,562,009 sentences**. Small crawled base (62,306) — synthetic dominates; treat as spelling/frequency prior, not style corpus. Dakshina's romanized text has **no training portion** (validation-page origin) — eval-only.

### 5. Transliteration pairs

- **Primary lexicon:** raw train TSVs only — `raw/hi/dakshina/lexicons/hi.translit.sampled.train.tsv` (44,204 rows) and `raw/kn/dakshina/lexicons/kn.translit.sampled.train.tsv` (50,624 rows). Column 3 (annotator agreement) can weight candidate ranking; dev/test TSVs reserved strictly for evaluation.
- **Scale-out pairs (production-eligible):** `derived/{hi,kn}/*_aksharantar_pairs.tsv`, filtered to the CC0-mined provenance subset (`source` ∈ {Wikidata, Samanantar, IndicCorp}); de-duplicate vs. the Dakshina train lexicon on merge. Non-mined rows: `Dakshina` (CC BY-SA 4.0), `AK-Freq` (CC BY 4.0); `Existing` rows excluded (unverified mixed third-party origin).
- **Analysis/eval only (never production):** `derived/{hi,kn}/dakshina_{sentence,token}_pairs.tsv` (validation-page origin, no training portion) and `derived/*/dakshina_lexicon_pairs.tsv` (mixed splits).

---

## App attribution text (ship in app licenses / about screen)

Primary (n-gram / language model data):

> Language data for Hindi and Kannada: Dakshina dataset by Google Research (CC BY-SA 4.0, https://github.com/google-research-datasets/dakshina); FineWeb-2 by Hugging Face (ODC-By 1.0, https://huggingface.co/datasets/HuggingFaceFW/fineweb-2); IndicCorp v2 by AI4Bharat (CC0, https://huggingface.co/datasets/ai4bharat/IndicCorpV2); Sangraha by AI4Bharat (CC BY 4.0, https://huggingface.co/datasets/ai4bharat/sangraha); data from Hindi Wikipedia and Kannada Wikipedia (CC BY-SA 4.0 / GFDL, https://hi.wikipedia.org, https://kn.wikipedia.org).

Transliteration / romanization assets:

> Transliteration data: Dakshina dataset by Google Research (CC BY-SA 4.0); Aksharantar by AI4Bharat, IIT Madras and Microsoft (CC BY 4.0 for manually created data, CC0 for mined data, https://huggingface.co/datasets/ai4bharat/Aksharantar).

Wikipedia ShareAlike handling: keep this attribution with any distributed n-gram counts derived from the Wikipedia rows and link the license (https://creativecommons.org/licenses/by-sa/4.0/); page-level attribution is satisfiable via the dump URL + contributor history (Wikimedia ToU §7 "Through hyperlink (where possible) or URL to the page …").
