package com.wisperlow.mobile.keyboard.lm

import kotlin.math.exp
import kotlin.math.ln

/**
 * Count floors per order, applied to the counts before estimation. A row below its floor
 * behaves as never observed. Defaults keep every n-gram the counter found. A word below
 * [minUnigramCount] drops out of the model's probability space and keeps only negligible
 * mass, which is how a model sheds its long tail without breaking normalization.
 */
data class EstimatorConfig(
    val minUnigramCount: Int = 1,
    val minBigramCount: Int = 1,
    val minTrigramCount: Int = 1,
) {
    init {
        require(minUnigramCount >= 1 && minBigramCount >= 1 && minTrigramCount >= 1) {
            "min counts are positive; 1 keeps every observed n-gram"
        }
    }
}

/**
 * Interpolated modified Kneser-Ney (Chen & Goodman): three discounts per order fitted from
 * count-of-counts, and continuation counts as the unigram base, so a word is likely when it
 * follows many different words, not merely when it is frequent.
 *
 * The output is the backoff form [NgramTables] documents: a listed n-gram stores ln of the
 * interpolated probability, and each backoff weight stores ln of that context's interpolation
 * weight lambda, so backing a missing n-gram off to the lower order adds exactly the mass
 * the interpolated formula would have given it and the documented rules sum to one.
 */
object NgramEstimator {
    /** Count-space floor keeping ln-probabilities finite for words with no continuation mass. */
    private const val NEGLIGIBLE_COUNT = 1e-6

    /** Three discounts for counts 1, 2 and 3+, fitted per order from count-of-counts. */
    private class Discounts(val first: Double, val second: Double, val third: Double) {
        fun at(count: Int): Double = when (count) {
            1 -> first
            2 -> second
            else -> third
        }
    }

    /** [onDiscountFallback] reports the affected order (2 or 3) for compiler diagnostics. */
    fun estimate(
        counts: NgramCounts,
        config: EstimatorConfig = EstimatorConfig(),
        onDiscountFallback: (Int) -> Unit = {},
    ): NgramTables {
        val words = counts.vocabulary.size
        require(words > 0) { "cannot estimate an empty vocabulary" }
        val bigramKept = BooleanArray(counts.bigramCounts.size) { counts.bigramCounts[it] >= config.minBigramCount }
        // A trigram whose two-word context lost its bigram row has nowhere to store its
        // backoff weight (that lives on the bigram row), so it goes with the context.
        val trigramKept = BooleanArray(counts.trigramCounts.size) {
            counts.trigramCounts[it] >= config.minTrigramCount &&
                keptBigramRow(counts, bigramKept, counts.trigramFirst[it], counts.trigramSecond[it]) >= 0
        }

        // Unigram base: continuation counts over surviving bigrams. The floor keeps every
        // ln-probability finite, and pins the sentence-start token (which only ever appears
        // as context) to negligible mass so it is never really predicted.
        val continuation = LongArray(words)
        for (i in counts.bigramCounts.indices) if (bigramKept[i]) continuation[counts.bigramSecond[i]]++
        val sentenceStart = counts.vocabulary.indexOf(Vocabulary.SENTENCE_START)
        val weight = DoubleArray(words) {
            if (it == sentenceStart || counts.unigramCounts[it] < config.minUnigramCount) {
                NEGLIGIBLE_COUNT
            } else {
                continuation[it].toDouble().coerceAtLeast(NEGLIGIBLE_COUNT)
            }
        }
        val baseTotal = weight.sum()
        val unigramLogProb = FloatArray(words) { ln(weight[it] / baseTotal).toFloat() }

        // Bigram order: for each context (a first word), discount its successors and hand the
        // leftover mass to the unigram base through lambda, stored as that word's backoff.
        val bigramDiscounts = discountsOf(counts.bigramCounts) { onDiscountFallback(2) }
        val keptBigrams = bigramKept.count { it }
        val bigramFirst = IntArray(keptBigrams)
        val bigramSecond = IntArray(keptBigrams)
        val bigramLogProb = FloatArray(keptBigrams)
        val bigramBackoff = FloatArray(keptBigrams)
        val bigramOutRow = IntArray(counts.bigramCounts.size) { -1 }
        val unigramBackoff = FloatArray(words)
        var bigramOut = 0
        var i = 0
        while (i < counts.bigramCounts.size) {
            if (!bigramKept[i]) {
                i++
                continue
            }
            val context = counts.bigramFirst[i]
            var end = i
            var total = 0L
            while (end < counts.bigramCounts.size && counts.bigramFirst[end] == context) {
                if (bigramKept[end]) total += counts.bigramCounts[end]
                end++
            }
            var removedMass = 0.0
            for (row in i until end) if (bigramKept[row]) {
                removedMass += bigramDiscounts.at(counts.bigramCounts[row]) / total
            }
            val lambda = removedMass.coerceAtMost(1.0)
            unigramBackoff[context] = ln(lambda).toFloat()
            for (row in i until end) if (bigramKept[row]) {
                val successor = counts.bigramSecond[row]
                bigramOutRow[row] = bigramOut
                bigramFirst[bigramOut] = context
                bigramSecond[bigramOut] = successor
                val share = discountedShare(bigramDiscounts, counts.bigramCounts[row], total)
                bigramLogProb[bigramOut] =
                    ln(share + lambda * exp(unigramLogProb[successor].toDouble())).toFloat()
                bigramOut++
            }
            i = end
        }

        // ln P(word | first) at the bigram level: the stored bigram, else backoff + unigram.
        fun successorProbability(first: Int, word: Int): Double {
            val row = keptBigramRow(counts, bigramKept, first, word)
            if (row >= 0) return exp(bigramLogProb[bigramOutRow[row]].toDouble())
            return exp(unigramBackoff[first].toDouble()) * exp(unigramLogProb[word].toDouble())
        }

        // Trigram order: same shape, but the lower order is the interpolated bigram and lambda
        // lands on the context's bigram row. Contexts with no surviving trigrams keep backoff 0
        // (ln 1), which is exactly lambda 1: back straight off to the bigram.
        val trigramDiscounts = discountsOf(counts.trigramCounts) { onDiscountFallback(3) }
        val keptTrigrams = trigramKept.count { it }
        val trigramFirst = IntArray(keptTrigrams)
        val trigramSecond = IntArray(keptTrigrams)
        val trigramThird = IntArray(keptTrigrams)
        val trigramLogProb = FloatArray(keptTrigrams)
        var trigramOut = 0
        i = 0
        while (i < counts.trigramCounts.size) {
            if (!trigramKept[i]) {
                i++
                continue
            }
            val first = counts.trigramFirst[i]
            val second = counts.trigramSecond[i]
            var end = i
            var total = 0L
            while (end < counts.trigramCounts.size &&
                counts.trigramFirst[end] == first &&
                counts.trigramSecond[end] == second
            ) {
                if (trigramKept[end]) total += counts.trigramCounts[end]
                end++
            }
            var removedMass = 0.0
            for (row in i until end) if (trigramKept[row]) {
                removedMass += trigramDiscounts.at(counts.trigramCounts[row]) / total
            }
            val lambda = removedMass.coerceAtMost(1.0)
            val contextRow = keptBigramRow(counts, bigramKept, first, second)
            bigramBackoff[bigramOutRow[contextRow]] = ln(lambda).toFloat()
            for (row in i until end) if (trigramKept[row]) {
                val third = counts.trigramThird[row]
                val share = discountedShare(trigramDiscounts, counts.trigramCounts[row], total)
                trigramFirst[trigramOut] = first
                trigramSecond[trigramOut] = second
                trigramThird[trigramOut] = third
                trigramLogProb[trigramOut] =
                    ln(share + lambda * successorProbability(second, third)).toFloat()
                trigramOut++
            }
            i = end
        }

        return NgramTables(
            vocabulary = counts.vocabulary,
            unigramLogProb = unigramLogProb,
            unigramBackoff = unigramBackoff,
            bigramFirst = bigramFirst,
            bigramSecond = bigramSecond,
            bigramLogProb = bigramLogProb,
            bigramBackoff = bigramBackoff,
            trigramFirst = trigramFirst,
            trigramSecond = trigramSecond,
            trigramThird = trigramThird,
            trigramLogProb = trigramLogProb,
        )
    }

    /** The listed share of a context's mass: its discounted count over the context total. */
    private fun discountedShare(discounts: Discounts, count: Int, total: Long): Double =
        (count - discounts.at(count)).coerceAtLeast(0.0) / total

    /** Index of the bigram row [first, word] when it survived pruning, else -1. */
    private fun keptBigramRow(counts: NgramCounts, bigramKept: BooleanArray, first: Int, word: Int): Int {
        var lo = counts.bigramStart(first)
        var hi = counts.bigramStart(first + 1)
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val second = counts.bigramSecond[mid]
            when {
                second < word -> lo = mid + 1
                second > word -> hi = mid
                else -> return if (bigramKept[mid]) mid else -1
            }
        }
        return -1
    }

    /**
     * Fit before estimator pruning: removing singleton/doubleton rows destroys the fit.
     * The counter must also preserve them. For missing buckets or a nonpositive/out-of-range
     * fit, use KenLM's documented fallback D1=0.5, D2=1, D3+=1.5 for the entire order.
     * This is a training-data fallback, never a lambda chosen against typing evaluation.
     * See https://github.com/kpu/kenlm/blob/master/lm/builder/adjust_counts.cc.
     */
    private fun discountsOf(counts: IntArray, onFallback: () -> Unit): Discounts {
        val buckets = LongArray(5)
        for (count in counts) if (count in 1..4) buckets[count]++
        val fallback = Discounts(0.5, 1.0, 1.5)
        if ((1..3).any { buckets[it] == 0L }) return fallback.also { if (counts.isNotEmpty()) onFallback() }
        val y = buckets[1].toDouble() / (buckets[1] + 2.0 * buckets[2])
        val fitted = DoubleArray(3) { index ->
            val k = index + 1
            k - (k + 1) * y * buckets[k + 1] / buckets[k]
        }
        if (fitted.indices.any { !fitted[it].isFinite() || fitted[it] <= 0.0 || fitted[it] > it + 1 }) {
            return fallback.also { onFallback() }
        }
        return Discounts(fitted[0], fitted[1], fitted[2])
    }
}
