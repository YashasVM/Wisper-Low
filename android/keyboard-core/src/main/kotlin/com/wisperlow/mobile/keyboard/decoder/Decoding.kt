package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.Touch

/** One typed letter: what the board typed and where it was touched (null when unknown, e.g. a word recomposed after backspace). */
data class TypedKey(val char: Char, val touch: Touch?)

enum class CandidateKind {
    /** Exactly the letters typed, known word or not. */
    LITERAL,

    /** A known word with the typed letters, ignoring case and apostrophes: "dont" to "don't". */
    EXACT,

    /** A known word the typed letters were probably a slip for. */
    CORRECTION,

    /** A known word that starts with (a likely reading of) the typed letters. */
    COMPLETION,
}

/**
 * A reading of the typed letters. [score] is the total ln score the decoder ranks by:
 * spatial evidence plus weighted language model plus edit penalties.
 */
data class Candidate(
    /** Vocabulary id, or [com.wisperlow.mobile.keyboard.lm.Vocabulary.UNKNOWN] for an unknown literal. */
    val wordId: Int,
    /** The surface to insert, before matching the case the user typed. */
    val word: String,
    val score: Float,
    val spatialScore: Float,
    val lmScore: Float,
    val kind: CandidateKind,
    /** Insertions, omissions and transpositions assumed. */
    val edits: Int,
    /**
     * The word came from the user's learned lexicon, not the word-list model, so [score] is
     * on the user-ordering scale rather than the decoder's calibrated log-probability scale.
     * A fix the user explicitly taught is not marked here: it has its own gate.
     */
    val userWord: Boolean = false,
)

/** Turns typed letters, in context, into likely words. */
interface TypingDecoder {
    /** The best readings of [input] after [context] (preceding word ids, oldest first), best first. */
    fun decode(input: List<TypedKey>, context: IntArray, max: Int = 12): List<Candidate>
}

/** How likely each next letter is, given the word so far and the words before; leans ambiguous taps toward likely keys. */
interface NextCharModel {
    /** ln P(next letter is c) for each plausible lowercase letter c; letters left out are negligible. */
    fun logProbs(prefix: String, context: IntArray): Map<Char, Float>
}
