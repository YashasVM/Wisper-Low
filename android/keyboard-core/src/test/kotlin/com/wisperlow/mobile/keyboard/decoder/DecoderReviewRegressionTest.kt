package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class DecoderReviewRegressionTest {
    private val words = listOf("hello" to 60_000L, "don't" to 150_000L, "dont" to 400L, "the" to 1_000_000L)
    private val vocabulary = Vocabulary(listOf("<s>") + words.map { it.first })
    private val model = UnigramModel(vocabulary, longArrayOf(1L) + words.map { it.second }.toLongArray())
    private val index = SimpleWordIndex(model)
    private val spatial = object : com.wisperlow.mobile.keyboard.spatial.SpatialModel {
        override fun logLikelihood(touch: com.wisperlow.mobile.keyboard.Touch, char: Char): Float = 0f
        override fun candidates(touch: com.wisperlow.mobile.keyboard.Touch, max: Int) =
            emptyList<com.wisperlow.mobile.keyboard.spatial.KeyChance>()
    }
    private val decoder = BeamTypingDecoder(index, model, spatial)
    private fun typed(word: String): List<TypedKey> = word.map { TypedKey(it, null) }

    @Test
    fun `empty input yields no candidates`() {
        assertEquals(emptyList<Candidate>(), decoder.decode(emptyList(), intArrayOf(), 5))
    }

    @Test
    fun `non-positive max yields no candidates`() {
        assertEquals(emptyList<Candidate>(), decoder.decode(typed("the"), intArrayOf(), 0))
        assertEquals(emptyList<Candidate>(), decoder.decode(typed("the"), intArrayOf(), -3))
    }

    @Test
    fun `mixed case and apostrophe input keeps literal and resolves don't`() {
        val result = decoder.decode(typed("Dont"), intArrayOf(), 3)
        assertTrue(result.any { it.word == "Dont" && it.kind == CandidateKind.LITERAL })
        assertEquals("don't", result.first().word)
    }

    @Test
    fun `trie next char model is safe under concurrent access`() {
        val next = TrieNextCharModel(index, model)
        val error = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val threads = (0..3).map { i ->
            Thread {
                start.await()
                repeat(50) {
                    try {
                        next.logProbs(if (i % 2 == 0) "th" else "", intArrayOf())
                    } catch (t: Throwable) {
                        error.compareAndSet(null, t)
                    }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(error.get()?.toString() ?: "ok", error.get() == null)
    }
}
