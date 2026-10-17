# Personalization & decoder evaluation — research note (revised)

Scope: mobile keyboard personalization and decoder metrics. Claims below are cited to primary sources or AOSP source; unverified claims from the prior draft were removed.

## Evidence table

| Claim | Source | Finding |
|---|---|---|
| AOSP user-history adds **every committed word** when autocorrect is enabled and the input connection is not slow; increments count by 1; no promotion threshold in provided source. | AOSP `LatinIME` `InputLogic.java` lines ~400, 1424-1440, 2184-2192; `UserHistoryDictionary.addToDictionary` (`count=1`) | Learning is gated by `mAutoCorrectionEnabledPerUserSettings` and slow-connection guard, not by a word-frequency threshold. |
| AOSP user-history forgetting curve: decay check every **2 h**; level drops by one every **15 days**; 16 levels (0-15), visible at level ≥2; level-0 entries discarded after **30 time steps** (~11.25 days at 9 h/step). | AOSP `forgetting_curve_utils.cpp`: `DECAY_INTERVAL_SECONDS=2*60*60`; `DURATION_TO_LOWER_THE_LEVEL_IN_SECONDS=15*24*60*60`; `MAX_LEVEL=15`; `MIN_VISIBLE_LEVEL=2`; `DISCARD_LEVEL_ZERO_ENTRY_TIME_STEP_COUNT_THRESHOLD=30`; `MAX_ELAPSED_TIME_STEP_COUNT=31` | Decay is time-based level reduction, not immediate deletion. |
| Gboard unlearning: long-press a suggestion and drag to bin removes it; "Delete learned words & data" clears all on-device learned data. | Gboard Help, "Get word suggestions and fix mistakes" (support.google.com/gboard/answer/7068415); "Learn how Gboard gets better" (support.google.com/gboard/answer/12373137) | Documented user-visible behavior only. |
| Gboard personalization: "Personalize for you" toggle; federated learning sends model updates (not typed text) only while charging, on Wi-Fi, idle. | Gboard Help 12373137 | Opt-in; on-device learning always; server receives aggregated updates only when opted in and charging. |
| AOSP source does not show an explicit password-field or incognito skip in the provided files; Gboard help does not document incognito/password handling in the pages fetched. | AOSP `InputLogic.performAdditionToUserHistoryDictionary`; Gboard Help pages above | **Unknown** — do not assert; treat as unverified. |
| Ouyang 2017 FST decoder: Table 1 WERs (tap/gesture): Baseline 6.31/11.90, FST 5.78/11.51, FST+p.c. 5.07/9.20. Eval on >3000 words, smartphone QWERTY, prompts from transcribed speech, participants asked to type quickly. | Ouyang et al., "Mobile Keyboard Input Decoding with Finite-State Transducers", arXiv:1704.03987, §6, Table 1 | Measured lab typing, not production. |
| Ouyang `C·L·G` composition uses a **bi-key spatial context** transducer (`a:b` = key b with previous key a), not pronunciation/personalization interpolation. Dynamic models (user n-grams, contacts) are added via **on-the-fly lattice rescoring** + character-to-word splicing for OOVs. | Ouyang et al. 2017, §3.1, §5.1 | Corrects prior misdescription. |
| VelociTap: novice users reach mean **41 wpm** on a **40 mm** wide smartwatch keyboard at **3% character error rate**. | Vertanen et al., CHI 2015, "VelociTap", doi:10.1145/2702123.2702135, abstract | Smartwatch-sized keyboard; not a phone baseline. |
| Yin 2013 hierarchical spatial backoff model: posture+key reduces CER ~11.5%; user+key ~14.2%; posture classification + adaptive reduces CER **13.2%** over non-adaptive baseline; user+key model needs **≥55 touch points** (key E) before beating key-only. | Yin et al., CHI 2013, "Making touchscreen keyboards adaptive...", pp. 2775-2784, §3/§4, Figure 4 | Upper bounds from known-intent data; real use lower. |
| Weir 2012 GP regression: touch accuracy improvement raw-sensor: **23.47%** (2 mm), **14.20%** (3 mm), **4.70%** (4 mm); device-reported touch marginally better: **23.79% / 14.79% / 5.11%**; ~**200** training examples sufficient. | Weir et al., UIST 2012, pp. 465-476; darylweir.com/assets/gptouch.pdf, Conclusions + Fig. 3/5 | Per-button (not word) accuracy; small-N study. |
| Azenkot & Zhai 2012 (as quoted by Yi et al. 2019): index finger on 60×37.5 mm keyboard, touch offset 0.59 mm below key center, σ_x=1.81 mm, σ_y=1.48 mm. | Yi et al., IJHCS 2019, quoting Azenkot & Zhai 2012, MobileHCI'12 | σ ≈ 0.25-0.30 key-width (6-7 mm keys), not σ=key width. |
| Metrics for decoder eval: WER, CER after correction, keystroke savings, false-correction rate (auto-corrupting a valid word). | Ouyang et al. 2017 §4.4; standard text-entry metrics | Define false-correction rate explicitly in tests. |

## Corrected / deleted vs prior draft

- Removed claim that IOActive UserDictionaryProvider advisory describes AOSP user-history promotion rules (it concerns a different provider).
- Corrected `C·L·G` description: spatial context, not pronunciation/personalization interpolation.
- Removed unsupported "no server data" blanket; replaced with Gboard's documented federated-learning opt-in.
- Removed unsupported "σ = key width" and "σ = key width/4"; replaced with measured σ_x=1.81 mm, σ_y=1.48 mm (≈0.25-0.30 key width).
- Removed "50-100 touch sessions" and "~200 samples full personalization" blanket; replaced with Yin ≥55 points (single key, ideal data) and Weir ~200 examples.
- Removed "40 mm smartwatch" misattribution as a phone target; VelociTap is smartwatch.
- Kept Ouyang WER table but labelled as lab typing, not production parity targets.

## Remaining unknowns

- Whether AOSP/Gboard skips learning in password/incognito fields (not in provided source or fetched help pages).
- Exact Gboard personalization thresholds and decay schedule (proprietary, undocumented).
- Optimal Wisperlow adaptation threshold (must be measured on device).

## Proposed engineering defaults (UNVALIDATED)

- Add every committed word to user history when autocorrect is enabled and input connection is not slow (AOSP-style), count +=1.
- Blend user n-grams via on-the-fly lattice rescoring, not graph rewrite (Ouyang §5.1).
- Forgetting: level-based decay, 2 h check, 15-day level drop, 16 levels, visible ≥2, discard level-0 after ~11 days.
- Touch noise: bivariate Gaussian per key with σ_x≈1.8 mm, σ_y≈1.5 mm on phone QWERTY; adapt spatial model only after ≥50-60 samples per key (Yin) and use ~200 examples for full per-user GP-style adaptation (Weir).
- Unlearning: long-press suggestion → remove; "clear all learned data" action; expose "personalize" toggle.
- Decoder targets: track WER, CER-after-correction, keystroke savings, false-correction rate; treat Ouyang/VelociTap numbers as literature references, not parity goals.
