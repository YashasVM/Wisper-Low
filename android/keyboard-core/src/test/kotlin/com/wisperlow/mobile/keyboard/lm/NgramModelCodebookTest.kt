package com.wisperlow.mobile.keyboard.lm

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for the WLM1 codebook on a realistic, heavily skewed vocabulary: a
 * handful of frequent words sitting above a long tail of ~60k rare ones. The old
 * quantile codebook spread its 256 entries evenly over the *count* of values, so almost
 * every entry landed in the dense tail and the top two entries were many nats apart;
 * common words then decoded to 0 or to the tail value (the compiler measured a 3.75-nat
 * error on 'and'). These tests pin down the bounded-error replacement.
 *
 * Also covers parser robustness: a truncated or corrupt file must fail with a clear
 * [IllegalArgumentException], not a raw [java.nio.BufferUnderflowException] or an
 * arithmetic overflow that silently accepts the header.
 */
class NgramModelCodebookTest {

    @Test
    fun `skewed codebook keeps every common-word unigram logprob within a small error`() {
        val model = skewedModel
        val ref = TablesModel(skewedTables)
        for (id in 1..COMMON_WORDS) {
            val expected = ref.unigramLogProb(id)
            val actual = model.unigramLogProb(id)
            assertTrue(
                "common word w$id: decoded $actual, expected $expected",
                abs(actual - expected) <= MAX_ERROR_NATS,
            )
        }
    }

    @Test
    fun `common words stay well above the rare tail after quantization`() {
        val model = skewedModel
        val ref = TablesModel(skewedTables)
        // The fixture separates the two bands by well over 5 nats.
        assertTrue("fixture gap", ref.unigramLogProb(1) - ref.unigramLogProb(RARE_PROBE) > 5f)
        assertTrue(
            "common w1 (${model.unigramLogProb(1)}) must stay above rare w$RARE_PROBE (${model.unigramLogProb(RARE_PROBE)})",
            model.unigramLogProb(1) - model.unigramLogProb(RARE_PROBE) > 5f,
        )
    }

    @Test
    fun `rare words and extremes survive within the bounded error`() {
        val model = skewedModel
        val ref = TablesModel(skewedTables)
        val probes = listOf(0, COMMON_WORDS + 1, RARE_PROBE, skewedTables.vocabulary.size - 1)
        for (id in probes) {
            val expected = ref.unigramLogProb(id)
            val actual = model.unigramLogProb(id)
            assertTrue("unigram w$id: decoded $actual, expected $expected", abs(actual - expected) <= MAX_ERROR_NATS)
        }
    }

    @Test
    fun `listed and backed-off logprobs round-trip within the bounded error`() {
        val model = skewedModel
        val ref = TablesModel(skewedTables)
        val rng = Random(5)
        val contexts = mutableListOf(
            intArrayOf(),
            intArrayOf(1),
            intArrayOf(1, 21),
            intArrayOf(2, 3),
            intArrayOf(-1, 1),
            intArrayOf(Vocabulary.UNKNOWN, Vocabulary.UNKNOWN),
        )
        repeat(24) { contexts.add(intArrayOf(1 + rng.nextInt(COMMON_WORDS), 21 + rng.nextInt(skewedTables.vocabulary.size - 21))) }
        var checked = 0
        var maxError = 0f
        for (ctx in contexts) {
            val words = listOf(1, 2, 21, RARE_PROBE, -1) + List(12) { rng.nextInt(skewedTables.vocabulary.size) }
            for (w in words) {
                val expected = ref.logProb(w, ctx)
                val actual = model.logProb(w, ctx)
                if (!expected.isFinite() || !actual.isFinite()) continue
                maxError = maxOf(maxError, abs(actual - expected))
                checked++
            }
        }
        assertTrue("should check many pairs", checked > 200)
        assertTrue("max |delta logprob| $maxError nats", maxError <= MAX_ERROR_NATS)
    }

    @Test
    fun `a stored zero backoff decodes to exactly zero`() {
        // Every unigram shares one logprob the codebook can represent exactly, and no
        // bigram is listed, so a two-word context reaches the unigram-backoff branch and
        // any residual error is the decoded backoff weight itself (0).
        val v = 8
        val surfaces = (0 until v).map { if (it == 0) Vocabulary.SENTENCE_START else "w$it" }
        val tables = NgramTables(
            vocabulary = surfaces,
            unigramLogProb = FloatArray(v) { -5f },
            unigramBackoff = FloatArray(v) { 0f },
            bigramFirst = IntArray(0),
            bigramSecond = IntArray(0),
            bigramLogProb = FloatArray(0),
            bigramBackoff = FloatArray(0),
            trigramFirst = IntArray(0),
            trigramSecond = IntArray(0),
            trigramThird = IntArray(0),
            trigramLogProb = FloatArray(0),
        )
        val model = load(tables)
        for (w in 1 until v) assertEquals("unigram w$w", -5f, model.unigramLogProb(w), 1e-6f)
        for (ctx in 1 until v) {
            for (w in 1 until v) {
                if (w == ctx) continue
                assertEquals("backoff w$w | w$ctx", -5f, model.logProb(w, intArrayOf(ctx)), 1e-6f)
            }
        }
    }

    @Test
    fun `truncated model throws a clear exception`() {
        val full = serialize(skewedTables)
        val cuts = listOf(0, 4, 16, 17, 18, 25, full.size / 2, full.size - 1)
        for (cut in cuts) {
            try {
                NgramModel.load(ByteBuffer.wrap(full, 0, cut))
                fail("expected IllegalArgumentException for a $cut-byte prefix")
            } catch (e: IllegalArgumentException) {
                assertTrue("cut $cut should carry a message", !e.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun `corrupt header counts throw a clear exception instead of underflowing or wrapping`() {
        val full = serialize(skewedTables)
        // A vocabulary larger than the u16 id limit (would read past the vocabulary into
        // the probability sections and underflow).
        assertCorruptHeader(full, offset = 5, value = 100_000)
        // Enormous bigram and trigram counts: the old Int arithmetic wrapped and load()
        // returned a model that only blew up later, on the first lookup.
        assertCorruptHeader(full, offset = 9, value = Int.MAX_VALUE)
        assertCorruptHeader(full, offset = 13, value = Int.MAX_VALUE)
    }

    private fun assertCorruptHeader(full: ByteArray, offset: Int, value: Int) {
        val copy = full.copyOf()
        copy[offset] = (value and 0xFF).toByte()
        copy[offset + 1] = ((value shr 8) and 0xFF).toByte()
        copy[offset + 2] = ((value shr 16) and 0xFF).toByte()
        copy[offset + 3] = ((value shr 24) and 0xFF).toByte()
        try {
            NgramModel.load(ByteBuffer.wrap(copy))
            fail("expected IllegalArgumentException for a corrupt header at offset $offset = $value")
        } catch (e: IllegalArgumentException) {
            assertTrue("offset $offset should carry a message", !e.message.isNullOrBlank())
        }
    }

    private fun load(tables: NgramTables): NgramModel = NgramModel.load(ByteBuffer.wrap(serialize(tables)))

    private fun serialize(tables: NgramTables): ByteArray =
        ByteArrayOutputStream().use { NgramModelWriter.write(tables, it); it.toByteArray() }

    private companion object {
        /** Half of the widest single codebook step we accept; see [MAX_ERROR_NATS]. */
        const val MAX_ERROR_NATS = 0.1f

        const val COMMON_WORDS = 20
        const val RARE_PROBE = 60_000

        val skewedTables: NgramTables = buildSkewedTables()
        val skewedModel: NgramModel by lazy { NgramModel.load(ByteBuffer.wrap(ByteArrayOutputStream().use { NgramModelWriter.write(skewedTables, it); it.toByteArray() })) }

        /**
         * ~60k words: sentence start pinned to negligible mass, [COMMON_WORDS] frequent
         * words just above a dense rare tail. A few hundred bigrams/trigrams give the
         * common words successors so prediction and backoff are exercised too.
         */
        fun buildSkewedTables(): NgramTables {
            val v = RARE_PROBE + 1
            val surfaces = ArrayList<String>(v)
            surfaces.add(Vocabulary.SENTENCE_START)
            for (i in 1 until v) surfaces.add("w$i")

            val unigramLogProb = FloatArray(v) { id ->
                when {
                    id == 0 -> -30f
                    id <= COMMON_WORDS -> -(3.0f + (id - 1) * 0.07f)
                    else -> -14f - (id % 7) * 0.5f
                }
            }
            val unigramBackoff = FloatArray(v) { id -> if (id in 1..COMMON_WORDS) -0.7f else 0f }

            val rng = Random(11)
            val biFirst = ArrayList<Int>()
            val biSecond = ArrayList<Int>()
            val biProb = ArrayList<Float>()
            val biBo = ArrayList<Float>()
            for (ctx in 1..COMMON_WORDS) {
                val successors = LinkedHashSet<Int>()
                successors.add(1 + (ctx % COMMON_WORDS))
                while (successors.size < 25) successors.add(COMMON_WORDS + 1 + rng.nextInt(v - COMMON_WORDS - 1))
                for (s in successors) {
                    biFirst.add(ctx)
                    biSecond.add(s)
                    biProb.add(-(0.5f + rng.nextFloat() * 5.5f))
                    biBo.add(if (rng.nextInt(3) == 0) 0f else -(0.4f + rng.nextFloat() * 1.6f))
                }
            }
            val biOrder = biFirst.indices.sortedWith(compareBy({ biFirst[it] }, { biSecond[it] }))

            val triFirst = ArrayList<Int>()
            val triSecond = ArrayList<Int>()
            val triThird = ArrayList<Int>()
            val triProb = ArrayList<Float>()
            for (row in biOrder) {
                if (row % 3 != 0) continue
                val thirds = LinkedHashSet<Int>()
                thirds.add(1 + (row % COMMON_WORDS))
                while (thirds.size < 3) thirds.add(COMMON_WORDS + 1 + rng.nextInt(v - COMMON_WORDS - 1))
                for (th in thirds) {
                    triFirst.add(biFirst[row])
                    triSecond.add(biSecond[row])
                    triThird.add(th)
                    triProb.add(-(0.3f + rng.nextFloat() * 4.5f))
                }
            }
            val triOrder = triFirst.indices.sortedWith(compareBy({ triFirst[it] }, { triSecond[it] }, { triThird[it] }))

            return NgramTables(
                vocabulary = surfaces,
                unigramLogProb = unigramLogProb,
                unigramBackoff = unigramBackoff,
                bigramFirst = biOrder.map { biFirst[it] }.toIntArray(),
                bigramSecond = biOrder.map { biSecond[it] }.toIntArray(),
                bigramLogProb = biOrder.map { biProb[it] }.toFloatArray(),
                bigramBackoff = biOrder.map { biBo[it] }.toFloatArray(),
                trigramFirst = triOrder.map { triFirst[it] }.toIntArray(),
                trigramSecond = triOrder.map { triSecond[it] }.toIntArray(),
                trigramThird = triOrder.map { triThird[it] }.toIntArray(),
                trigramLogProb = triOrder.map { triProb[it] }.toFloatArray(),
            )
        }
    }
}
