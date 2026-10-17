package com.wisperlow.mobile.keyboard.lm

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * A [LanguageModel] backed by the compact binary format written by [NgramModelWriter].
 * Reads straight from the buffer (a memory-mapped file or an Android asset buffer)
 * without copying the n-gram arrays into the heap: group lookups binary-search the
 * buffer in place via absolute gets. The buffer must stay valid and unmodified for
 * the lifetime of the model.
 */
class NgramModel private constructor(
    override val vocabulary: Vocabulary,
    private val buffer: ByteBuffer,
    private val base: Int,
    private val vocabSize: Int,
    private val bigramCount: Int,
    private val trigramCount: Int,
    afterVocab: Int,
) : LanguageModel {
    private val uniBookOff: Int
    private val uniBoBookOff: Int
    private val biBookOff: Int
    private val biBoBookOff: Int
    private val triBookOff: Int
    private val uniProbOff: Int
    private val uniBoOff: Int
    private val biProbOff: Int
    private val biBoOff: Int
    private val triProbOff: Int
    private val biOffOff: Int
    private val biSecondOff: Int
    private val triOffOff: Int
    private val triThirdOff: Int

    /** Unigram ids best-first, excluding [Vocabulary.SENTENCE_START]; for predict candidates. */
    private val topUnigrams: IntArray

    init {
        uniBookOff = afterVocab
        uniBoBookOff = uniBookOff + CODEBOOK_BYTES
        biBookOff = uniBoBookOff + CODEBOOK_BYTES
        biBoBookOff = biBookOff + CODEBOOK_BYTES
        triBookOff = biBoBookOff + CODEBOOK_BYTES
        uniProbOff = triBookOff + CODEBOOK_BYTES
        uniBoOff = uniProbOff + vocabSize
        biProbOff = uniBoOff + vocabSize
        biBoOff = biProbOff + bigramCount
        triProbOff = biBoOff + bigramCount
        biOffOff = triProbOff + trigramCount
        biSecondOff = biOffOff + 4 * (vocabSize + 1)
        triOffOff = biSecondOff + 2 * bigramCount
        triThirdOff = triOffOff + 4 * (bigramCount + 1)
        topUnigrams = (0 until vocabSize)
            .filter { vocabulary.surface(it) != Vocabulary.SENTENCE_START }
            .sortedByDescending { uniProb(it) }
            .toIntArray()
    }

    override fun unigramLogProb(word: Int): Float =
        if (word in 0 until vocabSize) decode(uniBookOff, codeAt(uniProbOff + word)) else UnigramModel.UNKNOWN_LOG_PROB

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
                    decode(triBookOff, codeAt(triProbOff + ti))
                } else {
                    val row = bigramRow(w1, w2)
                    val bo = if (row >= 0) decode(biBoBookOff, codeAt(biBoOff + row)) else 0f
                    bo + bigram(w2, word)
                }
            }
        }
    }

    override fun predict(context: IntArray, max: Int): List<ScoredWord> {
        if (max <= 0) return emptyList()
        val candidates = HashSet<Int>()
        val n = context.size
        var groupSizes = 0
        if (n >= 1) {
            val w2 = context[n - 1]
            bigramGroup(w2)?.let { (start, end) ->
                groupSizes += end - start
                for (i in start until end) candidates += biSecondId(i)
            }
        }
        if (n >= 2) {
            val row = bigramRow(context[n - 2], context[n - 1])
            if (row >= 0) {
                val start = triGroupStart(row)
                val end = triGroupStart(row + 1)
                groupSizes += end - start
                for (i in start until end) candidates += triThirdId(i)
            }
        }
        // Enough top unigrams that any backed-off word outscoring a grouped one is covered:
        // a non-candidate backed-off word shares one constant offset with the unigrams, so
        // only top-(max + group sizes) unigrams can matter.
        val uniCandidates = minOf(max + groupSizes, topUnigrams.size)
        for (i in 0 until uniCandidates) candidates += topUnigrams[i]
        return candidates
            .filter { it in 0 until vocabSize && vocabulary.surface(it) != Vocabulary.SENTENCE_START }
            .map { ScoredWord(it, logProb(it, context)) }
            .sortedByDescending { it.logProb }
            .take(max)
    }

    private fun uniProb(word: Int): Float = decode(uniBookOff, codeAt(uniProbOff + word))

    private fun bigram(w1: Int, w2: Int): Float {
        val row = bigramRow(w1, w2)
        return if (row >= 0) {
            decode(biBookOff, codeAt(biProbOff + row))
        } else {
            val bo = if (w1 in 0 until vocabSize) decode(uniBoBookOff, codeAt(uniBoOff + w1)) else 0f
            bo + unigramLogProb(w2)
        }
    }

    private fun bigramRow(w1: Int, w2: Int): Int {
        val group = bigramGroup(w1) ?: return -1
        var lo = group.first
        var hi = group.second - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = biSecondId(mid)
            when {
                s < w2 -> lo = mid + 1
                s > w2 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun trigramIndex(w1: Int, w2: Int, w3: Int): Int {
        val row = bigramRow(w1, w2)
        if (row < 0) return -1
        val start = triGroupStart(row)
        val end = triGroupStart(row + 1)
        var lo = start
        var hi = end - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = triThirdId(mid)
            when {
                s < w3 -> lo = mid + 1
                s > w3 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** The bigram entry range for first word [w1], or null when out of range. */
    private fun bigramGroup(w1: Int): Pair<Int, Int>? {
        if (w1 < 0 || w1 >= vocabSize) return null
        return biGroupStart(w1) to biGroupStart(w1 + 1)
    }

    private fun biGroupStart(w1: Int): Int = buffer.getInt(base + biOffOff + 4 * w1)
    private fun triGroupStart(row: Int): Int = buffer.getInt(base + triOffOff + 4 * row)
    private fun biSecondId(i: Int): Int = buffer.getShort(base + biSecondOff + 2 * i).toInt() and 0xFFFF
    private fun triThirdId(i: Int): Int = buffer.getShort(base + triThirdOff + 2 * i).toInt() and 0xFFFF
    private fun codeAt(off: Int): Int = buffer.get(base + off).toInt() and 0xFF
    private fun decode(bookOff: Int, code: Int): Float = buffer.getFloat(base + bookOff + 4 * code)

    companion object {
        private const val CODEBOOK_BYTES = 256 * 4

        /**
         * Loads a model from [buffer], which must be positioned at the start of the
         * format. The buffer's byte order is set to little-endian; all reads are
         * absolute from the entry position, so the position may move afterwards.
         */
        fun load(buffer: ByteBuffer): NgramModel {
            val buf = buffer.order(ByteOrder.LITTLE_ENDIAN)
            val base = buf.position()
            if (buf.remaining() < 17) throw IllegalArgumentException("buffer too small for a WLM1 model")
            val magic = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)
            if (magic != "WLM1") throw IllegalArgumentException("bad magic '$magic': not a WLM1 model file")
            val version = buf.get().toInt() and 0xFF
            if (version != 1) throw IllegalArgumentException("unsupported WLM1 version $version")
            val v = buf.int
            val b = buf.int
            val t = buf.int
            require(v >= 0 && b >= 0 && t >= 0) { "negative counts in header" }
            val surfaces = ArrayList<String>(v)
            repeat(v) {
                val len = buf.short.toInt() and 0xFFFF
                val bytes = ByteArray(len)
                buf.get(bytes)
                surfaces.add(String(bytes, Charsets.UTF_8))
            }
            return NgramModel(Vocabulary(surfaces), buf, base, v, b, t, buf.position() - base)
        }

        /** Memory-maps [file] and loads the model; the file must not change while in use. */
        fun read(file: File): NgramModel {
            FileInputStream(file).use { fis ->
                val channel = fis.channel
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                return load(mapped)
            }
        }
    }
}
