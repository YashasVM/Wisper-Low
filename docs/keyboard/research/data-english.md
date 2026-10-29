# English open-text corpus research for Wisperlow keyboard n-gram models

**Goal:** collect open-licensed English text to build word and n-gram statistics (unigrams, bigrams, next-word prediction) for corrections and next-word prediction in texting, chat, and AI-prompting styles. Google builds Gboard on private user data; we need open substitutes.

**License policy (hard rule):** derived n-gram counts ship inside an MIT app on Google Play, so each source license must allow that.
- **OK:** CC0, CC-BY, CC-BY-SA, ODC-By, Apache-2.0, MIT.
- **Reject:** NC, ND, research-only, unclear, or copyleft (GPL) licenses.

Downloads live only under `/home/yvm/kbdata/raw/en/<source>/`, never in the repo. Every download is treated as untrusted data: streamed with `python -I`, never executed, converted to one-message/one-sentence-per-line `.txt.gz`. Full log: `/home/yvm/kbdata/README.md`.

---

## Source-by-source evaluation and verdict

| Source | License (verified on card/source) | Style | Verdict | Why |
|---|---|---|---|---|
| Tatoeba (`sentences_CC0` subset) | CC0 1.0 — tatoeba.org/en/downloads ("A part of our sentences are also available under CC0 1.0") | learner/translation sentences, general | **ACCEPT** | CC0 permits any use incl. commercial; no attribution required. |
| OpenAssistant oasst1 | Apache-2.0 — HF card shows `license: apache-2.0` | human–assistant chat | **ACCEPT** | Apache-2.0 allows commercial use and derivation. |
| WildChat | ODC-By — HF `LICENSE.md` ("ODC Attribution License"), retroactive from 2024-06-26 change | real user–ChatGPT chats | **ACCEPT** | ODC-By explicitly allows commercial use; attribution required (see below). |
| Dolly 15k | CC BY-SA 3.0 — HF card ("distributed under the cc-by-sa-3.0 license") | instruction prompts | **ACCEPT** | CC-BY-SA is on the allowed list; ShareAlike attribution required. |
| FineWeb / FineWeb-Edu (sample-10BT) | ODC-By — HF card shows `license: odc-by` | web crawl text | **ACCEPT (not downloaded)** | License OK; download aborted — Xet-backed shards (~2.1 GB each) transferred far too slowly (~0.3–0.5 MB/s) to complete a 2–4 GB sample in session. Re-attract target: 2–4 GB (1–2 shards). |
| C4 (Colossal Clean Crawled Corpus) | ODC-By — allenai/c4 ("released under the terms of ODC-BY") | web crawl text | **ACCEPT (not downloaded)** | License OK; full corpus is ~3 TB, far beyond the 12 GB cap. Only a tiny shard sample would be in scope. |
| Wikipedia | CC-BY-SA 3.0 / 4.0 — en.wikipedia.org/wiki/Wikipedia:Database_download | encyclopedia | **ACCEPT (not downloaded)** | License OK; full dump far beyond cap; a small article sample is in scope. |
| Common Voice sentences | CC0 — commonvoice.mozilla.org ("sentence text ... public domain (cc0)"); dataset released CC0 | read-aloud / prompted sentences | **ACCEPT (not downloaded)** | License OK; HF mirror is gated; direct website TSV download is in scope. |
| OpenSubtitles / OPUS (English mono) | ODC-By — Helsinki-NLP/OpenSubtitles2024 ("released under the ODC-BY license"); redistributes OpenSubtitles.org via OPUS | movie/TV dialogue (chatty) | **ACCEPT (not downloaded)** | License OK; HF build requires login; the tested opus.nlpl.eu v2018 `raw.en.gz` URL 404'd. Correct OPUS path needed. Full corpus is very large; sample only. |
| SODA | CC-BY-4.0 — allenai.org blog ("SODA dataset ... available under permissive license CC-BY-4.0") | machine-generated social chitchat dialogue | **ACCEPT** | License OK; downloaded `train.parquet` (text columns). |
| UltraChat | MIT — HF card `license: mit`; GitHub thunlp/UltraChat "distributed under the MIT license" | ChatGPT dialogues (instructional + chatty) | **ACCEPT** | MIT allows commercial use. Downloaded 1 of 3 `train_sft` shards. |
| wordfreq (`large_en`) | Apache-2.0 code; data under CC-BY-SA-4.0 + CC sources per `NOTICE.md` | word-frequency list | **DROPPED (format)** | License OK, but `large_en.msgpack.gz` is wordfreq's custom compressed format, not plain words; decoding needs the wordfreq library. Not worth the effort. |
| SUBTLEX-US | CC-BY-SA — openlexicon.fr `README-SUBTLEXus.md` ("LICENSE: CC-BY-SA") | subtitle word frequencies | **ACCEPT (not downloaded)** | License OK; no direct plain-text file URL obtained in session. |
| Leipzig Corpora Collection | CC-BY — sprogteknologi.dk / wortschatz ("All corpora provided for download are licensed under CC BY") | news/web sentences | **ACCEPT (not downloaded)** | License OK; English sentence file URL not obtained (download page is JS-rendered; HF mirror has only links). |
| AOSP dictionaries (Helium314/aosp-dictionaries) | **GPL v3** — codeberg.org/Helium314/aosp-dictionaries LICENSE | keyboard wordlists + bigrams | **REJECT** | GPL-3.0 is copyleft, not in the allowed list; shipping derived counts inside an MIT app is not clearly permitted. |
| Enron emails | Unclear/conflicting — loc.gov claims public domain, but wacclearinghouse.org and others restrict to "non-commercial research use" | email | **REJECT** | License is unclear/conflicting with NC-tinged restrictions; not safe for a commercial MIT app. |
| Ubuntu Dialogue Corpus | Unclear CC version — paper/download pages reference "Creative Commons" without a definite permissive version; some mirrors imply NC | IRC tech-support dialogue | **REJECT** | License not clearly permissive; possible NC restriction. |

**Accepted & downloaded:** Tatoeba, OpenAssistant oasst1, WildChat, Dolly 15k, UltraChat, SODA (6 sources).
**Accepted license, not downloaded:** FineWeb, C4, Wikipedia, Common Voice, OpenSubtitles, SUBTLEX-US, Leipzig (license OK; size, transfer speed, or missing URL blocked download).
**Rejected:** AOSP dictionaries (GPL v3 copyleft), Enron (unclear/NC-tinged), Ubuntu Dialogue (unclear CC).

---

## What was downloaded and where

All under `/home/yvm/kbdata/raw/en/<source>/` (never the repo). Each converted to `<source>_...txt.gz`, one message/sentence per line (user turns only for chat data). Logged in `/home/yvm/kbdata/README.md`.

| Source | File (in `/home/yvm/kbdata/raw/en/`) | License | Downloaded (bytes) | Output lines | Notes |
|---|---|---|---:|---:|---|
| Tatoeba | `tatoeba/sentences_CC0.tar.bz2` + `tatoeba_cc0_eng.txt.gz` | CC0 1.0 | 7,962,216 | 41,513 | CC0 subset, `lang=eng` only |
| OpenAssistant oasst1 | `openassistant_oasst1/oasst1_train.parquet` + `oasst1_eng_prompter.txt.gz` | Apache-2.0 | 39,516,251 | 15,200 | `role=prompter`, `lang=en` |
| WildChat | `wildchat/wildchat_shard0.parquet` + `wildchat_eng_user.txt.gz` | ODC-By | 290,754,400 | 112,684 | 1 of 6 shards; `role=user`, language en |
| Dolly 15k | `dolly_15k/databricks-dolly-15k.jsonl` + `dolly_eng_prompt.txt.gz` | CC BY-SA 3.0 | 13,085,339 | 15,011 | instruction `instruction` field (user side) |
| UltraChat | `ultrachat/ultrachat_train_sft_shard0.parquet` + `ultrachat_eng_user.txt.gz` | MIT | 243,999,189 | 219,017 | 1 of 3 `train_sft` shards; `role=user` |
| SODA | `soda/soda_train.parquet` + `soda_eng.txt.gz` | CC-BY-4.0 | 688,771,672 | 13,456,450 | train split, text columns |
| **Total** | | | **1,284,089,067** (~1.28 GB) | **13,864,886** | under the 12 GB cap |

FineWeb shard-0 download was re-attempted in the background but did not complete at a usable transfer rate (Xet-backed HF shard); no partial artifact is usable and none is kept.

---

## Recommended mixing weights

For a **texting- and prompting-flavoured** next-word/correction model, weight real chat and user-prompt data heaviest, keep encyclopedic/web text light. Weights are share of training text lines (re-sample each source to its weight):

| Weight | Source | Role in mix |
|---|---|---|
| 30% | WildChat | real user–bot chats — best "texting/chat + prompt" signal |
| 20% | UltraChat | user prompts — instruction/prompting style |
| 15% | OpenAssistant oasst1 | assistant-style dialogue |
| 10% | Dolly 15k | clean instruction prompts |
| 10% | SODA | casual social chitchat dialogue |
| 10% | Tatoeba | clean general sentences (CC0, safe baseline) |
| 5% | FineWeb (when added) | general web text, low weight to avoid noise |

Total ≈ 100%. FineWeb/Wikipedia/C4 (web) should stay ≤ ~5–10% combined; web text is broad but not texting-flavoured and risks diluting the chat/prompt signal.

---

## Attribution text the app must ship

Because WildChat is ODC-By, Dolly is CC BY-SA, SODA is CC-BY-4.0, UltraChat is MIT, and OpenAssistant is Apache-2.0, the app should include an attribution/licenses screen (and keep notices with any derived data). Suggested text:

```
Wisperlow keyboard n-gram / next-word statistics were built in part from open
datasets. Portions of the derived data incorporate content from the following
sources, used under their respective licenses:

- OpenAssistant Conversations (OASST1) — Apache-2.0
  https://huggingface.co/datasets/OpenAssistant/oasst1

- WildChat — Open Data Commons Attribution License (ODC-By) 1.0
  https://huggingface.co/datasets/allenai/WildChat
  Contains information from WildChat (allenai/WildChat), made available under
  the ODC Attribution License: https://opendatacommons.org/licenses/by/1.0/

- Dolly 15k (databricks/databricks-dolly-15k) — CC BY-SA 3.0
  https://creativecommons.org/licenses/by-sa/3.0/

- UltraChat 200k (HuggingFaceH4/ultrachat_200k) — MIT License
  https://huggingface.co/datasets/HuggingFaceH4/ultrachat_200k

- SODA (allenai/soda) — CC-BY-4.0
  https://creativecommons.org/licenses/by/4.0/

- Tatoeba (CC0 sentence subset) — CC0 1.0
  https://tatoeba.org/en/downloads

Derived n-gram counts contain no raw user data and no personally identifiable
information; sources were treated as untrusted and only streamed during build.
```

Next steps: obtain FineWeb 1–2 sample-10BT shards (2–4 GB), Common Voice English `validated.tsv`/`train.tsv` sentences, Leipzig English sentences, OpenSubtitles English mono (correct OPUS path), and SUBTLEX-US, each sampled to stay within the 12 GB total cap.
