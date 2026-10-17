package com.wisperlow.mobile.keyboard.lm

import kotlin.math.ln

/** A word id and how likely it is, as a natural log. */
data class ScoredWord(val id: Int, val logProb: Float)

/**
 * How likely words are in context. A context is the preceding word ids, oldest first;
 * [Vocabulary.UNKNOWN] marks a word the model doesn't know. Models read only as many
 * trailing context words as their order allows. All probabilities are natural logs.
 */
interface LanguageModel {
    val vocabulary: Vocabulary

    /** ln P(word | context). */
    fun logProb(word: Int, context: IntArray): Float

    /** ln P(word) whatever the context; cheap, for pruning searches. */
    fun unigramLogProb(word: Int): Float

    /** The likeliest next words after [context], best first, never [Vocabulary.SENTENCE_START]. */
    fun predict(context: IntArray, max: Int): List<ScoredWord>
}

/** A context-free model from word counts: a stand-in for tests and a floor under real models. */
class UnigramModel(override val vocabulary: Vocabulary, counts: LongArray) : LanguageModel {
    private val logProbs: FloatArray
    private val ranked: IntArray

    init {
        require(counts.size == vocabulary.size) { "one count per word" }
        val total = counts.sumOf { it.coerceAtLeast(1L).toDouble() }
        logProbs = FloatArray(counts.size) { ln(counts[it].coerceAtLeast(1L) / total).toFloat() }
        ranked = counts.indices
            .filter { vocabulary.surface(it) != Vocabulary.SENTENCE_START }
            .sortedByDescending { counts[it] }
            .toIntArray()
    }

    override fun logProb(word: Int, context: IntArray): Float = unigramLogProb(word)

    override fun unigramLogProb(word: Int): Float = if (word in logProbs.indices) logProbs[word] else UNKNOWN_LOG_PROB

    override fun predict(context: IntArray, max: Int): List<ScoredWord> = ranked.take(max).map { ScoredWord(it, logProbs[it]) }

    companion object {
        /** What models return for words they have never seen. */
        const val UNKNOWN_LOG_PROB = -20f
    }
}
