package com.wisperlow.mobile.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An editor with a cursor and a composing span, behaving like Android's BaseInputConnection. */
private class SessionField(initial: String = "") : EditorField {
    val text = StringBuilder(initial)
    var cursor = initial.length
    var composeStart = -1
    var composeEnd = -1

    override fun before(count: Int) = text.substring((cursor - count).coerceAtLeast(0), cursor)
    override fun after(count: Int) = text.substring(cursor, (cursor + count).coerceAtMost(text.length))

    private fun replaceComposing(with: String) {
        val (s, e) = if (composeStart >= 0) composeStart to composeEnd else cursor to cursor
        text.replace(s, e, with)
        cursor = s + with.length
        composeStart = -1
        composeEnd = -1
    }

    override fun setComposing(text: String) {
        val start = if (composeStart >= 0) composeStart else cursor
        replaceComposing(text)
        if (text.isNotEmpty()) {
            composeStart = start
            composeEnd = start + text.length
        }
    }

    override fun commit(text: String) = replaceComposing(text)
    override fun finishComposing() {
        composeStart = -1
        composeEnd = -1
    }
    override fun deleteBefore(count: Int) {
        val n = count.coerceAtMost(cursor)
        text.delete(cursor - n, cursor)
        cursor -= n
    }
    override fun backspaceKey() = deleteBefore(1)
    override fun batch(block: () -> Unit) = block()
    override fun toString() = text.toString()
}

/**
 * A [WordEngine] with no dictionary of its own: it corrects one memorised slip and
 * records every lesson the session teaches, so the tests can tell the session apart
 * from [Lexicon] itself. Works through the interface only, as the keyboard does.
 */
private class RecordingEngine : WordEngine {
    val learned = ArrayList<String>()
    val taught = ArrayList<Pair<String, String>>()
    val trusted = ArrayList<String>()
    val asked = ArrayList<String>()
    /** Where each character arrived: nothing the user typed gets lost between session and engine. */
    var lastTouches: List<Touch?>? = null

    override fun suggest(typed: String, previous: String?, max: Int, touches: List<Touch?>?): Suggestions {
        asked += typed
        lastTouches = touches
        // A trusted word is what the user meant, so a slip under it is no longer a slip.
        val auto = if (typed == "teh" && typed !in trusted) "the" else null
        return Suggestions(listOf(typed, "the").take(max), auto)
    }

    override fun predict(previous: String?, max: Int): List<String> = listOfNotNull(previous).take(max)
    override fun isKnown(word: String) = false
    override fun learn(word: String, previous: String?) {
        learned += word
    }
    override fun learnFix(typed: String, intended: String) {
        taught += typed to intended
    }
    override fun trust(word: String) {
        trusted += word
    }
    override fun isLearned(word: String) = false
    override fun forget(word: String) {}
    override fun exportLearned(): Lexicon.LearnedData = Lexicon.LearnedData(emptyList(), emptyList())
    override fun importLearned(data: Lexicon.LearnedData) {}
}

/**
 * The session's contract with a word engine, independent of any one implementation:
 * what gets composed, committed, undone and taught when the engine is connected the
 * way the keyboard connects it.
 */
class EngineSessionTest {
    private var now = 0L
    private val field = SessionField()
    private val engine = RecordingEngine()
    private val session = TypingSession(field, { engine }, { now })

    private fun typeWord(s: String) = s.forEach { session.type(it.toString()) }

    @Test
    fun `letters stay composing and space commits the correction`() {
        typeWord("teh")
        assertEquals("teh", session.composing)
        assertEquals("teh", field.toString())
        session.space()
        assertEquals("the ", field.toString())
        // Committing teaches the engine the word that went into the field.
        assertEquals(listOf("the"), engine.learned)
        assertEquals(emptyList<Pair<String, String>>(), engine.taught)
    }

    @Test
    fun `backspace right after a correction restores the typed word and trusts it`() {
        typeWord("teh")
        session.space()
        session.backspace()
        assertEquals("teh", field.toString())
        assertEquals("teh", session.composing)
        assertTrue("teh" in engine.trusted)
        // The trusted word is no longer a slip to fix.
        session.space()
        assertEquals("teh ", field.toString())
    }

    @Test
    fun `picking the typed word in the strip undoes the correction`() {
        typeWord("teh")
        session.space()
        assertEquals("teh", session.revertible)
        session.pick("teh")
        assertEquals("teh ", field.toString())
        assertTrue("teh" in engine.trusted)
        // Undo takes back the correction; only the earlier corrected commit was learned.
        assertEquals(listOf("the"), engine.learned)
    }

    @Test
    fun `a word committed without a correction is learned as typed`() {
        typeWord("hello")
        session.space()
        assertEquals("hello ", field.toString())
        assertEquals(listOf("hello"), engine.learned)
        assertEquals(emptyList<Pair<String, String>>(), engine.taught)
    }

    @Test
    fun `going back into a word and rewriting it teaches the fix`() {
        typeWord("teh")
        session.space()
        now += 2_000
        // One backspace undoes the correction and restores the typed word, three more erase it.
        repeat(4) { session.backspace() }
        assertEquals("", field.toString())
        typeWord("the")
        session.space()
        assertEquals(listOf("teh" to "the"), engine.taught)
        // The rewrite kept the user's spelling in the field, once.
        assertEquals("the ", field.toString())
    }

    @Test
    fun `learning disabled commits corrections but teaches nothing`() {
        session.options = TypingOptions(learn = false)
        typeWord("teh")
        session.space()
        assertEquals("the ", field.toString())
        assertTrue(engine.learned.isEmpty())
        assertTrue(engine.taught.isEmpty())
        assertTrue(engine.trusted.isEmpty())
    }

    @Test
    fun `the session hands the engine its typed touches for decoder evidence`() {
        val t = Qwerty.touch('t', 0.1f, -0.05f)
        session.type("t", t)
        session.type("h")
        session.space()
        assertEquals(listOf<Touch?>(t, null), engine.lastTouches)
    }
}
