# Latin-to-native transliteration for Wisperlow

Research date: 2026-10-17. Scope: how a Wisperlow user types Hindi and Kannada the
Gboard way — Latin letters in, native script out (`abc -> क`) — with a pure-Kotlin
engine that fits `android/keyboard-core/` (no Android dependencies, no new
dependencies, JUnit 4 tests only). Target: less than 5 ms per keystroke and at most
6,000,000 bytes (6 MB) of static data per language. This is a research and design
document, not a claim that a local n-gram engine matches IndicXlit or Gboard.

**Evidence convention.** Studies and product behavior have linked sources. Every value
marked **proposed** is an engineering starting point, not a published result. Sizes and
timings are estimates until a model compiler runs and the engine is measured on a
phone. Probabilities in the proposed scoring are natural logs, matching
`ENGINE.md` and `decoding-science.md`.

No proprietary keyboard binary, dictionary, or model was downloaded or inspected. No
Gboard or Indic keyboard artifact was reverse engineered. Only public papers,
datasets, and open-source code were read. Downloads and extracted paper text are under
`/tmp/rs-h2/`. Gradle was not run.

**Relationship to the existing engine.** This is an input method in front of the
typing engine described in `ENGINE.md`. Transliteration produces native-script
candidate words; the existing `Lexicon`/`TypingSession` state machine, the `lm/`
`Vocabulary`/`NgramTables`, and the `decoder/` `WordIndex` remain the consumers. A
native-script word is just a surface whose "keys" are its native codepoints.

---

## 1. How Gboard does it

### 1.1 The WFST transliteration decoder (Hellsten et al., 2017)

Gboard's transliterated keyboards were launched for 22 languages in the first half of
2017, using a weighted finite-state transducer (WFST) extension of the existing
keyboard decoder. The input side is the QWERTY letter string; the output side is the
native script. [Hellsten et al., FSMNLP 2017](https://aclanthology.org/W17-4002/)
([PDF](https://aclanthology.org/W17-4002.pdf), DOI `10.18653/v1/W17-4002`).

The modeling core has four steps, all directly reusable in Kotlin:

1. **EM alignment.** Given a lexicon of word pairs (`sanskrit` — `संस्कृत`), run
   expectation maximization to align input and output symbols. The paper's example
   alignment is `s:स a:ϵ n:◌ं s:स ϵ:◌् k:क r:◌ृ i:ϵ t:त`. Symbols on either side
   may not correspond to a symbol on the other side; those are represented with `ϵ`
   (insertions/deletions).
2. **Chunk merging.** A variant "reduces or eliminates input and/or output `ϵ`
   symbols ... by allowing the merger of multiple symbols on either the input or the
   output side," giving alignments like `ph:F` instead of `p:ϵ h:F`. This is exactly
   the many-to-many alignment of Jiampojamarn et al. (2007), §2 below. Merging chunks
   also "increase[s] the parameterization of the model."
3. **Pair language model (joint multi-gram).** The aligned "pair symbols" are treated
   as tokens, and an n-gram model is trained over sequences of pairs. This yields a
   *joint* probability over input and output together, not a conditional `P(output |
   input)`. The paper names the alternative "joint multi-gram models (Bisani and Ney,
   2008)."
4. **WFST compilation and optimization.** The pair model is compiled to a transducer
   and composed with a native-script word-lexicon/n-gram model (`L∘G`). Two
   optimizations matter: *weight pushing* with local determinization (large latency
   and active-memory reductions) and the `LOUDS` succinct graph format.

Reported blind-test results (Hindi and Tamil), versus Google Indic Keyboard, an
HMM-based baseline whose transliteration component learns per-rule `P(s|t)` over
sub-word (mostly syllable) rules with an independence assumption [Hellsten et al.,
2017, Tables 1–2](https://aclanthology.org/W17-4002.pdf):

| System | Hindi WER | Hindi LPC (ms) | Tamil WER | Tamil LPC (ms) |
| --- | --- | --- | --- | --- |
| Baseline HMM (Google Indic Keyboard) | 19.5 | 3.0 | 26.2 | 2.1 |
| WFST-based | 16.4 | 0.5 | 22.6 | 0.2 |

Compiled `L∘G` size for Hindi fell from 85.3 MB (standard) to 26.3 MB (compact) to
**6.72 MB (LOUDS)**; Tamil was 126.5 → 37.1 → **9.80 MB**. `LPC` is latency per
character. The paper explains the accuracy gain over the baseline by the joint
conditioning: in the pair model "the current ... depends on both [input and output]
history," whereas the baseline conditional model does not.

**Takeaways for us.** (a) The joint pair n-gram + native lexicon/LM architecture is
the proven production design; we do not need a neural model. (b) A 6–10 MB per-language
footprint is what Gboard itself operated at for these languages on 2017 hardware, so
our 6 MB budget is realistic. (c) The single biggest win is require no analog of weight
pushing if we keep the model small enough and use a tight beam — but local
determinization and weight pushing are the documented levers if latency disappoints.

### 1.2 The Dakshina dataset and its baselines (Roark et al., 2020)

Dakshina provides the training material this design depends on: for 12 South Asian
languages (including Hindi `hi`/Devanagari and Kannada `kn`/Kannada) it ships native
script Wikipedia text, a **romanization lexicon** of native words with attested
romanizations, and full-sentence parallel data. Hindi and Kannada each have a 25,000
word training lexicon; romanization was elicited from annotators with no prescribed
system, because (unlike Pinyin) these languages have **no standard Latin orthography**.
[Roark et al., LREC 2020](https://aclanthology.org/2020.lrec-1.294/)
([PDF](https://aclanthology.org/2020.lrec-1.294.pdf), [dataset](https://github.com/google-research-datasets/dakshina)).

Their single-word transliteration baselines use exactly the joint model:

> "take a word and its transliteration ... and use expectation-maximization to derive
> a character-by-character alignment ... Given such a string of 'pair' symbols, we can
> train an n-gram language model, which provides a joint distribution over input/output
> string relations. Converted into a finite-state transducer, this can be used to find
> the most likely transliterations ..."

They train **6-gram pair models with Witten-Bell smoothing**. Single-word results
(CER = character error rate, WER = word error rate, %, Latin → native; lower is better)
[Hellsten-style table, Roark et al., 2020, Table 2](https://aclanthology.org/2020.lrec-1.294.pdf):

| Lang | pair 6-gram CER | pair 6-gram WER | transformer CER | transformer WER |
| --- | --- | --- | --- | --- |
| Hindi (hi) | 14.7 | 53.1 | 13.4 | 50.0 |
| Kannada (kn) | 7.2 | 36.6 | 6.3 | 33.8 |

Three findings shape our design:

- **All three model families (pair n-gram, LSTM, transformer) fell within 2% absolute
  CER** of each other. The 6-gram pair model is not far behind a transformer on this
  task; that is the entire justification for a lightweight local engine.
- **Dravidian scripts were among the easiest** and Perso-Arabic hardest. Kannada CER
  (6.3–7.2) is roughly half Hindi's (13.4–14.7), because Kannada romanization is more
  phonetically transparent. Expect Kannada to outperform Hindi at every rank.
- Word error rate at top-1 is high (Hindi ~50%, Kannada ~36%). Top-1 alone is not a
  usable product; **top-3 ranking and a good default are** (see §4).

---

## 2. Open data and open models

### 2.1 Aksharantar and IndicXlit (Madhani et al., 2022/2023)

Aksharantar is the largest public transliteration resource: 26 million pairs over 21
Indic languages, mined from Wikidata, Samanantar, and IndicCorp, plus human-annotated
test sets (103k pairs, 19 languages). The Aksharantar test set separates **frequent
words** (`AK-Freq`), **uniformly sampled words** (`AK-Uni`), and **named entities**
(foreign `AK-NEF`, Indian `AK-NEI`). IndicXlit is a single multilingual transformer
trained on it. [Madhani et al., arXiv 2022](https://arxiv.org/abs/2205.03018);
published as [Findings of EMNLP 2023](https://aclanthology.org/2023.findings-emnlp.4/)
([PDF](https://aclanthology.org/2023.findings-emnlp.4.pdf), [code](https://github.com/AI4Bharat/IndicXlit)).

Top-1 accuracy (%), Hindi and Kannada, from [Madhani et al., 2023, Tables 4 and 6](https://aclanthology.org/2023.findings-emnlp.4.pdf):

| Test set | Hindi | Kannada | All-language avg |
| --- | --- | --- | --- |
| Dakshina, Roark et al. (2020) baseline | 50.0 | 66.2 | 51.8 |
| Dakshina, IndicXlit | **60.5** | **77.1** | 60.5 |
| Dakshina, IndicXlit + rerank top-4 (α=0.9) | 72.4 | 85.2 | 72.1 |
| AK-Freq, IndicXlit | 58.6 | 80.6 | 69.7 |
| AK-Freq, IndicXlit + rerank | 67.9 | 90.5 | 79.4 |
| AK-Uni, IndicXlit | 52.9 | 72.6 | 59.1 |
| AK-Uni, IndicXlit + rerank | 63.0 | 82.1 | 65.7 |

Key reading: IndicXlit improves the earlier Dakshina baselines by **15% average
accuracy**. Reranking candidates with a **unigram language model lifts accuracy about
12 points on average**, but "doesn't help for named entities." The most common error
classes are **vowels (~60%) and similar consonants (~25%)**; the rest are gemination,
acronyms, contextual ambiguity, valid alternatives, and language-specific errors. Its
own analysis notes IndicXlit is "~11M" parameters, i.e. tens of MB of weights — far
over our 6 MB budget, confirming that we borrow its *rankings/lessons* and the
Aksharantar/AK-Freq vocabulary, not its weights.

**Licenses (relevant to an MIT project):** IndicXlit **code and models are MIT**;
the **Aksharantar benchmark and data are CC BY 4.0**; the **mined data is CC0** (per
[Madhani et al., 2023, §footnote 2–4](https://aclanthology.org/2023.findings-emnlp.4.pdf)
and the [repo](https://github.com/AI4Bharat/IndicXlit), SPDX `MIT`). The **Dakshina
dataset is CC BY-SA 4.0** ([Roark et al., 2020, License](https://github.com/google-research-datasets/dakshina)).
CC BY-SA is share-alike: a derived lexical resource may need the same licence, so the
build tool must record provenance per language, and the app should ship its own
licence/attribution file. This is a licensing decision for the project, flagged here,
not settled.

### 2.2 Joint source-channel grapheme n-grams (Li et al., 2004)

The joint model has a clean statistical justification. Li, Zhang, and Su abandon the
multi-step phoneme pipeline and propose **direct orthographic mapping (DOM)** through a
**joint source-channel model** they call the *n-gram transliteration model*: instead of
modeling `P(target | source)`, model the joint `P(<e,c>_1 ... <e,c>_K)`, the n-gram
probability over aligned `<source unit, target unit>` pair tokens. They learn the
alignment automatically by EM from a bilingual dictionary. [Li et al., ACL 2004](https://aclanthology.org/P04-1021/)
([PDF](https://aclanthology.org/P04-1021.pdf), DOI `10.3115/1218955.1218976`).

Two results motivate the joint model over a conditional one:

- A bigram joint model has `T²` parameters versus `T + C²` for the noisy-channel
  conditional (T = pair types, C = target units), i.e. the joint model "gives a finer
  description" by conditioning each output on *both* source and target history.
- On English→Chinese transliteration, the joint 3-gram beats the conditional model at
  every rank: open-set word error **29.9% vs 39.0%** at 1-best, and **5.4% vs 10.8%**
  at 10-best ([Table 3](https://aclanthology.org/P04-1021.pdf)). Perplexity of the
  joint model is consistently lower in open and closed tests.

This is the model class §3 builds: a joint n-gram over Latin-chunk — native-chunk
pairs, scored exactly as Li et al. score `P(<e,c>_k | <e,c>_{k-1...})`.

### 2.3 Many-to-many alignment (Jiampojamarn et al., 2007)

One-to-one alignment fails on transliteration for the same reasons it fails on
letter-to-phoneme conversion: two Latin letters can map to one native grapheme
(`sh -> श`) and one Latin letter can map to two (`x -> क्स`), plus the lengths differ.
Jiampojamarn, Kondrak, and Sherif extend the Ristad–Yianilos stochastic transducer EM
to allow **substrings up to `maxX` source units to align with up to `maxY` target
units**, with partial counts accumulated over all substring mappings in a
forward/backward pass. They set `maxX = maxY = 2` for their experiments. [Jiampojamarn
et al., NAACL 2007](https://aclanthology.org/N07-1047/)
([PDF](https://aclanthology.org/N07-1047.pdf), [m2m-aligner](https://github.com/letter-to-phoneme/m2m-aligner)).

Many-to-many alignment "result[s] in significant improvements over the traditional
one-to-one approach" — word accuracy gains of **2.7% to 7.6% absolute** across five
datasets (e.g. English CELEX 74.6% one-to-one vs 82.2% many-to-many; +HMM 83.6%)
[Jiampojamarn et al., 2007, Table 3](https://aclanthology.org/N07-1047.pdf). The
aligner is small, standalone, and EM-only — appropriate to reimplement in the offline
Python build step (§3.1).

---

## 3. Concrete pure-Kotlin design

The engine is an **input-method layer** that turns the Latin composing string into
ranked native-script candidates. Decoding is a joint beam search over the same
`TypedKey`/`Candidate` shapes the existing decoder uses.

### 3.1 Offline build (Python, run once per language)

Input: a romanization lexicon of `(latin, native, count)` triples — Dakshina's
`*.train.tsv` (25k lexemes each) plus Aksharantar/AK-Freq pairs, filtered to the
target script. A separate native-script Wikipedia dump supplies native text.

1. **Normalize** everything to NFC. Tokenize native text into codepoints; keep virama
   (`◌्` in Devanagari, `◌್` in Kannada) and vowel signs as their own codepoints, as
   Dakshina does. Drop ZWJ/ZWNJ. Lowercase Latin. Keep a version pin (Dakshina v1.0,
   Aksharantar release) in the file header.
2. **Align** each pair with the many-to-many EM aligner from Jiampojamarn et al.
   (2007), `maxLatinChunk = 3`, `maxNativeChunk = 3`. Start from one-to-one
   initialization (as Roark et al. and Hellsten et al. do with EM) and iterate to
   convergence. Output a per-pair chunk sequence:
   `na|ma|ste  <->  न|म|स्ते`, or at codepoint resolution
   `s:स a:ϵ n:◌ं s:स ϵ:◌् k:क r:◌ृ i:ϵ t:त`.
   **Merge across `ϵ`** (Hellsten et al.'s chunk-merging variant) so that chunk pairs
   are the unit of the n-gram, e.g. `ste -> स्ते`, `sh -> श`.
3. **Count chunk pairs.** For every aligned pair, weight each chunk-pair occurrence by
   the pair's `count` (Dakshina repeats romanizations as often as attested). This
   yields the joint count `#(latinChunk, nativeChunk)` and the joint n-gram
   transition counts `#((l,n)_{k} | (l,n)_{k-1} ...)`.
4. **Prune and quantize** the joint n-gram (Kneser-Ney or Witten-Bell smoothing, as in
   Roark et al.). Convert log-probabilities to `Int16`, and delta/decode with a
   varint. **Proposed: bigram over chunk pairs, with a separately stored unigram.**
   Li et al. show 3-gram helps, but a bigram over merged chunks already covers most of
   the local context and fits the size budget with margin.
5. **Build the native word trie and native char LM.** Take the native vocabulary
   (lexicon native forms + native Wikipedia word list), key it with `Vocabulary.keyOf`
   so casing/ZWJ rules stay consistent. Build a succinct trie (LOUDS layout, §3.3)
   storing a word id per terminal. Build a native-codepoint n-gram LM over the
   vocabulary: **proposed bigram**, since the vocabulary and the joint model already
   carry most of the signal and IndicXlit's reranking gain came from a *unigram* LM.
6. **Emit one file** per language (§3.4).

### 3.2 Runtime beam search (Kotlin)

State (a hypothesis) is:

```kotlin
data class XlitState(
    val latinPos: Int,        // how many Latin chars of the composing string are consumed
    val native: StringBuilder, // native script produced so far
    val trieNode: Int,        // node index in the native word trie, -1 if the path is dead (OOV)
    val lastPair: Int,        // chunk-pair id for the joint n-gram backoff
    val score: Float,         // natural-log joint score so far
)
```

At each keystroke, extend every live hypothesis (the composing string only grows):

```text
extend(state):
  for each chunk pair p = (latinChunk, nativeChunk) whose latinChunk
        is a prefix of input[state.latinPos ...]:
      nextLatinPos = state.latinPos + len(p.latinChunk)
      nextNative   = state.native + p.nativeChunk
      jScore       = jointLogProb(p, state.lastPair)        # bigram over chunk pairs
      trie         = walkTrie(state.trieNode, p.nativeChunk) # codepoint by codepoint
      if trie != DEAD:                                       # in-vocabulary: constrain
          lScore   = nativeLmScore(state.native, p.nativeChunk)
          uScore   = userBonus(nextNative)                   # learned romanizations (§4.5)
          emit state' = XlitState(nextLatinPos, nextNative, trie, id(p),
                                  state.score + jScore + lScore + uScore)
      else if oovFallbackEnabled:
          emit state' = XlitState(nextLatinPos, nextNative, DEAD, id(p),
                                  state.score + jScore)       # OOV: joint model only
  keep top BEAM_WIDTH by score
```

Candidate finalization (run after each keystroke, cheap):

- **Complete words:** any hypothesis with `latinPos == input.length` and a terminal
  trie node is a native candidate; its rank is `score`. Prefer terminals over OOV.
- **OOV fallback:** if no in-vocabulary hypothesis consumes the whole input (or the
  best one is very low), take the best unconstrained joint hypothesis as the native
  candidate. This is the "no trie match" path and is also what produces plausible
  spellings for names.
- **Literal fallback:** always include the raw Latin string as a candidate, so an
  English word or an unmatchable name is one tap away (§4).

Two independent scores combine: the **joint chunk-pair n-gram** (the transliteration
model) and the **native n-gram LM** (the language model). This mirrors Gboard's
`T ∘ L ∘ G` decomposition and IndicXlit's LM reranking. Suggested weights
(**proposed**): `1.0 * jScore + 0.5 * lScore + lambda * uScore`. The literal candidate
carries a fixed penalty so it never outranks a confident native word by accident.

### 3.3 Data structures

- **Chunk-pair table** — `HashMap<Int, IntArray>` from a Latin-chunk key to native
  chunk ids, and a `HashMap<Int, Float>` (or packed `Int16` array) from pair id to
  unigram log-prob. Pair ids are dense `Int`s. Total pair types are small (§3.4).
- **Joint bigram** — a flat `IntArray` indexed by `pairId * stride + backoffBucket`,
  storing quantized log-probs, with a small backoff table. Same shape as the existing
  `lm/NgramTables`, so the code and tests can reuse that convention.
- **Native word trie** — LOUDS (level-order unary degree sequence) layout, the same
  format Hellsten et al. use for `L∘G` on-device. A node is a bitvector + edge label
  array; walking a native chunk means walking its codepoints. LOUDS is compact and
  cache-friendly and is what makes the 6.72 MB Hindi figure possible.
- **Native char LM** — reuse `lm/NgramTables` directly (bigram over native
  codepoints), one table per language.
- **Index from Latin prefix position to candidate chunk pairs** — optional; with a
  bigram pair model small enough, a `HashMap<Int, ...>` lookup per position is enough.

No new dependencies. Everything is `IntArray`/`HashMap`/`StringBuilder` plus JUnit 4.

### 3.4 File format and size budget

A single little-endian file per language with a header then several sections:

```text
magic "WXL1", version, script tag ("deva"|"knda"), lexicon version pin
section table: (offset:u32, length:u32, kind:u8) for
  CHUNK_PAIRS, JOINT_NGRAM, NATIVE_TRIE(LOUDS), NATIVE_LM, WORD_IDS
```

**Proposed size estimate (Hindi, ~100k vocabulary):**

| Section | Estimate | Notes |
| --- | --- | --- |
| Chunk-pair table + joint bigram | ~0.4–0.8 MB | ~20k–50k observed pairs, ~100k–200k transitions, quantized |
| Native word trie (LOUDS) | ~1.5–2.5 MB | ~700k codepoints shared across prefixes |
| Native char bigram LM | <0.1 MB | ~200 native codepoints |
| Word ids / surfaces | ~0.5–1.0 MB | UTF-8 surfaces + compact keys |
| **Total** | **~3–4.5 MB** | under the 6 MB budget with margin |

Kannada's vocabulary and codepoint set are similar to Hindi's, so its budget is in the
same band. If a language overshoots 6 MB, prune the joint n-gram to bigram only and
the native LM to unigram, and cap the trie to the top-N frequent words plus a
free-standing OOV path — this trades a little accuracy for a hard bound.

### 3.5 Timing

**Proposed** budget: `BEAM_WIDTH = 16`. Each keystroke extends ≤16 hypotheses by the
chunk pairs that match at the current Latin position — typically <30 continuations
per position for a 3-letter Latin chunk bound — so <500 score lookups and trie walks,
each O(1)-ish. That is well inside 5 ms on a mid-range phone; the trie walk is the only
super-linear step (O(chunk length)) and is bounded by 3. If measurement misses 5 ms,
apply the Hellsten levers in order: (1) shrink the beam, (2) revert joint n-gram to
unigram, (3) add local determinization/weight pushing. Run the same
`gradle-locked.sh` test loop the repo already uses.

### 3.6 Expected quality (proposed, not measured)

Anchored on the pair-n-gram columns above: Hindi pair-6g CER 14.7 / WER 53.1, Kannada
pair-6g CER 7.2 / WER 36.6 (Dakshina). A local bigram-over-chunks engine with a native
LM and trie constraint should land **near the pair-ngram row, below IndicXlit**:

| Metric | Hindi **proposed** | Kannada **proposed** |
| --- | --- | --- |
| top-1 word accuracy | ~45–55% | ~60–70% |
| top-3 word accuracy | ~70–80% | ~80–88% |

The honest product claim is **top-3, not top-1**. On frequent words accuracy is much
higher (IndicXlit AK-Freq: Hindi 58.6, Kannada 80.6), so the strip pins frequent words
first. These numbers must be replaced by measurements on our own held-out split
(Dakshina `*.test.tsv`) before any accuracy claim enters user-facing copy.

---

## 4. User experience

Behaviors mirror Gboard's transliterated keyboards, implemented on the Compose panel
and the service in `android/app/`.

1. **Native candidates in the strip while composing Latin.** The user types on the
   QWERTY layout they already know; the suggestion strip shows native-script
   candidates for the current composing word, best first. The composing text in the
   input field stays Latin (or native, per user preference) until commit. This is the
   Gboard model described by [Hellsten et al., 2017, Fig. 1](https://aclanthology.org/W17-4002.pdf)
   and by [Ouyang et al., 2017](https://arxiv.org/abs/1704.03987) (the keyboard FST
   decoder's literal decoding, autocorrections, and word completions — including
   post-corrections).
2. **Space commits the top candidate.** If the best native candidate clears a
   confidence threshold (proposed: its score beats the literal candidate by a margin),
   space commits it. Otherwise space commits the literal Latin. The existing
   `TypingSession` already commits on word terminators; the transliteration layer
   supplies the surface it commits.
3. **The Latin original is one tap away.** The strip always includes a literal
   candidate slot showing the raw Latin string; tapping it commits exactly what was
   typed. Backspace immediately after a commit reverts the autocorrect/transliteration
   — the existing engine already reverts on immediate backspace, and this is the same
   affordance.
4. **English words in mixed typing.** Transliteration must not corrupt English. Use
   three guards: (a) the literal candidate is always present; (b) if the Latin word is
   a known English/frequent-Latin word and no native candidate clears the threshold,
   commit literal; (c) keep the existing English `Lexicon`/LM in the candidate pool and
   rank across both scripts. Code-switched sentences ("meeting बाद में") must work word
   by word.
5. **Learning user romanizations.** Treat an explicitly committed native candidate, or
   a user edit of a candidate, as supervision ([Ouyang et al. 2017](https://arxiv.org/abs/1704.03987)
   describes personalization the same way, and `decoding-science.md`'s supervision
   weights apply). Store a per-user table of `native word -> Latin forms seen` and
   `Latin string -> chosen native word`, with the decaying-count scheme already used
   for touch adaptation. Add `uScore` in the beam (§3.2) and also fold the user's
   chosen pairs into the joint chunk-pair counts with a low weight, so a learned
   romanization of a name persists. Never train from an engine-generated default that
   the user did not touch.

Additional UX notes carried over from the existing engine: the strip must stay
responsive under the 5 ms budget; the same `Candidate`/`TypedKey` types should carry
native surfaces so gesture and spatial scoring compose unchanged; and the language of
the native candidate follows the active keyboard language (no cross-script leakage
between Hindi and Kannada strips).

---

## 5. Worked examples

### 5.1 `namaste` → `नमस्ते` (Hindi / Devanagari)

Chunk alignment from the EM step (merged across `ϵ`):

```text
na  ->  न     # consonant + inherent 'a'
ma  ->  म
ste ->  स्ते  # s + virama(◌्) + t + e-vowel-sign(◌े)
```

Beam walk for input `namaste`, `BEAM_WIDTH = 16`:

| keystroke | live native prefix(es) | comment |
| --- | --- | --- |
| `n` | `न` | `na` not yet complete |
| `na` | `न` \| `ना` | joint n-gram context pending |
| `nam` | `नम` \| `नाम` | trie keeps both if attested |
| `nama` | `नम`, `नाम` | second `ma` chunk |
| `namas` | `नमस्` | virama path |
| `namast` | `नमस्त` | trie prunes to `नमस्...` words |
| `namaste` | **`नमस्ते`** | terminal word-node; joint + native LM agree |

The native char LM and the trie jointly kill `नामस्ते`/`नामस्त`, leaving `नमस्ते`; the
literal candidate `namaste` sits in the strip, one tap away, and space commits the
native word above threshold.

### 5.2 `naanu` → `ನಾನು` (Kannada)

```text
naa -> ನಾ   # n + aa vowel sign (ಾ)
nu  -> ನು   # n + inherent u
```

| keystroke | live native prefix(es) | comment |
| --- | --- | --- |
| `n` | `ನ` | |
| `na` | `ನ`, `ನಾ` | `aa` not yet resolved |
| `naa` | `ನಾ` | joint n-gram prefers long-ā after word-initial `n` in `naanu`-type words |
| `naan` | `ನಾನ` | |
| `naanu` | **`ನಾನು`** | terminal trie node + native LM |

Contrast: `naane` (`ನಾನೇ`) and `naanu` share the prefix `ನಾನ`; the final chunk
(`nu` vs `ne`) and the native LM separate them at the last keystroke, illustrating why
top-3 display matters even when top-1 is wrong.

---

## 6. Open questions and next steps

- **Measure, don't trust:** build the compiler, then report top-1/top-3 on Dakshina
  `*.test.tsv` for Hindi and Kannada before any UX or marketing claim.
- **Chunk n-gram order:** bigram is the size-safe default; test whether trigram over
  chunk pairs is worth its bytes.
- **Alignment bound:** `maxChunk = 3` is a starting point; 2 (as Jiampojamarn et al.)
  may be enough and halves the pair space.
- **Licensing:** ship an attribution/licence file; record provenance per language
  (CC BY-SA 4.0 for Dakshina-derived forms, CC BY 4.0/CC0 for Aksharantar-derived).
- **Weight pushing / LOUDS:** only if timings miss 5 ms or size misses 6 MB.
- **Extensibility:** the same pipeline should generalize to other Indic scripts by
  swapping the lexicon and script tag; keep the build script script-agnostic.

## Sources

- Hellsten, Roark, Goyal, Allauzen, Beaufays, Ouyang, Riley, Rybach. *Transliterated
  Mobile Keyboard Input via Weighted Finite-State Transducers.* FSMNLP 2017.
  https://aclanthology.org/W17-4002/
- Roark, Wolf-Sonkin, Kirov, Mielke, Johny, Demirşahin, Hall. *Processing South Asian
  Languages Written in the Latin Script: the Dakshina Dataset.* LREC 2020.
  https://aclanthology.org/2020.lrec-1.294/ ; dataset
  https://github.com/google-research-datasets/dakshina
- Madhani, Parthan, Bedekar, NC, Khapra, Kunchukuttan, Kumar, Khapra. *Aksharantar:
  Open Indic-language Transliteration datasets and models for the Next Billion Users.*
  arXiv 2022 (2205.03018); Findings of EMNLP 2023.
  https://aclanthology.org/2023.findings-emnlp.4/ ; code
  https://github.com/AI4Bharat/IndicXlit
- Li, Zhang, Su. *A Joint Source-Channel Model for Machine Transliteration.* ACL 2004.
  https://aclanthology.org/P04-1021/
- Jiampojamarn, Kondrak, Sherif. *Applying Many-to-Many Alignments and Hidden Markov
  Models to Letter-to-Phoneme Conversion.* NAACL 2007.
  https://aclanthology.org/N07-1047/ ; aligner
  https://github.com/letter-to-phoneme/m2m-aligner
- Ouyang, Rybach, Beaufays, Riley. *Mobile Keyboard Input Decoding with Finite-State
  Transducers.* 2017. https://arxiv.org/abs/1704.03987
- Bisani, Ney. *Joint-sequence models for grapheme-to-phoneme conversion.* Speech
  Communication, 2008. (Referenced by Hellsten et al. and Roark et al. as "joint
  multi-gram models".)
- Ristad, Yianilos. *Learning String-Edit Distance.* IEEE TPAMI, 1998. (Basis of the
  many-to-many EM aligner.)
