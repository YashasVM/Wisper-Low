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
    fun `no-strip user matches the plain typing path`() {
        val sentence = EvalData.sentences(data).first()
        val intended = EvalData.tokens(sentence)
        val engine = BaselineEngine(assets)
        val plan = Typist(TypistPreset.PRECISE)
            .plan(intended, EvalData.misspellings(data).associate { (w, r) -> Lexicon.key(r) to w }, seed = 1L)
        val run = Simulator.run(engine, plan, intended, stripUser = false, latencies = null)
        // Precise preset, no noise, no strip: the text should survive untouched.
        val out = engine.text().trim()
        assertTrue("precise no-strip run lost the sentence: \"$out\"", run.intendedCorrect >= intended.size - 1)
        assertEquals(0, run.cleanFalseCorrections)
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
