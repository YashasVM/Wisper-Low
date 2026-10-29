package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The simulator follows the production path: preserve literal taps until word commit. */
class SmartEngineTest {
    private val assets = File(System.getProperty("wisperlow.assets")!!)
    private val data = File("src/eval/resources/en")

    /** The tap that hit-tests to [landed] but sits [fraction] of a key width from its centre. */
    private fun edgeTap(landed: Char, fraction: Float): Pair<Float, Float> {
        val key = EvalBoard.byChar[landed]!!
        return key.centerX + fraction * EvalBoard.units.keyWidth to key.centerY
    }

    @Test
    fun `an off-centre tap stays literal until the word is committed`() {
        // "th" is a strong prefix for 'e'. A tap near w's right edge hit-tests to w, but the
        // word decoder can use the touch when space commits, while composing keeps the literal.
        val (x, y) = edgeTap('w', 0.48f)
        assertEquals("the tap must land on w", 'w', EvalBoard.hit(x, y)?.text?.single())

        val baseline = BaselineEngine(assets)
        baseline.type("t")
        baseline.type("h")
        baseline.tap(x, y)
        assertEquals("the legacy engine types the key it was given", "thw", baseline.composing.lowercase())

        val smart = SmartEngine.fromWordList(assets)
        smart.type("t")
        smart.type("h")
        smart.tap(x, y)
        assertEquals("the smart engine preserves the literal key for undo", "thw", smart.composing.lowercase())
        smart.space()
        assertEquals("the touch still informs the committed correction", "the", lastWord(smart))
    }

    @Test
    fun `composing, revertible and strip timing come through the interface`() {
        val engine: EngineUnderTest = SmartEngine.fromWordList(assets)
        // Type a slip the engine knows how to fix, so a revert slot is on offer.
        "teh".forEach { engine.type(it.toString()) }
        assertEquals("teh", engine.composing.lowercase())
        assertTrue("the strip should offer something for \"teh\"", engine.strip().isNotEmpty())
        assertTrue("strip timing must be measured", engine.lastStripNanos > 0L)
        engine.space()
        assertNotEquals("the engine should have corrected the slip", "teh", lastWord(engine))
    }

    @Test
    fun `the smart engine drives the simulator over a real sentence`() {
        val sentence = EvalData.sentences(data).first()
        val intended = EvalData.tokens(sentence)
        val plan = Typist(TypistPreset.EXACT)
            .plan(intended, EvalData.misspellings(data).associate { (w, r) -> Lexicon.key(r) to w }, seed = 3L)
        val engine = SmartEngine.fromWordList(assets)
        val run = Simulator.run(engine, plan, intended, stripUser = false, latencies = null)
        assertEquals("zero-noise typing has no touch mislandings", 0, run.touchMissWords)
        assertEquals(intended.count { it.hasWord }, run.intendedCorrect)
    }

    private fun lastWord(engine: EngineUnderTest): String =
        engine.text().trim().split(Regex("\\s+")).lastOrNull()?.let { it.lowercase() } ?: ""
}
