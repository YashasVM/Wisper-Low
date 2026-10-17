package com.wisperlow.mobile.keyboard.lm

import kotlin.math.exp
import kotlin.math.ln

/**
 * A base [LanguageModel] with this user's habits blended in. Word ids keep the base's
 * meaning: [vocabulary] is the base's words with the user's own words appended after
 * them, and is rebuilt only when the user adds or drops a word. ln P is
 * ln((1 - lambda) P_base + lambda P_user), where lambda = n / (n + 8) grows with how
 * often the user has been in that context, so their typing bends the base model
 * without inventing probabilities from nothing.
 */
class InterpolatedModel(private val base: LanguageModel, private val user: UserHistory) : LanguageModel {
    private class VocabCache(val revision: Int, val vocabulary: Vocabulary)
    private class TopCache(val countsRevision: Int, val surfaces: List<String>)

    @Volatile
    private var vocabCache: VocabCache? = null

    @Volatile
    private var topCache: TopCache? = null

    override val vocabulary: Vocabulary
        get() = buildVocabulary()

    override fun logProb(word: Int, context: IntArray): Float {
        val vocab = buildVocabulary()
        val pBase = exp(base.logProb(word, context)).coerceAtLeast(MIN_PROB)
        val lambda = lambdaFor(vocab, context)
        if (lambda <= 0f) return ln(pBase)
        return ln((1 - lambda) * pBase + lambda * userProb(vocab, word, context))
    }

    override fun unigramLogProb(word: Int): Float {
        val total = user.totalCount()
        val lambda = (total / (total + EVIDENCE_HALF)).toFloat()
        val pBase = exp(base.unigramLogProb(word)).coerceAtLeast(MIN_PROB)
        if (lambda <= 0f) return ln(pBase)
        val vocab = buildVocabulary()
        val pUser = if (word in 0 until vocab.size) user.count(vocab.surface(word)) / total else 0.0
        return ln((1 - lambda) * pBase + lambda * pUser.toFloat().coerceAtLeast(MIN_PROB))
    }

    override fun predict(context: IntArray, max: Int): List<ScoredWord> {
        if (max <= 0) return emptyList()
        val vocab = buildVocabulary()
        val ids = LinkedHashSet<Int>()
        for (scored in base.predict(context, max)) ids += scored.id
        val previous = lastSurface(vocab, context)
        val own: List<String> = if (previous == null) emptyList() else user.topAfter(previous, max).map { it.first }
        for (surface in own.ifEmpty { buildTop().take(max) }) {
            val id = vocab.id(surface)
            if (id != Vocabulary.UNKNOWN) ids += id
        }
        return ids.map { ScoredWord(it, logProb(it, context)) }.sortedByDescending { it.logProb }.take(max)
    }

    /** How much the user's evidence for this context outweighs the base: n / (n + 8). */
    private fun lambdaFor(vocab: Vocabulary, context: IntArray): Float {
        val previous = lastSurface(vocab, context) ?: return 0f
        // Pairs recorded after a word weigh as evidence for the context even when the word
        // was itself never learned as an output of this history.
        val n = maxOf(user.count(previous), user.evidenceAfter(previous))
        return (n / (n + EVIDENCE_HALF)).toFloat()
    }

    /**
     * The user's own P(word | context): their bigram after the last context word when
     * they have typed one, else their unigram estimate.
     */
    private fun userProb(vocab: Vocabulary, word: Int, context: IntArray): Float {
        if (word !in 0 until vocab.size) return MIN_PROB
        val surface = vocab.surface(word)
        val previous = lastSurface(vocab, context)
        val evidence = previous?.let { user.evidenceAfter(it) } ?: 0.0
        val p = if (previous != null && evidence > 0.0) {
            user.pairCount(previous, surface) / evidence
        } else {
            val total = user.totalCount()
            if (total > 0.0) user.count(surface) / total else 0.0
        }
        return p.toFloat().coerceAtLeast(MIN_PROB)
    }

    /** The surface of the last context word, null for a bare context or one the model rejects. */
    private fun lastSurface(vocab: Vocabulary, context: IntArray): String? {
        val last = context.lastOrNull() ?: return null
        return if (last in 0 until vocab.size) vocab.surface(last) else null
    }

    /** The base vocabulary with the user's own words appended; ids after the base keep meaning. */
    private fun buildVocabulary(): Vocabulary {
        vocabCache?.let { if (it.revision == user.revision) return it.vocabulary }
        return synchronized(this) {
            val cached = vocabCache
            if (cached != null && cached.revision == user.revision) {
                cached.vocabulary
            } else {
                // Read the revision before the snapshot, so a word added meanwhile is caught next time.
                val revision = user.revision
                val own = user.words().keys
                val baseWords = base.vocabulary
                val surfaces = ArrayList<String>(baseWords.size + own.size)
                for (id in 0 until baseWords.size) surfaces += baseWords.surface(id)
                surfaces += own.filter { baseWords.id(it) == Vocabulary.UNKNOWN }.sorted()
                val made = VocabCache(revision, Vocabulary(surfaces))
                vocabCache = made
                made.vocabulary
            }
        }
    }

    /** The user's most-typed words, best first; refreshed when counts move, unlike the vocabulary. */
    private fun buildTop(): List<String> {
        topCache?.let { if (it.countsRevision == user.countsRevision) return it.surfaces }
        return synchronized(this) {
            val cached = topCache
            if (cached != null && cached.countsRevision == user.countsRevision) {
                cached.surfaces
            } else {
                val countsRevision = user.countsRevision
                val surfaces = user.words().entries.sortedByDescending { it.value }.map { it.key }
                val made = TopCache(countsRevision, surfaces)
                topCache = made
                made.surfaces
            }
        }
    }

    companion object {
        /** Eight pieces of evidence make the user's habits weigh as much as the base model's. */
        private const val EVIDENCE_HALF = 8.0

        /** Probabilities under this are no probability at all, so logs stay finite. */
        private const val MIN_PROB = 1e-12f
    }
}
