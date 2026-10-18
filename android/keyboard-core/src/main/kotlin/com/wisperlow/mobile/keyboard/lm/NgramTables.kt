package com.wisperlow.mobile.keyboard.lm

/**
 * A backoff n-gram model, up to trigrams, as plain arrays: what the estimator builds and
 * the binary format stores. Natural logs throughout. Each level's rows are sorted by
 * word ids, first word first, so lookups can binary search.
 *
 * P(w3 | w1 w2) is the trigram if listed, else backoff(w1 w2) + P(w3 | w2).
 * P(w2 | w1) is the bigram if listed, else backoff(w1) + P(w2).
 * A missing backoff weight is 0 (ln 1).
 */
class NgramTables(
    /** Word surfaces by id; may include [Vocabulary.SENTENCE_START]. */
    val vocabulary: List<String>,
    val unigramLogProb: FloatArray,
    val unigramBackoff: FloatArray,
    val bigramFirst: IntArray,
    val bigramSecond: IntArray,
    val bigramLogProb: FloatArray,
    val bigramBackoff: FloatArray,
    val trigramFirst: IntArray,
    val trigramSecond: IntArray,
    val trigramThird: IntArray,
    val trigramLogProb: FloatArray,
) {
    init {
        require(unigramLogProb.size == vocabulary.size && unigramBackoff.size == vocabulary.size)
        require(bigramSecond.size == bigramFirst.size && bigramLogProb.size == bigramFirst.size && bigramBackoff.size == bigramFirst.size)
        require(trigramSecond.size == trigramFirst.size && trigramThird.size == trigramFirst.size && trigramLogProb.size == trigramFirst.size)
    }

    val bigramCount: Int get() = bigramFirst.size
    val trigramCount: Int get() = trigramFirst.size
}
