package com.wisperlow.mobile.keyboard.lm

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NgramModelTest {
    private val vocab: List<String>
    private val tables: NgramTables
    private val ref: TablesModel

    init {
        val built = buildTables()
        vocab = built.first
        tables = built.second
        ref = TablesModel(tables)
    }

    private fun loadModel(): NgramModel {
        val bytes = ByteArrayOutputStream().use { NgramModelWriter.write(tables, it); it.toByteArray() }
        return NgramModel.load(ByteBuffer.wrap(bytes))
    }

    @Test
    fun `round-trip logProb matches TablesModel within 0_05 nats`() {
        val model = loadModel()
        val random = Random(7)
        val contexts = mutableListOf(
            intArrayOf(),
            intArrayOf(0),
            intArrayOf(5),
            intArrayOf(0, 5),
            intArrayOf(5, 10),
            intArrayOf(5, 10, 15),
            intArrayOf(-1, 5),
            intArrayOf(5, -1),
            intArrayOf(-1, -1),
        )
        repeat(60) {
            val len = random.nextInt(4)
            contexts += IntArray(len) { if (random.nextBoolean()) -1 else random.nextInt(vocab.size) }
        }
        var checked = 0
        for (ctx in contexts) {
            val words = (0 until 30).map { random.nextInt(vocab.size) } + listOf(-1, 0, 1)
            for (w in words) {
                val expected = ref.logProb(w, ctx)
                val actual = model.logProb(w, ctx)
                assertTrue(
                    "logProb($w, ${ctx.toList()}) = $actual, expected $expected",
                    abs(actual - expected) <= 0.05f,
                )
                checked++
            }
        }
        assertTrue("should check many pairs", checked > 500)
    }

    @Test
    fun `predict matches TablesModel top-k`() {
        val model = loadModel()
        val contexts = listOf(
            intArrayOf(),
            intArrayOf(3),
            intArrayOf(0, 3),
            intArrayOf(3, 7),
            intArrayOf(3, 7, 11),
            intArrayOf(-1, 3),
            intArrayOf(3, -1),
        )
        for (ctx in contexts) {
            assertPredictMatches(model, ctx, 5)
            assertPredictMatches(model, ctx, 10)
        }
    }

    @Test
    fun `corrupt magic throws a clear exception`() {
        val bytes = ByteArrayOutputStream().use { NgramModelWriter.write(tables, it); it.toByteArray() }
        bytes[0] = 'X'.code.toByte()
        try {
            NgramModel.load(ByteBuffer.wrap(bytes))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message should mention magic: ${e.message}", e.message!!.contains("magic"))
        }
    }

    @Test
    fun `read memory-mapped file matches in-memory load`() {
        val file = File.createTempFile("wlm", ".bin")
        file.deleteOnExit()
        file.outputStream().use { NgramModelWriter.write(tables, it) }
        val fromFile = NgramModel.read(file)
        val fromMem = loadModel()
        for (ctx in listOf(intArrayOf(3, 7), intArrayOf(), intArrayOf(-1, 3))) {
            for (w in listOf(1, 5, 100)) {
                assertEquals(ref.logProb(w, ctx), fromFile.logProb(w, ctx), 0.05f)
                assertEquals(ref.logProb(w, ctx), fromMem.logProb(w, ctx), 0.05f)
            }
        }
    }

    @Test
    fun `load works on a positioned buffer with a prefix`() {
        val bytes = ByteArrayOutputStream().use { NgramModelWriter.write(tables, it); it.toByteArray() }
        val prefix = ByteArray(7) { 0x7F }
        val combined = prefix + bytes
        val buf = ByteBuffer.wrap(combined)
        buf.position(prefix.size)
        val model = NgramModel.load(buf)
        for (ctx in listOf(intArrayOf(3, 7), intArrayOf(), intArrayOf(-1, 3))) {
            for (w in listOf(1, 5, 100)) {
                assertEquals(ref.logProb(w, ctx), model.logProb(w, ctx), 0.05f)
            }
        }
    }

    @Test
    fun `unknown ids and empty context work`() {
        val model = loadModel()
        assertEquals(UnigramModel.UNKNOWN_LOG_PROB, model.logProb(-1, intArrayOf()), 0f)
        assertEquals(ref.logProb(-1, intArrayOf(-1, -1)), model.logProb(-1, intArrayOf(-1, -1)), 0.05f)
        val p = model.predict(intArrayOf(-1, -1), 3)
        assertEquals(3, p.size)
        assertTrue("never sentence start", p.all { it.id != 0 })
    }

    @Test
    fun `prints bytes per n-gram`() {
        val bytes = ByteArrayOutputStream().use { NgramModelWriter.write(tables, it); it.size() }
        val ngrams = tables.bigramCount + tables.trigramCount
        println(
            "WLM1: $bytes bytes, ${tables.bigramCount} bigrams, ${tables.trigramCount} trigrams -> " +
                "%.2f bytes/n-gram".format(bytes.toFloat() / ngrams),
        )
    }

    private fun assertPredictMatches(model: NgramModel, context: IntArray, k: Int) {
        val expected = ref.predict(context, k)
        val actual = model.predict(context, k)
        assertEquals("predict(${context.toList()}, $k) size", expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            if (abs(e.logProb - a.logProb) > 0.05f) {
                assertEquals("predict(${context.toList()}, $k) rank $i word", e.id, a.id)
            }
            assertTrue(
                "predict(${context.toList()}, $k) rank $i score $a vs $e",
                abs(e.logProb - a.logProb) <= 0.05f + 1e-4f,
            )
        }
        // No excluded word may beat the last included one beyond quantization error.
        val last = actual.last().logProb
        for (w in 1 until vocab.size) {
            if (actual.none { it.id == w }) {
                assertTrue(
                    "predict(${context.toList()}, $k) missed candidate $w",
                    ref.logProb(w, context) <= last + 0.05f,
                )
            }
        }
    }

    private companion object {
        /** A few hundred words, a few thousand bigrams/trigrams with sensible probabilities. */
        fun buildTables(seed: Int = 42): Pair<List<String>, NgramTables> {
            val random = Random(seed)
            val surfaces = ArrayList<String>()
            surfaces.add(Vocabulary.SENTENCE_START)
            for (i in 0 until 200) surfaces.add("w$i")
            val v = surfaces.size

            val counts = LongArray(v) { 1L + random.nextInt(1000) }
            counts[0] = 5000L
            val total = counts.sum().toDouble()
            val uniProb = FloatArray(v) { kotlin.math.ln(counts[it] / total).toFloat() }

            val firsts = (0 until v).shuffled(random).take(150)
            val biFirst = ArrayList<Int>()
            val biSecond = ArrayList<Int>()
            val biProb = ArrayList<Float>()
            val biBo = ArrayList<Float>()
            for (f in firsts) {
                val seconds = (0 until v).shuffled(random).take(5 + random.nextInt(15))
                for (s in seconds) {
                    biFirst.add(f)
                    biSecond.add(s)
                    biProb.add(-(0.3 + random.nextDouble() * 4.7).toFloat())
                    biBo.add(if (random.nextBoolean()) 0f else -(random.nextDouble() * 2.5).toFloat())
                }
            }
            val biOrder = biFirst.indices.sortedWith(compareBy({ biFirst[it] }, { biSecond[it] }))

            val triFirst = ArrayList<Int>()
            val triSecond = ArrayList<Int>()
            val triThird = ArrayList<Int>()
            val triProb = ArrayList<Float>()
            for (row in biOrder) {
                if (random.nextDouble() > 0.7) continue
                val thirds = (0 until v).shuffled(random).take(1 + random.nextInt(3))
                for (t in thirds) {
                    triFirst.add(biFirst[row])
                    triSecond.add(biSecond[row])
                    triThird.add(t)
                    triProb.add(-(0.2 + random.nextDouble() * 3.8).toFloat())
                }
            }
            val triOrder = triFirst.indices.sortedWith(
                compareBy({ triFirst[it] }, { triSecond[it] }, { triThird[it] }),
            )

            val uniBo = FloatArray(v)
            for (row in biOrder) uniBo[biFirst[row]] = biBo[row]

            return surfaces to NgramTables(
                vocabulary = surfaces,
                unigramLogProb = uniProb,
                unigramBackoff = uniBo,
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
