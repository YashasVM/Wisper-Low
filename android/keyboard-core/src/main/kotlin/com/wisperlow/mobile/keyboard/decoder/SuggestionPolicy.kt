package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.Lexicon
import com.wisperlow.mobile.keyboard.Suggestions
import com.wisperlow.mobile.keyboard.lm.Vocabulary

/** How eagerly the typed word is replaced when it is committed. */
enum class CorrectionStrength {
    /** Suggest, never replace: the letters typed are what space commits. */
    OFF,

    /** Fix obvious slips of everyday words; keep real words as typed ([TypedFacts.literalKnown]). */
    MODEST,

    /** Fix likely slips of real words too, accepting more false corrections. */
    AGGRESSIVE,
}

/**
 * What the rest of the engine already knows about the word being typed, so the policy
 * need not re-derive it. Ranking is the decoder's job; replacing a word is a decision
 * with an asymmetric cost (docs/keyboard/research/decoding-science.md section 4), so it
 * is made here from calibrated margins plus facts about trust and case.
 */
data class TypedFacts(
    /** The typed letters are a dictionary or learned word, so replacing them costs more. */
    val literalKnown: Boolean,
    /** The user restored this literal after an undo; nothing may replace it. */
    val userTrusted: Boolean,
    val strength: CorrectionStrength = CorrectionStrength.MODEST,
    /**
     * ln P(typed literal) - ln P(the model's most probable word), so it is at most 0 and
     * unchanged by any uniform scaling of the counts. 0 means unknown or unpriced, which
     * the policy reads as "not rare".
     */
    val literalLogOdds: Float = 0f,
)

/** Every threshold the policy weighs, in natural-log score units so they read as odds. */
data class PolicyParams(
    /** A short-word slip fix must beat the literal by more than this to count as taught. */
    val taughtMargin: Float = 3.0f,
    /** A rival this close to the correction target makes the call ambiguous. */
    val ambiguityMargin: Float = 0.7f,
    /** A completion beating the target by more than this means the user is still typing. */
    val completionBlockMargin: Float = 1.0f,
    /** Literal not in the dictionary, MODEST then AGGRESSIVE. */
    val unknownLiteralMargin: Float = 1.5f,
    val unknownLiteralAggressiveMargin: Float = 0.5f,
    /** Literal is a real word, MODEST then AGGRESSIVE: much harder to replace. */
    val knownLiteralMargin: Float = 7.0f,
    val knownLiteralAggressiveMargin: Float = 5.0f,
    /** Same-normalized surface restoration (apostrophe/case) only needs this much lead. */
    val variantMargin: Float = 3.5f,
    /**
     * A dictionary literal at or above this log-odds against the model's most probable word
     * (about 1 in 2200) is common enough to keep the middle slot over completions.
     */
    val commonLiteralLogOdds: Float = -7.7f,
    /**
     * A dictionary literal below this log-odds (about 1 in 27000) is rare enough that its
     * entry may be a misspelling rather than a word the user meant.
     */
    val rareLiteralLogOdds: Float = -10.2f,
    /** ln ratio a rare literal's correction must beat it by to replace it (about 50x). */
    val rareLiteralRatio: Float = 3.9f,
    /** Three letters leave little evidence, so correcting them takes extra margin. */
    val shortWordExtraMargin: Float = 1.0f,
)

/**
 * Turns decoder candidates into the words shown on the strip and the word that space
 * will type. The middle slot is the space-commit choice; a correction only replaces
 * when every protection and margin passes, and never for trusted or odd literals.
 */
class SuggestionPolicy(private val params: PolicyParams = PolicyParams()) {

    /** One candidate together with the word exactly as it would be shown. */
    private class Shown(val index: Int, val candidate: Candidate, val word: String)

    fun decide(typed: String, candidates: List<Candidate>, facts: TypedFacts, slots: Int = 3): Suggestions {
        if (typed.isBlank() || slots <= 0) return Suggestions.None
        // Every word on the strip carries the capitalization the user typed ("Teh" to "The").
        val shown = candidates.mapIndexed { i, c -> Shown(i, c, Lexicon.matchCase(typed, c.word)) }
        val literal = literalOf(shown, typed)
        val target = autocorrectTarget(shown, typed)
        val auto = target
            ?.takeIf { mayReplace(typed, it, literal, shown, facts) }
            ?.word
            ?.takeIf { it != typed }
        val others = shown
            .asSequence()
            .filter {
                it.candidate.kind != CandidateKind.LITERAL &&
                    !(auto != null && it.word.equals(auto, ignoreCase = true)) &&
                    run {
                        val same = it.word.equals(typed, ignoreCase = true)
                        val caseVariantAllowed = typed.all { c -> !c.isLetter() || c.isLowerCase() } && it.word.any { c -> c.isUpperCase() }
                        !(same && !caseVariantAllowed)
                    }
            }
            .sortedByDescending { it.candidate.score }
            .distinctBy { it.word.lowercase() }
            .map { it.word }
            .toList()
        // A common word is likelier to be what the user meant than a completion of it.
        val common = facts.literalKnown && facts.literalLogOdds >= params.commonLiteralLogOdds
        // Slots read left to right; the middle one is what space will type, as on most keyboards.
        val words = when {
            auto != null -> listOf(typed, auto) + others.take(1)
            common -> listOfNotNull(others.getOrNull(0), typed, others.getOrNull(1))
            else -> listOf(typed) + others.take(2)
        }
        return Suggestions(words.take(slots), autoCorrect = auto)
    }

    /** The reading that is exactly what was typed: a LITERAL candidate, or an EXACT one spelled so. */
    private fun literalOf(shown: List<Shown>, typed: String): Shown? =
        shown.firstOrNull { it.candidate.kind == CandidateKind.LITERAL }
            ?: shown.firstOrNull {
                it.candidate.kind == CandidateKind.EXACT && it.candidate.word.equals(typed, ignoreCase = true)
            }

    /** Best candidate that could replace the typed word: a slip, or an EXACT spelling change. */
    private fun autocorrectTarget(shown: List<Shown>, typed: String): Shown? = shown
        .filter {
            it.candidate.kind == CandidateKind.CORRECTION ||
                (it.candidate.kind == CandidateKind.EXACT && it.candidate.word != typed)
        }
        .maxByOrNull { it.candidate.score }

    private fun mayReplace(typed: String, target: Shown, literal: Shown?, shown: List<Shown>, facts: TypedFacts): Boolean {
        if (facts.strength == CorrectionStrength.OFF || facts.userTrusted) return false
        if (isProtected(typed)) return false
        // Ambient learning scores words on the user-ordering scale, not the decoder's
        // log-probability scale, so a merely learned word (a rival the user happens to have
        // typed before) never replaces a real dictionary word. Only an explicit fix, which has
        // its own gate, or the calibrated model may.
        if (facts.literalKnown && target.candidate.userWord) return false
        val baseLiteral = literal ?: return false
        val variant = target.candidate.kind == CandidateKind.EXACT && Lexicon.key(target.candidate.word) == Lexicon.key(typed)
        if (variant) {
            val key = Lexicon.key(typed)
            if (key in AMBIGUOUS) return false
        }
        // A correction of a word the user typed is measured against that word's real probability.
        // When its listed spelling differs only in case ("Ben" for "ben"), the raw spelling is not
        // in the list and would be priced as unknown, letting any correction win. A case-only
        // variant (an EXACT target) is a different, weaker call and keeps the raw literal price.
        val literalCandidate = if (!variant && baseLiteral.candidate.wordId == Vocabulary.UNKNOWN) {
            shown.firstOrNull {
                it.candidate.kind == CandidateKind.EXACT && it.candidate.word.lowercase() == typed.lowercase()
            }?.candidate ?: baseLiteral.candidate
        } else {
            baseLiteral.candidate
        }
        val margin = target.candidate.score - literalCandidate.score
        if (typed.length <= 2 && !variant && !shortWordPasses(typed, target, margin)) return false
        // A rare dictionary word may be a misspelling, but only a correction far more
        // probable than it in calibrated log-probability terms may replace it.
        val rare = !variant && facts.literalKnown && facts.literalLogOdds < params.rareLiteralLogOdds
        if (rare && target.candidate.lmScore - literalCandidate.lmScore < params.rareLiteralRatio) return false
        val required = when {
            variant -> params.variantMargin
            else -> requiredMargin(typed, facts.literalKnown && !rare, facts.strength)
        }
        if (margin <= required) return false
        if (rivalNear(target, shown, typed)) return false
        if (shown.any { it.candidate.kind == CandidateKind.COMPLETION && it.candidate.score > target.candidate.score + params.completionBlockMargin }) {
            return false
        }
        return true
    }

    /**
     * One- and two-letter literals stay as typed, with narrow exceptions: the pronoun
     * "i" to "I", and a slip fix with the odds of a fix the user taught the keyboard.
     */
    private fun shortWordPasses(typed: String, target: Shown, margin: Float): Boolean =
        (typed.equals("i", ignoreCase = true) && target.word == "I") ||
            (target.candidate.kind == CandidateKind.CORRECTION && margin > params.taughtMargin)

    private fun requiredMargin(typed: String, known: Boolean, strength: CorrectionStrength): Float {
        val base = when (strength) {
            CorrectionStrength.MODEST -> if (known) params.knownLiteralMargin else params.unknownLiteralMargin
            CorrectionStrength.AGGRESSIVE -> if (known) params.knownLiteralAggressiveMargin else params.unknownLiteralAggressiveMargin
            CorrectionStrength.OFF -> return Float.POSITIVE_INFINITY
        }
        return base + if (typed.length == 3) params.shortWordExtraMargin else 0f
    }

    /** Inner capitals and digits are deliberate ("iPhone", "camelCase", "2day"); never replace. */
    private fun isProtected(typed: String): Boolean =
        typed.any { it.isDigit() } ||
            (typed.drop(1).any { it.isUpperCase() } && typed.any { it.isLowerCase() })

    /**
     * A candidate nearly as good as the target makes the fix a coin flip: blocked. Words
     * that differ only by case are the same word, so duplicates of the target don't count.
     */
    private fun rivalNear(target: Shown, shown: List<Shown>, typed: String): Boolean {
        val seen = HashSet<String>()
        for (rival in shown.sortedByDescending { it.candidate.score }) {
            val key = rival.word.lowercase()
            if (key == target.word.lowercase() || !seen.add(key)) continue
            val kind = rival.candidate.kind
            if (kind == CandidateKind.LITERAL || kind == CandidateKind.COMPLETION) continue
            if (kind == CandidateKind.EXACT && rival.candidate.word.equals(typed, ignoreCase = true)) continue
            if (rival.candidate.score > target.candidate.score - params.ambiguityMargin) return true
        }
        return false
    }
    companion object {
        private val AMBIGUOUS = setOf("its", "well", "were", "hell", "shell", "ill", "lets", "shed", "wed", "id", "hed", "whos", "cause")
    }

}
