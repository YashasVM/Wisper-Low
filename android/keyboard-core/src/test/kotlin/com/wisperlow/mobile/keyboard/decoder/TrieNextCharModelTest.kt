package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.ScoredWord
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrieNextCharModelTest {
    private val vocabulary =
        Vocabulary(listOf("<s>", "the", "they", "then", "these", "those", "good", "morning", "night"))
    private val unigram = UnigramModel(vocabulary, longArrayOf(1, 1000, 30, 20, 40, 100, 500, 10, 1000))
    private val index = SimpleWordIndex(unigram)
    private val nextChars = TrieNextCharModel(index, unigram)
    private val good = vocabulary.id("good")
    private val morning = vocabulary.id("morning")
    private val night = vocabulary.id("night")

    /** Unigrams for the counting, a script for the contextual view. */
    private fun scripted(script: (IntArray) -> List<ScoredWord>) = TrieNextCharModel(
        index,
        ScriptedModel(unigram, script),
    )

    private fun nodeAt(key: String): Int {
        var node = index.root
        for (c in key) node = index.child(node, c)
        return node
    }

    @Test
    fun `after th the letter of the dominant word wins`() {
        val probs = nextChars.logProbs("th", intArrayOf())
        assertTrue(probs.getValue('e') > probs.getValue('o'))
        assertEquals(setOf('e', 'o'), probs.keys)
    }

    @Test
    fun `context lifts a letter the unigram view ignores`() {
        val morningNext = scripted { listOf(ScoredWord(morning, ln(0.9f)), ScoredWord(night, ln(0.1f))) }
        val nightNext = scripted { listOf(ScoredWord(night, ln(0.9f)), ScoredWord(morning, ln(0.1f))) }

        // After "good" the next word starts over, so only the context says where it goes.
        val withMorning = morningNext.logProbs("", intArrayOf(good))
        val withNight = nightNext.logProbs("", intArrayOf(good))
        val unguided = nextChars.logProbs("", intArrayOf(good))

        assertTrue("'m' should win once 'morning' is predicted", withMorning.getValue('m') > withMorning.getValue('n'))
        assertTrue("'n' should win once 'night' is predicted", withNight.getValue('n') > withNight.getValue('m'))
        assertTrue(withMorning.getValue('m') > withNight.getValue('m'))
        // Unigram alone puts almost nothing on 'm', so the context has a lot to correct.
        assertTrue(exp(unguided.getValue('m')) < 0.05)
    }

    @Test
    fun `probabilities are normalised and letters are the trie's children`() {
        val probs = nextChars.logProbs("th", intArrayOf())
        assertEquals(1.0, probs.values.sumOf { exp(it.toDouble()) }, 1e-4)
        assertEquals(listOf('e', 'o'), index.children(nodeAt("th")).toList())
    }

    @Test
    fun `apostrophes and case in the prefix are ignored`() {
        val plain = nextChars.logProbs("th", intArrayOf())
        assertEquals(plain, nextChars.logProbs("t'h", intArrayOf()))
        assertEquals(plain, nextChars.logProbs("TH", intArrayOf()))
    }

    @Test
    fun `an unknown prefix has no opinion`() {
        assertTrue(nextChars.logProbs("zz", intArrayOf()).isEmpty())
        assertTrue(nextChars.logProbs("qqqq", intArrayOf()).isEmpty())
        // A word nothing longer starts: no letter can follow.
        assertTrue(nextChars.logProbs("these", intArrayOf()).isEmpty())
    }

    @Test
    fun `without a matching prediction the unigram trie view stands alone`() {
        val node = nodeAt("th")
        val expectedE = ln(exp(index.massBelow(index.child(node, 'e')) - index.massBelow(node)))
        val expectedO = ln(exp(index.massBelow(index.child(node, 'o')) - index.massBelow(node)))

        val probs = scripted { listOf(ScoredWord(morning, 0f), ScoredWord(night, 0f)) }
            .logProbs("th", intArrayOf())
        assertEquals(expectedE, probs.getValue('e'), 1e-4f)
        assertEquals(expectedO, probs.getValue('o'), 1e-4f)
    }

    @Test
    fun `results are cached per prefix and context`() {
        val model = scripted { context ->
            if (context.isEmpty()) listOf(ScoredWord(night, 0f)) else listOf(ScoredWord(morning, 0f))
        }
        val first = model.logProbs("", intArrayOf(good))
        assertSame(first, model.logProbs("", intArrayOf(good)))
        assertNotSame(first, model.logProbs("", intArrayOf(night)))
        assertNotSame(first, model.logProbs("th", intArrayOf(good)))
        assertSame(first, model.logProbs("", intArrayOf(good)))
    }

    /** Unigrams from a real model, predictions from the test's script. */
    private class ScriptedModel(
        base: LanguageModel,
        private val script: (IntArray) -> List<ScoredWord>,
    ) : LanguageModel by base {
        override fun predict(context: IntArray, max: Int): List<ScoredWord> = script(context).take(max)
    }
}