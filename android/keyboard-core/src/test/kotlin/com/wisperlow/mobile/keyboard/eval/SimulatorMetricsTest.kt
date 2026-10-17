package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.KeyKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The metric regression suite: a tap that lands on the wrong key must be a touch miss,
 * never a false autocorrection of a correctly typed word, and a word the engine drops
 * must count as a failure instead of being skipped. The literal expectations here are
 * the whole point, so the numbers are written out, not just checked to be non-zero.
 */
class SimulatorMetricsTest {

    /** A controllable engine: it types the key a tap landed on, and commits a scripted word. */
    private class ScriptedEngine(
        /** Per commit, in order: the committed word, "" to drop the token, null to commit what was typed. */
        private val outputs: List<String?> = emptyList(),
    ) : EngineUnderTest {
        private val field = StringBuilder()
        private val word = StringBuilder()
        private var at = 0

        override var lastStripNanos: Long = 0L
            private set
        override val composing: String get() = word.toString()
        override val revertible: String? get() = null

        override fun reset() {
            field.clear()
            word.clear()
            at = 0
        }

        override fun tap(x: Float, y: Float) {
            EvalBoard.hit(x, y)?.let { if (it.kind == KeyKind.CHAR) word.append(it.text) }
        }

        override fun type(text: String) {
            word.append(text)
        }

        override fun space() {
            val out = outputs.getOrNull(at) ?: word.toString()
            at++
            if (out.isNotEmpty()) field.append(out).append(' ')
            word.clear()
        }

        override fun backspace() {}
        override fun strip(): List<String> = emptyList()
        override fun pick(index: Int) {}
        override fun text(): String = field.toString()
    }

    private fun tapOn(c: Char) = EvalBoard.byChar[c]!!.let { KeyEvent.Tap(it.centerX, it.centerY) }
    private fun taps(word: String) = word.map(::tapOn)
    private fun planned(
        intended: String,
        typed: String = intended,
        events: List<KeyEvent> = taps(intended),
    ) = PlannedToken(intended, typed, noisy = typed != intended, events = events, punct = "", punctEvents = emptyList())
    private fun tokens(vararg words: String) = words.map { Tok(it, "") }

    private fun run(engine: EngineUnderTest, plan: List<PlannedToken>, intended: List<Tok>) =
        Simulator.run(engine, plan, intended, stripUser = false, latencies = null)

    @Test
    fun `a wrong-key touch is a touch miss, not a false autocorrection`() {
        // "cat" intended and typed as "cat", but the third tap is thrown at the x key.
        val plan = listOf(planned("cat", events = listOf(tapOn('c'), tapOn('a'), tapOn('x'))))
        val r = run(ScriptedEngine(), plan, tokens("cat"))
        assertEquals(1, r.intendedWords)
        assertEquals("the mislanded word was not typed correctly", 0, r.cleanWords)
        assertEquals(1, r.noisyWords)
        assertEquals("the runtime mislanding is a touch miss", 1, r.touchMissWords)
        assertEquals("it was not a planned mistake", 0, r.planWrongWords)
        assertEquals("a wrong tap must never count as auto-corruption", 0, r.cleanFalseCorrections)
        assertEquals(0, r.intendedCorrect)
    }

    @Test
    fun `engine corruption of a correctly typed word is a false autocorrection`() {
        // The taps spell "cat" exactly; the engine commits "cut".
        val r = run(ScriptedEngine(listOf("cut")), listOf(planned("cat")), tokens("cat"))
        assertEquals(1, r.cleanWords)
        assertEquals(1, r.cleanFalseCorrections)
        assertEquals(0, r.noisyWords)
        assertEquals(0, r.touchMissWords)
        assertEquals(0, r.intendedCorrect)
    }

    @Test
    fun `a planned slip that the engine fixes is a recovery, not a touch miss`() {
        // The user typed "teh"; the engine committed "the".
        val r = run(ScriptedEngine(listOf("the")), listOf(planned("the", typed = "teh", events = taps("teh"))), tokens("the"))
        assertEquals(0, r.cleanWords)
        assertEquals(1, r.noisyWords)
        assertEquals(1, r.noisyRecovered)
        assertEquals("the taps matched the planned slip, so not a mislanding", 0, r.touchMissWords)
        assertEquals(1, r.planWrongWords)
        assertEquals(0, r.cleanFalseCorrections)
        assertEquals(1, r.intendedCorrect)
    }

    @Test
    fun `a dropped word is counted as a failure and does not shift later words`() {
        // Three words typed, the middle one never reaches the field.
        val plan = listOf(planned("cat"), planned("dog"), planned("fox"))
        val r = run(ScriptedEngine(listOf("cat", "", "fox")), plan, tokens("cat", "dog", "fox"))
        assertEquals(3, r.intendedWords)
        assertEquals("the omitted word must not be silently skipped", 3, r.cleanWords)
        assertEquals("the omission is the engine's failure on a correct word", 1, r.cleanFalseCorrections)
        assertEquals("the word after the omission is still scored in place", 2, r.intendedCorrect)
        assertEquals(0, r.noisyWords)
    }

    @Test
    fun `punctuation after a word is trimmed from the committed token`() {
        val plan = listOf(planned("hello", events = taps("hello") + tapOn('.')))
        val r = run(ScriptedEngine(), plan, tokens("hello"))
        assertEquals(1, r.cleanWords)
        assertEquals(1, r.intendedCorrect)
        assertEquals(0, r.cleanFalseCorrections)
    }

    @Test
    fun `the raw typed-input probe follows the board hit test`() {
        val q = EvalBoard.byChar['q']!!
        assertEquals("q", Simulator.rawChar(KeyEvent.Tap(q.centerX, q.centerY)))
        // Most of a key width to the right of q's centre lands inside w's rectangle.
        val offCentre = KeyEvent.Tap(q.centerX + 0.8f * EvalBoard.units.keyWidth, q.centerY)
        assertEquals("w", Simulator.rawChar(offCentre))
    }
}
