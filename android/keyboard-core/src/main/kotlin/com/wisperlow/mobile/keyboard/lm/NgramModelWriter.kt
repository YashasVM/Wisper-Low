package com.wisperlow.mobile.keyboard.lm

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Serializes [NgramTables] to the compact binary format v1 ("WLM1"), little-endian
 * throughout. Aims at ~4-5 bytes per bigram/trigram plus ~1 KB per codebook.
 *
 * Layout (byte offsets from the start of the buffer):
 * ```
 * 0    4   magic "WLM1"
 * 4    1   version (1)
 * 5    4   vocabSize                       u32
 * 9    4   bigramCount                     u32
 * 13   4   trigramCount                    u32
 * 17   ... vocabulary: per word, u16 UTF-8 byte length + bytes, in id order
 *      ... unigram codebook                256 x f32
 *      ... unigram backoff codebook        256 x f32
 *      ... bigram codebook                 256 x f32
 *      ... bigram backoff codebook         256 x f32
 *      ... trigram codebook                256 x f32
 *      ... unigram prob codes              vocabSize x u8
 *      ... unigram backoff codes           vocabSize x u8
 *      ... bigram prob codes               bigramCount x u8
 *      ... bigram backoff codes            bigramCount x u8
 *      ... trigram prob codes              trigramCount x u8
 *      ... bigram offsets                  (vocabSize+1) x u32 - first-word group starts
 *      ... bigram second ids               bigramCount x u16
 *      ... trigram offsets                 (bigramCount+1) x u32 - bigram-row group starts
 *      ... trigram third ids               trigramCount x u16
 * ```
 *
 * Every value array is quantized to 8 bits against a 256-entry float codebook that is
 * uniform over the observed log range (a bounded-error quantizer: every code's error is
 * at most half a step). The book always contains 0 exactly, which the zero backoff
 * weights rely on to round-trip without a spurious offset.
 * Bigram entries are sorted by (first, second); trigram entries by (first, second, third).
 * Invariant: every trigram's (w1, w2) exists as a bigram row - true for any count-based
 * estimator - so trigram groups are addressed by bigram row index.
 */
object NgramModelWriter {
    private const val MAGIC = "WLM1"
    private const val VERSION = 1
    private const val CODEBOOK_ENTRIES = 256
    private const val CODEBOOK_BYTES = CODEBOOK_ENTRIES * 4

    fun write(tables: NgramTables, out: OutputStream) {
        val vocab = tables.vocabulary
        val v = vocab.size
        val b = tables.bigramCount
        val t = tables.trigramCount
        require(v <= 0xFFFF) { "vocabulary too large for u16 ids: $v" }

        val vocabBytes = vocab.sumOf { 2 + it.toByteArray(Charsets.UTF_8).size }
        val total = 17 + vocabBytes + 5 * CODEBOOK_BYTES + 2 * v + 2 * b + t +
            4 * (v + 1) + 2 * b + 4 * (b + 1) + 2 * t

        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC.toByteArray(Charsets.US_ASCII))
        buf.put(VERSION.toByte())
        buf.putInt(v)
        buf.putInt(b)
        buf.putInt(t)
        for (word in vocab) {
            val bytes = word.toByteArray(Charsets.UTF_8)
            buf.putShort(bytes.size.toShort())
            buf.put(bytes)
        }

        val uniBook = buildCodebook(tables.unigramLogProb)
        val uniBoBook = buildCodebook(tables.unigramBackoff)
        val biBook = buildCodebook(tables.bigramLogProb)
        val biBoBook = buildCodebook(tables.bigramBackoff)
        val triBook = buildCodebook(tables.trigramLogProb)

        uniBook.forEach { buf.putFloat(it) }
        uniBoBook.forEach { buf.putFloat(it) }
        biBook.forEach { buf.putFloat(it) }
        biBoBook.forEach { buf.putFloat(it) }
        triBook.forEach { buf.putFloat(it) }
        buf.put(encode(tables.unigramLogProb, uniBook))
        buf.put(encode(tables.unigramBackoff, uniBoBook))
        buf.put(encode(tables.bigramLogProb, biBook))
        buf.put(encode(tables.bigramBackoff, biBoBook))
        buf.put(encode(tables.trigramLogProb, triBook))

        // Bigram groups by first word: offsets are start indices into the entry arrays.
        val biOff = IntArray(v + 1)
        var idx = 0
        for (f in 0 until v) {
            while (idx < b && tables.bigramFirst[idx] < f) idx++
            biOff[f] = idx
        }
        biOff[v] = b
        biOff.forEach { buf.putInt(it) }
        for (i in 0 until b) buf.putShort(tables.bigramSecond[i].toShort())

        // Trigram groups by bigram row: one offset per bigram row, empty rows share the
        // next non-empty start so group r is [triOff[r], triOff[r+1]).
        val rowStart = IntArray(b) { -1 }
        var ti = 0
        while (ti < t) {
            val w1 = tables.trigramFirst[ti]
            val w2 = tables.trigramSecond[ti]
            val row = bigramRow(tables, w1, w2)
            require(row >= 0) { "trigram ($w1,$w2,...) has no bigram row" }
            rowStart[row] = ti
            while (ti < t && tables.trigramFirst[ti] == w1 && tables.trigramSecond[ti] == w2) ti++
        }
        val triOff = IntArray(b + 1)
        var cur = t
        for (r in b - 1 downTo 0) {
            if (rowStart[r] >= 0) cur = rowStart[r]
            triOff[r] = cur
        }
        triOff[b] = t
        triOff.forEach { buf.putInt(it) }
        for (i in 0 until t) buf.putShort(tables.trigramThird[i].toShort())

        out.write(buf.array(), 0, buf.position())
    }

    /**
     * A bounded-error 256-entry codebook over [values].
     *
     * Log probabilities already live on a natural (nat) axis, so a uniform grid over the
     * observed range gives every code at most half a step of error. The previous
     * quantile book instead spread its entries evenly over the *count* of values: the
     * dense long tail absorbed almost every code and the two top codes ended up ~8 nats
     * apart, so common words decoded as 0 or as the tail value.
     *
     * Exactly 0 is always present, and is the top code because every stored value is
     * <= 0 (probabilities are < 1 and backoff weights are <= 1). That makes a zero
     * backoff round-trip exactly, which the missing-row rules depend on.
     */
    private fun buildCodebook(values: FloatArray): FloatArray {
        var lo = 0f
        var hi = 0f
        for (v in values) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val book = FloatArray(CODEBOOK_ENTRIES)
        if (hi <= lo) return book // every value is 0 (or the array is empty)
        val step = (hi - lo) / (CODEBOOK_ENTRIES - 1)
        for (i in book.indices) book[i] = lo + i * step
        book[0] = lo
        book[book.lastIndex] = hi
        if (lo <= 0f && 0f <= hi) {
            var zero = 0
            while (zero < book.lastIndex && book[zero] < 0f) zero++
            book[zero] = 0f
        }
        return book
    }

    private fun encode(values: FloatArray, book: FloatArray): ByteArray {
        val out = ByteArray(values.size)
        for (i in values.indices) out[i] = nearestCode(book, values[i]).toByte()
        return out
    }

    private fun nearestCode(book: FloatArray, v: Float): Int {
        var lo = 0
        var hi = book.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (book[mid] < v) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(book[lo - 1] - v) <= abs(book[lo] - v)) return lo - 1
        return lo
    }

    private fun bigramRow(tables: NgramTables, w1: Int, w2: Int): Int {
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
}
