package com.wisperlow.mobile.keyboard.lm

/**
 * The unquantized reference [LanguageModel] over [NgramTables]: applies the backoff
 * rules exactly as documented on [NgramTables]. Other implementations (the binary
 * [NgramModel], on-device variants) are tested against this one.
 */
class TablesModel(private val tables: NgramTables) : LanguageModel {
    override val vocabulary: Vocabulary = Vocabulary(tables.vocabulary)

    override fun unigramLogProb(word: Int): Float =
        if (word in tables.unigramLogProb.indices) tables.unigramLogProb[word] else UnigramModel.UNKNOWN_LOG_PROB

    override fun logProb(word: Int, context: IntArray): Float {
        val n = context.size
        return when {
            n == 0 -> unigramLogProb(word)
            n == 1 -> bigram(context[0], word)
            else -> {
                val w1 = context[n - 2]
                val w2 = context[n - 1]
                val ti = trigramIndex(w1, w2, word)
                if (ti >= 0) {
                    tables.trigramLogProb[ti]
                } else {
                    val row = bigramRow(w1, w2)
                    val bo = if (row >= 0) tables.bigramBackoff[row] else 0f
                    bo + bigram(w2, word)
                }
            }
        }
    }

    override fun predict(context: IntArray, max: Int): List<ScoredWord> =
        (0 until vocabulary.size)
            .filter { vocabulary.surface(it) != Vocabulary.SENTENCE_START }
            .map { ScoredWord(it, logProb(it, context)) }
            .sortedByDescending { it.logProb }
            .take(max)

    private fun bigram(w1: Int, w2: Int): Float {
        val row = bigramRow(w1, w2)
        return if (row >= 0) {
            tables.bigramLogProb[row]
        } else {
            val bo = if (w1 in tables.unigramBackoff.indices) tables.unigramBackoff[w1] else 0f
            bo + unigramLogProb(w2)
        }
    }

    private fun bigramRow(w1: Int, w2: Int): Int {
        var lo = 0
        var hi = tables.bigramFirst.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val f = tables.bigramFirst[mid]
            when {
                f < w1 -> lo = mid + 1
                f > w1 -> hi = mid - 1
                else -> {
                    val s = tables.bigramSecond[mid]
                    when {
                        s < w2 -> lo = mid + 1
                        s > w2 -> hi = mid - 1
                        else -> return mid
                    }
                }
            }
        }
        return -1
    }

    private fun trigramIndex(w1: Int, w2: Int, w3: Int): Int {
        var lo = 0
        var hi = tables.trigramFirst.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val f = tables.trigramFirst[mid]
            when {
                f < w1 -> lo = mid + 1
                f > w1 -> hi = mid - 1
                else -> {
                    val s = tables.trigramSecond[mid]
                    when {
                        s < w2 -> lo = mid + 1
                        s > w2 -> hi = mid - 1
                        else -> {
                            val t = tables.trigramThird[mid]
                            when {
                                t < w3 -> lo = mid + 1
                                t > w3 -> hi = mid - 1
                                else -> return mid
                            }
                        }
                    }
                }
            }
        }
        return -1
    }
}
