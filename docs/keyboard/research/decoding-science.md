# Keyboard decoding science for Wisperlow

Research date: 2026-10-17. Scope: a clean-room, pure-Kotlin typing engine for mid-range Android phones, targeting less than 8 ms of decoding per keystroke and at most 8,000,000 bytes of static language data per language. This is a research and design document, not a claim that the proposed engine reaches Gboard or iOS accuracy.

**Evidence convention.** Named studies and product behavior have linked sources. Every value marked **proposed** is an engineering starting point, not a paper result. Size calculations are estimates until a model compiler produces the file. Timing targets require measurements on an actual phone. Logarithms in proposed scoring algorithms are natural logarithms.

The local baseline was inspected at commit `e91a7df0dd68f52457cd162f95e0319365955aaa`, with unrelated working-tree changes present. The inspected files were under `android/app/src/main/java/com/wisperlow/mobile/keyboard/`. `Lexicon.kt` scans word-length buckets using weighted edit distance, adds user-bigram bonuses, and trusts user words after two observations. `TypingSession.kt` already composes words, commits on separators, and reverts autocorrect on immediate backspace. `KeyArea.kt` resolves rectangular key regions. `Qwerty.kt` reconstructs touch positions from hardcoded row offsets. These are local observations, not external research claims. The source links below are repository-relative so they remain reviewable: [Lexicon.kt](../../../android/app/src/main/java/com/wisperlow/mobile/keyboard/Lexicon.kt), [TypingSession.kt](../../../android/app/src/main/java/com/wisperlow/mobile/keyboard/TypingSession.kt), [KeyArea.kt](../../../android/app/src/main/java/com/wisperlow/mobile/keyboard/KeyArea.kt), and [Qwerty.kt](../../../android/app/src/main/java/com/wisperlow/mobile/keyboard/Qwerty.kt). Paths may change during extraction to `android/keyboard-core/`.

No proprietary keyboard binary, dictionary, or model was downloaded or inspected. No GPL or source-available implementation was copied. The only repository file written for this research is this document. Downloads and extracted research material are under `/tmp/kb-research-r1a/`. Gradle was not run.

## 1. Touch and spatial model

### What the evidence supports

Azenkot and Zhai studied 32 people using two thumbs, one thumb, or one index finger. Their work establishes posture-dependent touch distributions, rather than one universal offset. The original per-key numerical standard deviations were not available in the accessible author material, so this document does not invent them. [Azenkot and Zhai, 2012](https://research.google/pubs/touch-behavior-with-different-postures-on-soft-smartphone-keyboards/).

Yin, Ouyang, Partridge, and Zhai model the offset from each key center as a bivariate Gaussian. Their hierarchical spatial backoff model shares data across users, keys, and postures. For right-handed one-finger input, left-side taps tend rightward and bottom-row taps downward; two-thumb input has different offsets. Individual distributions are narrower than pooled ones. Their experiment uses at least 50 samples for a user-key model and a 0.94 posture-confidence threshold. Standalone character error falls from 8.64% to 7.50%, a 13.2% relative reduction, without an LM. The posture classifier reaches 86.4%; neither the classifier nor the offsets establish left-handed defaults. [Yin et al., 2013, pp. 2775–2784](https://groups.csail.mit.edu/mug/pubs/Yin2013Making.pdf).

Weir et al. use user-specific Gaussian-process correction for general target acquisition. About 200 labeled touches are useful in their experiments; reported error reductions vary with target radius. These results support learning nonlinear offsets, but are not keyboard WER results and do not establish that a Gaussian process is necessary here. [Weir et al., 2012](https://www.dcs.gla.ac.uk/~rod/publications/WeiRogMur12.pdf).

FFitts separates relative motor variance from an absolute touch-precision floor, `σ² = σr² + σa²`. Its calibration reports `σa = 0.94 mm` for the one-dimensional task and a bivariate `SDxy = 1.5 mm` for the two-dimensional task. Those are different statistics, not interchangeable x/y decoder sigmas. A scalar measured in millimeters converts to horizontal key units by division by key width in millimeters, and to vertical key units by division by key height. FFitts predicts movement time. Bayesian Touch instead uses Bayesian target selection with target-size-dependent likelihoods. Neither supplies universal phone-keyboard Gaussian coefficients. [Bi, Li, and Zhai, FFitts, 2013](https://www3.cs.stonybrook.edu/~xiaojun/pdf/FFitts.pdf); [Bi and Zhai, Bayesian Touch, 2013](https://research.google/pubs/bayesian-touch-a-statistic-criterion-of-target-selection-with-finger-touch/).

The most relevant production reference is Sivek and Riley's Gboard study. It normalizes offsets as `Δx=(x−cx)/keyWidth`, `Δy=(y−cy)/keyHeight`. Its baseline decoder sigma is 0.55; tuned personalized values are 0.40–0.50, including 0.50 for en-US in the US and en-IN. It retains 800 taps in four unordered buckets and uses seven key clusters. Full covariance learning gives no significant benefit. These are decoder tuning values, not direct estimates of motor precision. [Sivek and Riley, 2022, §§2–4](https://arxiv.org/abs/2209.11311).

### Proposed phone model and online adaptation

Use the actual layout's hit-cell centers and dimensions, including gutters and row offsets. Normalize independently by width and height. Preserve the raw touch-down coordinate, pointer identity, timestamp, and layout version until the word is settled. A keycap's painted rectangle is not necessarily its hit cell. Hardcoded QWERTY coordinates cannot describe split, resized, or language-specific layouts.

Start with a diagonal Gaussian, zero means, and **proposed decoder `σx=σy=0.50`**. This follows the scale of the production reference, but must be retuned with our LM and edit costs. Prefer adapting means first. Keep fitted motor variance and decoder sigma separate, rather than narrowing the decoder whenever a small sample appears precise.

For a key `k`, define normalized residual `r=((x−cx)/Wk,(y−cy)/Hk)` and an offset mean `μk`. A full probability model is:

```text
p(touch | k) = Normal2D(r; μk, Σk) / (Wk * Hk)
negativeLogLikelihood = 0.5 * (r−μk)' inverse(Σk) (r−μk)
                      + 0.5 * log(det(Σk)) + log(Wk*Hk) + constant
```

The area Jacobian matters if differently sized keys compete. With equal cells and fixed covariance, its terms cancel. For the first implementation, use equal-sized letter cells, fixed sigma, and the Gaussian exponent as an energy. Do not call that energy a fully calibrated posterior.

**Proposed adaptation rule:** maintain weighted counts, sums, and squared sums, with a prior at each hierarchy level. For accepted residual `r`, confidence weight `a`, and elapsed days `d`:

```text
f = 2^(−d / 30)                     # proposed 30-day half-life
N <- f*N + a
S <- f*S + a*r
Q <- f*Q + a*(r*r')
muRaw = S/N
Craw = Q/N − muRaw*muRaw'
beta = N/(N + 50)
mu = beta*muRaw + (1−beta)*muParent
C = beta*Craw + (1−beta)*CParent
    + beta*(1−beta)*(muRaw−muParent)*(muRaw−muParent)'
```

The last term accounts for disagreement between means. It prevents an overconfident covariance when parent and local estimates disagree. Clamp the initial mean correction to `±0.20 W` and `±0.20 H`. If variance adaptation is later enabled, constrain decoder sigmas initially to `0.35–0.60` and retune its temperature. These bounds are proposed.

Use hierarchical backoff in this order: layout/orientation/global, then row and left/middle/right zone, then individual key, then optional posture-specific key. Activate a row-zone estimate at **20 effective samples** and a key estimate at **50**. Until then use the parent. Shrinkage remains active after the threshold. Store separate landscape and portrait statistics; layout changes invalidate coordinate assumptions. Posture inference can wait until an on-device classifier beats this simpler model on held-out traces.

Use an explicitly chosen word or a user-confirmed correction as strong supervision. Use unchanged, known literal words only as weak supervision, proposed weight `0.1`; use deliberate selections as weight `1`. Align taps to the accepted spelling and discard ambiguous insertion/deletion alignments. Do not train from an engine-generated autocorrection merely because space committed it. Otherwise the decoder can teach itself that its mistakes were intended.

Pitfalls include selection bias toward easy words, changing grip within a session, sparse letters, autocorrect-generated labels, touch-up drift, missing multi-pointer events, and feedback loops between LM and spatial adaptation. Keep an unadapted control score in evaluation. A pooled Gaussian that looks wide may represent several postures; a narrow per-user Gaussian can still be wrong after a grip change. The posture findings above motivate these safeguards, while their exact implementation is proposed.

## 2. Invisible key-target resizing

### Published mechanisms and limits

Gunawardana, Paek, and Meek resolve taps with `argmax_k Ptouch(t|k) * PLM(k|history)^w`. They use a character 8-gram trained on 8.5 million USENET characters. Unrestricted resizing can be worse than a static keyboard. Anchored resizing reduces keystroke error by 17%, 6%, 8%, and 13% relative on standard, email, query, and URL corpora. Their best anchor is `6×60 px` inside `46×100 px` keys, or approximately `0.130 W × 0.600 H`; support extends to `86×140 px`. They show that an absolute anchor guarantee requires competing keys to have zero support there, since a sufficiently strong prior can otherwise overwhelm a nonzero likelihood. The LM exponent is fitted, not a universal published constant. [Gunawardana, Paek, and Meek, 2010](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/paper-final.pdf).

Apple's US8232973B2 describes hidden hit-region changes while visible icons remain fixed, especially Figures 8A–8C. An example scales area as `Avisible(i) * [1 + K*P(next=i)]`, with an empirical `K`, and describes resolution of overlaps. This is evidence of a disclosed design, not evidence that a particular current iOS release implements that formula. [Apple patent US8232973B2](https://patents.google.com/patent/US8232973B2/en).

Google's public evidence supports both incremental word-hypothesis decoding and a separate key-correction module near boundaries. It does not support the claim that Google resolves everything independently per tap, or waits until space to do all decoding. [Sivek and Riley, 2022, introduction and §2](https://arxiv.org/abs/2209.11311); [Ouyang et al., 2017](https://arxiv.org/abs/1704.03987).

Personalization has several distinct precedents. Baldwin and Chai's accessible abstract reports vocabulary/error-correction-derived online labels, a 10.4% relative error reduction, and adaptation within less than a week's average typing. Full experimental details were not verified here. [Baldwin and Chai, 2012, DOI](https://doi.org/10.1145/2166966.2166969). Himberg et al. use vector quantization to move and resize a visible keyboard; that is a different intervention from hidden hit testing. [Himberg et al., 2003](https://doi.org/10.1145/604045.604061). Findlater and Wobbrock's hidden adaptation improves speed by 12.9% overall, without a discernible error difference, in a 12-person, ten-finger large-touchscreen experiment. It is useful evidence for preserving appearance, not a phone-thumb effect size. [Findlater and Wobbrock, 2012](https://faculty.washington.edu/wobbrock/pubs/chi-12.03.pdf).

### Exact proposed resolution rule

Apply resizing only to ordinary letter taps. Space, backspace, enter, shift, cursor gestures, alternates, accessibility exploration, and application-specified literal fields retain their own deterministic behavior.

1. Find the geometric key `k0`. If the touch lies inside any key's center anchor, return that key immediately. Start with a **proposed `0.35 W × 0.45 H` centered anchor**, measured as full dimensions.
2. Consider `k0` and neighboring letter cells whose rectangles expanded by **`0.15 W` horizontally and `0.10 H` vertically** contain the touch. Never steal a point from another key's anchor. These bounds limit boundary motion even under extreme context.
3. Compute a character prior from the current prefix and previous two words. Mix it with uniform letter mass: `p(c)=0.75*pTrie(c)+0.25/letterCount`. If the prefix is absent, use a character backoff model or uniform mass; do not force the prefix into a dictionary word.
4. Set `l(c)=max(log p(c) − max_j log p(j), −log 4)`. Rank candidates by `−spatialEnergy(t,c) + 0.5*l(c)`. Thus the proposed local LM advantage is bounded by `log 2`. Resolve ties to `k0`.
5. Preserve the original touch and both the geometric and emitted character. Never replace the likelihood with this posterior when scoring a word, because that would count the LM twice.

For equal one-dimensional Gaussians, the decision boundary shifts from the midpoint by `σ² * log(P1/P2) / centerDistance`, after applying the LM exponent. This derivation explains why an unbounded prior can move a boundary past a key center. Explicit anchors and support bounds avoid that behavior.

This anchor guarantee governs immediate tap output. A later, visibly offered word autocorrection is a separate action. If the product requires a center tap to be immutable even at word commit, make anchored observations hard constraints in word search too. That stronger policy sacrifices correction recall; it should not happen accidentally because the same spatial function was reused.

### Cheap character priors from a word trie and n-gram LM

Let `D(v)` be the vocabulary words below trie node `v`, including a word terminating there. Compute:

```text
M(v,h) = sum_{w in D(v)} P(w | previousTwoWords=h)
P(nextChar=c | prefix=v,h) = M(child(v,c),h) / M(v,h)
P(endOfWord | v,h) = P(wordAt(v) | h) / M(v,h)
```

Include end-of-word in the denominator; dropping it biases keys after complete short words. Personal user words need their own trie mass. Prefix probabilities condition on being an in-vocabulary word, so retain the uniform or character-model escape above for names and unknown words.

**Proposed efficient implementation:** assign word IDs in lexical trie order. Every node then covers a contiguous `[firstWordId,lastWordId]` interval. An interpolated discounted LM has `P_h(w)=D_h(w)+b_h*P_suffix(h)(w)`, where `D_h` is sparse nonnegative direct mass. For each active history, build a transient prefix-sum array over its successors sorted by word ID. Then:

```text
M_h(v) = rangeSum(D_h, interval(v)) + b_h*M_suffix(h)(v)
```

A range sum needs two binary searches; recurse through at most two word-history levels. Precompute unigram subtree mass. Cache results for visited nodes during the current word. Build history arrays once at the word boundary on a worker. Quantization must preserve normalization by recomputing residual backoff mass after decoding the stored direct masses. This is a proposed design based on the backoff representation, not a claim about Apple's or Google's code. Packed n-gram tries and quantized probability/backoff storage have established precedents. [KenLM data structures](https://kheafield.com/code/kenlm/structures/).

## 3. Word-level decoding

### Noisy-channel objective and published systems

The intended word `w`, its alignment `a`, observed taps `T`, and context `h` give:

```text
(w*,a*) = argmin_{w,a} [ Ctouch(T | w,a) + Cedit(a) − lambda*log P(w|h) ]
```

The alignment is necessary because a touch can be extra, a letter can lack a touch, or two observations can swap. A literal path must exist even when it is outside the static vocabulary. This is the noisy-channel approach used in published mobile decoders. [Ouyang et al., 2017](https://arxiv.org/abs/1704.03987); [Weir et al., 2014](https://www.keithv.com/pub/uncertain/WeirEtAlCHI2014.pdf).

Ouyang et al. compose spatial context, lexicon, and grammar as `(C ∘ L) ∘ G`, on demand. They discuss roughly 20 ms visible-feedback and 5–10 MB LM constraints, Gaussian spatial models, and low-order word n-grams, giving 64K words as an example. Missing letters advance the graph without consuming input; extra taps consume input without graph advancement. Completion uses surviving prefixes and reachable words. Literal/OOV paths and extra costs for changing valid words protect intentional input. They do not publish fixed edit costs, beam width, exact experimental LM size/order, or measured decode latency. They also do not specify a dedicated transposition operation. [Ouyang et al., 2017, §§2–4](https://arxiv.org/pdf/1704.03987).

Weir et al. combine touch uncertainty with a Witten–Bell character 7-gram, pruned to 225K n-grams and 2.0 MB compressed on disk. They support insertions and deletions with tuned costs, but do not publish numeric penalties or beam settings. Their current-word revision strategy avoids accumulating alignment errors from repeatedly revising the whole preceding sequence. Their pressure-model discussion gives inconsistent half/full-key-width sigma descriptions, so it is not a reliable numerical default. [Weir et al., 2014, pp. 2309–2312](https://www.keithv.com/pub/uncertain/WeirEtAlCHI2014.pdf).

VelociTap initially uses a 12-gram character LM with 103M n-grams and 1.1 GB storage, plus a 4-gram word LM with 194M n-grams, 2.0 GB storage, and 64K words. Its improved decoder adds word scores during search, an OOV penalty, and a distinct, cheaper space-insertion cost. Numeric edit penalties and beam settings are not published. Recognition runs on a remote multicore server. Its reported approximately 40 ms sentence decoding after later tuning is not an Android per-keystroke latency. [Vertanen et al., 2015, decoder and improved-decoder sections](https://www.keithv.com/pub/velocitap/velocitap.pdf).

AOSP LatinIME supplies concrete implementation constants: omission `0.467`, same-character omission `0.345`, first-character omission `0.5256`, insertion `0.7248`, same-character insertion `0.5508`, terminal insertion `0.8128`, transposition `0.5608`, space omission `0.1`, first completion `0.4836`, subsequent completion `0.00624`, and language-distance weight `1.1214`. Its ordinary/single-point node-cache limits are 170/310. These are LatinIME scoring units, not natural-log edit priors or Gboard beam settings. They demonstrate useful distinctions, but cannot be pasted into our score without conversion and tuning. [AOSP scoring parameters](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/native/jni/src/suggest/policyimpl/typing/scoring_params.cpp); [AOSP operation weighting](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/native/jni/src/suggest/policyimpl/typing/typing_weighting.cpp).

### Proposed compact decoder

Use a packed character trie plus a word trigram LM. Keep candidate generation separate from the decision to autocorrect. The following values are proposed seeds in natural-log-like energy units:

- Fixed-sigma touch cost `g(t,k)=min(0.5*[(dx−μx)^2/σx²+(dy−μy)^2/σy²], 6.0)`, with `σx=σy=0.50` initially.
- A missing intended letter costs `4.6`; an extra observed tap costs `4.6`; an adjacent transposition costs `3.9`, plus the crossed touch costs. A repeated-letter omission can start at `3.5`.
- Start `lambda=1.0`. Edit costs correspond to odds-scale assumptions near 1% or 2%, not measured frequencies of keyboard events.
- Keep at most **96 states per input position**, within **12 units** of the best lookahead priority. Allow **two structural edits per word**, at most **one consecutive missing-letter transition**, and at most **32 observations** before falling back to literal-only behavior.
- For substitution expansion, use up to **four best spatial keys plus the geometric literal key**. Keep the exact literal candidate outside the beam. These caps make the search approximate.
- Gather at most **32 completion candidates**, then rerank with exact word-context scores. Completion descendants are suggestions, never silent replacements just because the suffix is plausible.

Use a weighted edit lattice over the trie. A state contains `(node, i, edits, lastWasOmission, traceback)`, with the minimum accumulated channel cost. `i` is the number of consumed observations. Trie depth and edit count bound zero-input transitions. The context is fixed while decoding the current word.

```text
decode(T, h):
	frontier[0] = { rootState(channelCost=0) }
	for i = 0 .. T.size:
		states = boundedOmissionClosure(frontier[i])
		states = mergeAndPrune(states,
			priority = channelCost − lambda*log M(node,h),
			beamDelta = 12, stateLimit = 96)
		for s in states:
			if i == T.size:
				if terminal(s.node):
					offerCorrection(s.word,
						s.channelCost − lambda*log P(s.word|h))
				for w in boundedCompletionSearch(s.node, h, limit=32):
					offerCompletion(w,
						s.channelCost − lambda*log P(w|h)
						+ 1.5 + 0.15*untypedSuffixLength(w))
				continue
			for child c in spatiallyPlausibleChildren(s.node,T[i]):
				offer(frontier[i+1], advance(s,c), s.cost + g(T[i],c))
			if s.edits < 2:
				offer(frontier[i+1], extraTap(s), s.cost + 4.6)
			if s.edits < 2 and i+1 < T.size:
				for two-edge path c,d below s.node:
					if d plausible for T[i] and c plausible for T[i+1]:
						offer(frontier[i+2], advanceSwap(s,c,d),
							s.cost + g(T[i],d) + g(T[i+1],c) + 3.9)
	alwaysOfferLiteralAndTrustedUserWords()
	mergeSameSurfaceFormsAndReturnTopCandidates()

boundedOmissionClosure(states):
	for s in the original states:
		if s.edits < 2 and not s.lastWasOmission:
			for child c below s.node:
				add advanceWithoutInput(s,c), cost + omissionCost(c)
	return states plus added states
```

`offer` retains the cheapest path per complete state key. Do not merge states with different edit budgets or omission eligibility. The subtree mass is a pruning lookahead only. It is replaced by the exact word probability at a terminal, not added repeatedly. A language prior applied at every character would count the same word preference many times.

Keep corrections and completions distinct in metadata. A suffix added after the last aligned observation remains a completion for autocorrect policy, even if the edit lattice could describe it as trailing missing taps. Otherwise `hel` can become `help` merely when the user intended to end an unfamiliar word. Internal omissions such as `recive` can still generate corrections. Apostrophe variants need explicit lexical mappings with display forms; do not equate all punctuation-stripped spellings.

The pseudocode recomputes the short current word for clarity. An implementation can retain per-position frontiers, invalidate the last two positions for transpositions, and replay at most 32 taps after backspace or a layout change. Completion search uses contextual subtree bounds plus a maximum expansion count. Bounded search is not exhaustive; log search truncation as a numeric diagnostic, without logging text.

### Calibration and the 8 ms constraint

Fit sigma, edit penalties, and LM weight together on development traces, then choose policy thresholds on separate data. Proposed grid: sigma `0.35,0.40,0.45,0.50,0.55`; `lambda=0.5,0.75,1.0,1.25,1.5`; missing/extra cost `3.5–6.0`; swap cost `2.5–5.0`. Optimize corrected WER subject to a false-correction ceiling. A frequency score such as the current `ln(count+1)` is not a normalized context probability and is not a calibrated confidence.

**Proposed time allocation:** touch priors/resolution `0.3 ms`, search `4.0 ms`, LM/completions `1.2 ms`, ranking/policy `0.5 ms`, with `2.0 ms` slack inside 8 ms. These are allocations, not measurements. Bound both expansions, initially 8,000 per update, and elapsed CPU time. Check cancellation/deadline every 64 expansions. If the deadline approaches, publish a valid partial list with the literal preserved. Never let a stale result commit a newer word.

Use primitive arrays, pooled state buffers, integer node/word IDs, and precomputed key centers. Avoid per-node objects, substring allocation, and full-vocabulary scans. Touch feedback and composing-text insertion must not wait for decoding. Run search on a worker and attach a generation number to each request. Measure warm/cold p50, p95, p99, allocation, and end-to-end separator commit on named mid-range hardware. The literature does not prove that these Kotlin defaults meet 8 ms.

## 4. Autocorrect policy

### Why ranking and replacement need different thresholds

A high-ranked candidate need not be safe to insert. AOSP explicitly gates autocorrect separately and suppresses it for digits, mostly-capitalized words, resumed compositions, missing main dictionaries, and shortcut candidates. These policies are public reference behavior, not Gboard internals. [AOSP `Suggest.java`](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/src/com/android/inputmethod/latin/Suggest.java).

In a controlled 170-person study, prediction saved 3.43 characters per phrase but added about two seconds; correcting wrong autocorrections took 5.5 seconds on average. [Alharbi, Stuerzlinger, and Putze, 2020](https://vvise.iat.sfu.ca/user/data/papers/autocorrectfrustration.pdf). A later 20-person study compares oracle autocorrect, 10% failures, 20% failures, and no autocorrect. More failures increase frustration and physical/mental demand. Participants report meaning changes, difficult earlier-word repairs, unwanted sensitive suggestions, and uncertainty about deleting learned words. Neither experiment establishes an acceptable universal failure percentage. [Alharbi and Stuerzlinger, 2022](https://graphicsinterface.org/wp-content/uploads/gi2022-16.pdf).

A simple decision calculation illustrates the asymmetry. If a correct intervention saves an **estimated** 0.3 seconds and a wrong one costs 5.5 seconds, replacement has positive expected time value only when `p > 5.5/(5.5+0.3) = 0.948`. Social or attention costs justify a higher threshold. The benefit estimate and resulting policy are ours; 5.5 seconds comes from the study above.

### Proposed replacement rules

At space or correcting punctuation, offer the best correction automatically only when all eligibility checks pass:

- A calibrated estimate `P(best is intended) >= 0.98`.
- Best-versus-literal odds at least **20:1**, or `costLiteral−costBest >= log 20 ≈ 3.00` in a calibrated score space.
- Best-versus-runner-up odds at least **5:1**, or a margin of `log 5 ≈ 1.61`.
- The candidate is a correction, not a completion, and the edit structure passes the restrictions below.

These are proposed launch thresholds. A softmax of a truncated beam is not a calibrated posterior. Fit a small calibration model or isotonic mapping using top score, literal margin, runner-up margin, word length, edit count, spatial ambiguity, dictionary status, and OOV status. Evaluate calibration by probability bins and a reliability error measure. Retain literal candidates and account for unsearched/OOV alternatives; otherwise confidence is inflated.

Apply these proposed protections before score thresholds:

1. Preserve an ordinary valid literal word by default, including rare real words. Show `from` as a suggestion for `form`; do not silently change it merely because it is more frequent. Allow explicitly taught replacement pairs separately. Any future real-word correction mode needs its own high-precision evaluation.
2. Preserve one- and two-letter literals, with narrow explicit rules for English `i` to `I` and unambiguous apostrophe expansions. For three-letter words, permit at most one structural edit and require common-word support. Do not turn a novel abbreviation into a longer word.
3. Preserve mixed case, internal capitals, digits, usernames, URLs, email addresses, code-like tokens, explicit literal selections, and trusted user words. Title case at sentence start can receive a case-preserving correction. Do not invent a proper noun's spelling or title case from frequency alone.
4. Preserve expressive repetitions such as `sooo`, `yesss`, and `hahaha`. A collapsed alternative may appear in the strip. Repeated-letter penalties alone cannot distinguish expression from a typing error.
5. Disable automatic replacement when editing inside an existing word, after cursor movement, in password/literal fields, or after a correction was just rejected. Require a fresh composition before autocorrect resumes.
6. A manually taught pair is evidence about that pair and its context. One rewrite should not permanently force a replacement of an already valid word. Proposed threshold: two explicit repeats for a valid-word pair, with a visible way to remove it.

### Suggestion strip and undo

Gboard's published next-word predictor places the best probability in the center, second on the left, and third on the right. That is evidence for the documented prediction ordering, not proof that every Gboard UI mode has the same layout. [Hard et al., 2018, §2](https://arxiv.org/abs/1811.03604). Apple's guide describes the typed word in quotation marks and accepting a proposed correction with space, punctuation, or Return; repeatedly rejecting a suggestion suppresses it. Newer iOS also underlines corrections for one-tap reversion. [Apple predictive-text guide](https://support.apple.com/en-mz/guide/iphone/iphd4ea90231/ios); [Apple autocorrection support](https://support.apple.com/en-ie/104995).

For Wisperlow, keep three stable slots. When an eligible autocorrection exists, show literal left, the separator-commit choice in the center with a clear visual distinction, and an alternative right. Otherwise keep the literal accessible and put the strongest suggestion in the center without implying that space accepts it. For next-word prediction, use best center, second left, third right. The current `Suggestions` model already separates words from `autoCorrect`; preserve that distinction.

Keep immediate backspace reversion. Google documents backspace as the way to undo an automatic correction, plus long-press/drag removal of unwanted suggestions. [Gboard correction help](https://support.google.com/gboard/answer/7068415?hl=en). Wisperlow should restore both the original composing word and its touch history, consume the undo action once, and invalidate undo when the editor contents/cursor no longer match. Undo must also cancel pending positive learning from the rejected correction. Do not learn the wrong output and merely add the restored word on top.

**Proposed un-learning:** one explicit undo blocks the exact literal-to-replacement pair immediately in the current context. Keep a bounded persistent rejection record until the user selects that replacement deliberately or clears learned data. Long-press removal deletes user counts and prevents re-promotion through a separate learned bigram. Offer distinct actions for removing a personal word and hiding a static suggestion. Rejection should not silently delete a legitimate static word from correction lookup.

## 5. Language models

### What Google's published history actually says

The history is task-specific; a next-word network is not automatically the inner model for tap decoding.

- **2017 FST work:** compact word n-grams support tap/gesture decoding, completions, and predictions. Exact model order and experimental byte size are not disclosed. [Ouyang et al., 2017](https://arxiv.org/abs/1704.03987).
- **2018/2019 next-word deployment:** Hard et al. describe an English Katz-smoothed, Bayesian-interpolated 5-gram baseline with **1.25M n-grams, including 164K unigrams**. Their federated CIFG-LSTM uses a **10K word vocabulary**, **96-dimensional embeddings/projection**, **670 cells**, and **1.4M parameters**, quantized to a **1.4 MB** deployment model. On their client-cache evaluation, top-one recall is **12.5%** for the n-gram versus **15.8%** for federated CIFG. Production suggestion CTR moves from **2.13% to 2.35%**. These are prediction metrics, not corrected-word WER or per-tap latency. [Hard et al., 2018, arXiv:1811.03604](https://arxiv.org/abs/1811.03604).
- **2019 OOV discovery:** Chen et al. train a character CIFG model with federated learning, then sample new word candidates server-side. Small and large models have **216K and 758K parameters**. This discovers population vocabulary; it is not immediate personal-word acceptance. Their simulated vocabulary-recovery results do not establish individual spelling trust, and the paper discusses memorization and implausible generated strings. [Chen et al., 2019, arXiv:1903.10635](https://arxiv.org/abs/1903.10635).
- **2019 fast decoding LM:** another Chen et al. paper distills a federated RNN into an n-gram for latency. Its keyboard constraints are **under 10 MB**, **under 1.5M n-grams**, and **under 200K unigrams**. Higher-order vocabulary is restricted to the frequent **64K** words, with a unigram tail. This explains how a small neural next-word model can coexist with a larger lexical decoder. [Chen et al., CoNLL 2019](https://aclanthology.org/K19-1012/).
- **2019 emoji:** Ramaswamy et al. use a **10K word input vocabulary**, **96-dimensional embeddings**, two CIFG layers of **256 units each**, and **100 emoji outputs**. An additional no-emoji outcome gates display; frequency reweighting improves diversity. The quantized TFLite predictor averages about **1 ms**, without a sufficiently detailed device distribution to transfer that number to our Kotlin runtime. [Ramaswamy et al., 2019](https://arxiv.org/abs/1906.04329).
- **2024 and later:** Google's 2024 report describes over **30** on-device next-word LMs across **7+ languages** and **15+ countries**, using federated learning and differential privacy. A later report describes synthetic conversational pretraining and private federated adaptation, including key-decoder language models. Its **22.8% relative** next-word improvement concerns synthetic pretraining against a public-data baseline. It does not establish an LLM running per tap, current parameter counts, or current memory size. [Google, 2024](https://research.google/blog/advances-in-private-training-for-production-on-device-language-models/); [Google, synthetic and federated domain adaptation](https://research.google/blog/synthetic-and-federated-privacy-preserving-domain-adaptation-with-llms-for-mobile-applications/).

Apple publicly states that iOS 17 introduces a transformer language model for autocorrect and improved predictions. Apple does not disclose enough model-size, decoding, and latency information in that announcement to set our Kotlin budget. [Apple's iOS 17 announcement](https://www.apple.com/ie/newsroom/2023/06/ios-17-makes-iphone-more-personal-and-intuitive/).

### Proposed offline model under 8 MB

Start with a **word trigram using interpolated modified Kneser–Ney smoothing**. Retain selected frequent 4-grams only when held-out correction quality justifies their bytes. Reserve a normalized unknown-word path, sentence boundaries, and separate case/display metadata. A character model remains useful for OOV scoring and prefix escape, but cannot recover word context by itself.

**Proposed initial build envelope:** a 64K contextual core inside an approximately 80K-word lexical dictionary, with roughly 300K bigrams, 250K trigrams, and up to 50K selected 4-grams. Include the unigrams in record counts. Prefer a sound 64K lexicon plus personal words over a larger polluted vocabulary. Expand the lexical tail only after measuring OOV and false-correction effects.

The record counts are starting constraints, not quality predictions. The current English asset contains **46,724 entries**, **107,849 distinct lowercase prefixes**, and **326,170 UTF-8 surface bytes**, measured from `android/app/src/main/assets/dict/en.txt`. Those measurements give a useful packing baseline but exclude normalized-key/apostrophe variants and indexes. Reproduce with `python -I` by counting non-comment lines, their lowercase prefixes, and encoded word lengths. The asset has unigram counts only; it cannot supply true bigrams or trigrams.

**Estimated byte allocation, with an enforced total cap:**

- Packed lexical trie, word IDs, surface strings, and case metadata: **2.0 MB**.
- Quantized n-gram records and their history indexes: **4.2 MB**.
- Compact character backoff model for unknown strings: **0.4 MB**.
- Normalization tables, quantizers, manifest, and static prediction caches: **0.2 MB**.
- Unassigned headroom: **1.2 MB** inside **8.0 MB decimal**.

These are allocations for a custom format, not measured file sizes. In particular, an n-gram record needs its word ID, direct probability mass, any backoff/index information, and alignment overhead. Estimate bytes from the actual packed representation; do not assume every record costs the same or every pointer fits in 16 bits. A plain Kotlin object graph will not match these estimates.

Quantize log probabilities and backoff values to **8-bit per-order codebooks** initially; use 16 bits for unigrams if ranking/calibration needs it. Store sorted word IDs with packed or delta-coded integers and encode shared history ranges once. Keep probability normalization consistent with the trie-mass calculation in section 2. KenLM demonstrates bit-packed tries and independent probability/backoff quantization; its binary format is a reference, not automatically a Kotlin-readable or byte-for-byte suitable artifact. [Heafield, 2011](https://aclanthology.org/W11-2123/); [KenLM quantization documentation](https://kheafield.com/code/kenlm/structures/).

Use entropy-based pruning, then retrain/recompute backoff and measure held-out loss. Stolcke's criterion removes n-grams with small relative-entropy impact; in its speech task a model shrinks to 26% without increasing recognition error. That result motivates the method, not a promised keyboard compression ratio. Tune the pruning threshold to our byte cap and task results. [Stolcke, 1998](https://www.sri.com/publication/speech-natural-language-pubs/entropy-based-pruning-of-backoff-language-models/).

Prepare a corpus manifest recording origin, redistribution terms, cleaning, deduplication, genre weights, and checksums. The repository header attributes its current unigram source to MIT FrequencyWords/OpenSubtitles-derived data; this is a local attribution, not evidence that any arbitrary corpus or derived n-gram distribution is suitable for MIT redistribution. Keep model-data licensing distinct from runtime-code licensing. The initial training corpus must include legally usable conversational material and modern spelling. A public-domain book-only LM is a poor assumed substitute for the user's messaging distribution; measure domain mismatch rather than claiming a fixed quality loss.

**Expected quality:** contextual n-grams should supply information the current unigram/user-history engine lacks, particularly for ambiguous real-word candidates and first-session predictions. The direction follows the architecture and published LM comparisons; no defensible absolute WER, Gboard-parity claim, or percentage gain follows from an 8 MB file size. Evaluate before increasing order or vocabulary. Corpus quality and false-correction policy can dominate the choice between a trigram and a pruned 5-gram.

### Is a 1–3M-parameter int8 neural LM worthwhile?

It is viable in storage: **1–3M int8 weights occupy approximately 1–3 MB**, before scales, activations, vocabulary, runtime, and indexes. Hard et al.'s 1.4 MB predictor is a concrete precedent. Another published system uses a 15K vocabulary, 16-bit quantization, a 7.40 MB model, and **6.47 ms average next-word prediction on a Galaxy S7**. These demonstrate feasibility, not a pure-Kotlin mid-range latency guarantee. [Yu et al., 2018](https://aclanthology.org/C18-2028/).

**Proposed choice:** keep n-gram queries inside the tap-search beam. Prototype neural prediction at word boundaries or reranking of at most 16 completed candidates only after the n-gram baseline is measured. Cache the recurrent state for committed context. A small 10K-word network still needs a larger lexical/OOV fallback. A tied 64K×96 embedding alone needs about **6.14M parameters**, so a 1–3M budget does not buy that vocabulary/dimension pair without factorization, subwords, or a smaller embedding.

For a pure-Kotlin prototype, explicitly count multiply-accumulate operations, profile the scalar/array implementation, and include quantization conversions and output scoring. A native tensor runtime would alter the pure-Kotlin requirement and must be an explicit architecture choice. Neural distillation into a pruned n-gram is a practical alternative supported by the 2019 work above. Ship a neural model only if correction or prediction gains survive identical byte, latency, power, and false-correction constraints.

## 6. Next-word prediction and completions

Rank completed-word predictions by interpolated `P(w | lastTwoWords)` plus the personalization model in section 8. For a composing prefix, rank reachable words with touch alignment, context, and a modest proposed completion cost `1.5 + 0.15*untypedCharacters`. Use the same lexical IDs and normalization as correction search, while returning a distinct completion kind. Do not mix a zero-touch next-word prediction with a corrected current word.

**Proposed retrieval:** at a word boundary, merge the best successors from trigram, bigram, unigram, and user histories, deduplicate by normalized lexical identity, and rerank exactly. Start with **32 successors per available history**, then retain three visible predictions. Prefix search combines these contextual candidates with trie traversal so an uncommon prefix match is not lost merely because it was absent from the top 32. If a higher-recall reference search finds material misses, increase the pool or use subtree upper bounds.

Capitalization needs state: beginning of sentence, shift-once, caps lock, and a lexical display form. Preserve internal capitals in names. Carry word-level normalization separately from output text. Predict punctuation as explicit punctuation candidates or a separate boundary model; never map a high `P(endOfSentence)` directly to an inserted period. Start with existing deterministic double-space punctuation and explicit user-selected punctuation suggestions. Apostrophes and trailing spaces belong in commit behavior, not opaque language-model strings.

Keystroke savings is useful but not a speed guarantee. Quinn and Zhai find that suggestions reduce actions and are preferred, while the attention/selection costs reduce average typing speed. [Quinn and Zhai, 2016](https://research.google/pubs/a-costbenefit-study-of-text-entry-suggestion-interaction/). C-PAK's simulation reports maximum conventional-suggestion savings of **46.3%**, compared with **49.4%** for its phrase method. In its novice study, realized savings are **16% versus 25%**, while speed is **37.7 versus 28.5 WPM**, favoring conventional suggestions. These are different metrics and tasks. [Li, Quinn, and Zhai, author publication record and paper links](https://tianshili.me/publications/); [C-PAK, DOI](https://doi.org/10.1145/3544101).

Do not promise 46% savings in normal use. As an explicitly hypothetical calculation, if 15% of five-character words are accepted before any letters are typed, one selection replaces six actions including space, giving `0.15*(6−1)/6 = 12.5%` idealized action savings. Looking at the strip and mistaken selections can erase the time benefit. Measure actual and oracle savings separately, stabilize the center slot, and avoid reorderings caused only by tiny score differences. The proposed hysteresis is to retain slot order unless a challenger improves log score by `0.15`, except when the eligible autocorrect choice changes.
