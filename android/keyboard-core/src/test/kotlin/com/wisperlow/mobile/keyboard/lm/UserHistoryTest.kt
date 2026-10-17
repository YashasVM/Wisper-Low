package com.wisperlow.mobile.keyboard.lm

import java.io.File
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserHistoryTest {
    private val thirtyDays = 30L * 24 * 60 * 60 * 1000

    private var now = 0L
    private val history = UserHistory { now }

    /** Letter-only labels, since the history ignores words with digits or punctuation. */
    private fun label(i: Int, prefix: String): String {
        var n = i
        val sb = StringBuilder(prefix)
        do {
            sb.append(('a'.code + n % 26).toChar())
            n /= 26
        } while (n > 0)
        return sb.toString()
    }

    private fun tempFile() = File.createTempFile("user-history", ".tsv").apply { deleteOnExit() }

    @Test
    fun `learning raises a word's probability`() {
        val base = UnigramModel(Vocabulary(listOf("hello", "world")), longArrayOf(5, 4))
        val model = InterpolatedModel(base, history)
        val before = model.logProb(1, intArrayOf(0))
        val unigramBefore = model.unigramLogProb(1)
        history.learn("hello", null)
        history.learn("world", "hello")
        history.learn("world", "hello")
        assertTrue(model.logProb(1, intArrayOf(0)) > before)
        assertTrue(model.unigramLogProb(1) > unigramBefore)
    }

    @Test
    fun `a user-only word becomes predictable after its context word`() {
        val base = UnigramModel(Vocabulary(listOf("hello")), longArrayOf(5))
        val model = InterpolatedModel(base, history)
        history.learn("hello", null)
        // Id 1 is where the user's word will be appended once they type it.
        val before = model.logProb(1, intArrayOf(0))
        history.learn("zorp", "hello")
        val after = model.logProb(1, intArrayOf(0))
        assertTrue(after > before)
        assertTrue(after > -5f)
        val next = model.predict(intArrayOf(0), 5).map { model.vocabulary.surface(it.id) }
        assertTrue(next.contains("zorp"))
    }

    @Test
    fun `decay halves counts after thirty days`() {
        repeat(4) { history.learn("hello", null) }
        repeat(4) { history.learn("world", "hello") }
        assertEquals(4.0, history.count("hello"), 0.0)
        assertEquals(4.0, history.pairCount("hello", "world"), 0.0)
        assertTrue(history.isTrusted("hello"))
        now += thirtyDays
        assertEquals(2.0, history.count("hello"), 1e-9)
        assertEquals(2.0, history.pairCount("hello", "world"), 1e-9)
        assertTrue(history.isTrusted("hello"))
        now += thirtyDays
        assertEquals(1.0, history.count("hello"), 1e-9)
        assertFalse(history.isTrusted("hello"))
    }

    @Test
    fun `forget removes the word and its pairs`() {
        history.learn("hello", null)
        history.learn("world", "hello")
        history.learn("world", "hello")
        history.learn("again", "hello")
        history.forget("world")
        assertEquals(0.0, history.count("world"), 0.0)
        assertEquals(0.0, history.pairCount("hello", "world"), 0.0)
        assertFalse(history.words().containsKey("world"))
        assertEquals(1.0, history.pairCount("hello", "again"), 0.0)
        history.forget("hello")
        assertEquals(0.0, history.pairCount("hello", "again"), 0.0)
        assertEquals(0.0, history.evidenceAfter("hello"), 0.0)
    }

    @Test
    fun `write and read round trip`() {
        history.learn("love", null)
        repeat(3) { history.learn("iPhone", "love") }
        val file = tempFile()
        history.write(file)
        val loaded = UserHistory { now }
        loaded.read(file)
        assertEquals(3.0, loaded.count("iPhone"), 0.0)
        assertEquals(1.0, loaded.count("love"), 0.0)
        assertEquals(3.0, loaded.pairCount("love", "iPhone"), 0.0)
        assertEquals(history.words(), loaded.words())
        // The saved update time carries decay across a restart.
        now += thirtyDays
        val later = UserHistory { now }
        later.read(file)
        assertEquals(1.5, later.count("iPhone"), 1e-9)
    }

    @Test
    fun `the word cap evicts the weakest oldest entries`() {
        for (i in 0 until 20_000) history.learn(label(i, "w"), null)
        history.learn("brandnew", null)
        val snapshot = history.words()
        assertTrue(snapshot.size <= 20_000)
        assertFalse(snapshot.containsKey(label(0, "w")))
        assertEquals(1.0, history.count(label(19_999, "w")), 0.0)
        assertEquals(1.0, history.count("brandnew"), 0.0)
    }

    @Test
    fun `the pair cap evicts the weakest oldest pairs`() {
        for (i in 0 until 100) history.learn(label(i, "ctx"), null)
        for (i in 0 until 100) for (j in 0 until 500) history.learn(label(j, "nxt"), label(i, "ctx"))
        history.learn("extra", label(0, "ctx"))
        assertEquals(0.0, history.pairCount(label(0, "ctx"), label(0, "nxt")), 0.0)
        assertEquals(1.0, history.pairCount(label(0, "ctx"), "extra"), 0.0)
        assertEquals(1.0, history.pairCount(label(99, "ctx"), label(499, "nxt")), 0.0)
    }

    @Test
    fun `logProb over the vocabulary sums to about one`() {
        val surfaces = listOf(Vocabulary.SENTENCE_START, "cat", "dog", "fish")
        val model = InterpolatedModel(UnigramModel(Vocabulary(surfaces), longArrayOf(2, 6, 3, 1)), history)
        // "fish" the user never wrote after: the base alone decides.
        assertEquals(1.0, sumOver(model, intArrayOf(2)), 1e-3)
        history.learn("cat", null)
        repeat(3) { history.learn("dog", "cat") }
        // "cat" the user knows: both estimates are proper, so the blend is too.
        assertEquals(1.0, sumOver(model, intArrayOf(1)), 1e-3)
    }

    @Test
    fun `vocabulary keeps base ids, appends the user's words, and rebuilds only for new words`() {
        val base = UnigramModel(Vocabulary(listOf("hello")), longArrayOf(10))
        val model = InterpolatedModel(base, history)
        assertEquals("hello", model.vocabulary.surface(0))
        assertEquals(Vocabulary.UNKNOWN, model.vocabulary.id("zorp"))
        history.learn("hello", null)
        val built = model.vocabulary
        history.learn("hello", null)
        assertSame(built, model.vocabulary)
        history.learn("zorp", "hello")
        val rebuilt = model.vocabulary
        assertNotSame(built, rebuilt)
        assertEquals(1, rebuilt.id("zorp"))
        assertEquals("hello", rebuilt.surface(0))
    }

    private fun sumOver(model: LanguageModel, context: IntArray): Double =
        (0 until model.vocabulary.size).sumOf { exp(model.logProb(it, context).toDouble()) }
}
