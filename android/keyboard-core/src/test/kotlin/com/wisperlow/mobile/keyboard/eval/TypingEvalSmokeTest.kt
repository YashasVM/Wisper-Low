package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs a few sentences end to end against the baseline engine: the simulator, the
 * seeded typist and the metrics on real data, in a few seconds. Reads the word lists
 * from the app assets, like the other engine tests.
 */
class TypingEvalSmokeTest {
    private val assets = File(System.getProperty("wisperlow.assets")!!)
    private val data = File("src/eval/resources/en")

    @Test
    fun `runs five sentences through the strip user`() {
        val sentences = EvalData.sentences(data).take(5)
        assertEquals(5, sentences.size)
        val latencies = ArrayList<Long>()
        var picks = 0
        var correct = 0
        var words = 0
        val engine = BaselineEngine(assets)
        for ((i, sentence) in sentences.withIndex()) {
            val intended = EvalData.tokens(sentence)
            engine.reset()
            val plan = Typist(TypistPreset.NORMAL)
                .plan(intended, EvalData.misspellings(data).associate { (w, r) -> Lexicon.key(r) to w }, seed = 7L + i)
            val run = Simulator.run(engine, plan, intended, stripUser = true, latencies = latencies)
            assertTrue("field empty after \"$sentence\"", engine.text().isNotBlank())
            picks += run.picks
            correct += run.intendedCorrect
            words += run.intendedWords
        }
        assertTrue("strip user made no picks in 5 sentences", picks > 0)
        assertTrue("accuracy too low: $correct of $words", correct >= words - 1)
        assertTrue("latency was never measured", latencies.isNotEmpty())
    }

    @Test
    fun `exact preset is a true zero-noise control`() {
        // EXACT has sigma 0 and no mistakes: every tap lands on its key's centre, so the
        // raw typed literal is the intended sentence and nothing is a touch miss. PRECISE,
        // by contrast, still draws Gaussian noise at sigma 0.15.
        assertEquals(0f, TypistPreset.EXACT.sigma, 0f)
        assertTrue(TypistPreset.PRECISE.sigma > 0f)

        val sentence = EvalData.sentences(data).first()
        val intended = EvalData.tokens(sentence)
        val engine = BaselineEngine(assets)
        val plan = Typist(TypistPreset.EXACT)
            .plan(intended, EvalData.misspellings(data).associate { (w, r) -> Lexicon.key(r) to w }, seed = 1L)
        val run = Simulator.run(engine, plan, intended, stripUser = false, latencies = null)
        assertEquals("zero-noise typing should have no touch mislandings", 0, run.touchMissWords)
        assertEquals("zero-noise typing should not be corrupted", 0, run.cleanFalseCorrections)
        assertEquals("every word typed exactly should survive", intended.count { it.hasWord }, run.intendedCorrect)
    }

    @Test
    fun `spelling corpus types exactly and reports`() {
        val stats = SpellingStats()
        val pairs = EvalData.misspellings(data)
        assertTrue("misspelling pairs missing from $data", pairs.isNotEmpty())
        val fix = BaselineEngine(assets)
        for ((wrong, right) in pairs.take(5)) {
            fix.reset()
            wrong.forEach { fix.type(it.toString()) }
            fix.space()
            stats.pairs++
            if (lastWord(fix) == right.lowercase()) stats.fixed++
        }
        assertTrue("none of the misspellings were fixed: $stats", stats.fixed > 0)
    }

    private fun lastWord(engine: BaselineEngine): String =
        engine.text().trim().split(Regex("\\s+")).lastOrNull()?.let { normExact(it) } ?: ""
}
