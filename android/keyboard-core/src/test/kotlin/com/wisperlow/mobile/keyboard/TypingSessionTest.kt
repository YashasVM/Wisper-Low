package com.wisperlow.mobile.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An editor with a cursor and a composing span, behaving like Android's BaseInputConnection. */
private class FakeField(initial: String = "") : EditorField {
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
    override fun finishComposing() { composeStart = -1; composeEnd = -1 }
    override fun deleteBefore(count: Int) {
        val n = count.coerceAtMost(cursor)
        text.delete(cursor - n, cursor)
        cursor -= n
    }
    override fun backspaceKey() = deleteBefore(1)
    override fun batch(block: () -> Unit) = block()
    override fun toString() = text.toString()
}

class TypingSessionTest {
    private val lexicon = Lexicon(
        listOf("the" to 100_000L, "then" to 20_000L, "hello" to 30_000L, "help" to 40_000L, "don't" to 50_000L, "world" to 9_000L),
    )
    private var now = 0L
    private val field = FakeField()
    private val session = TypingSession(field, { lexicon }, { now })

    private fun typeWord(s: String) = s.forEach { session.type(it.toString()) }

    @Test
    fun `space corrects the typed word`() {
        typeWord("teh")
        session.space()
        assertEquals("the ", field.toString())
    }

    @Test
    fun `backspace right after a correction restores the typed word`() {
        typeWord("teh")
        session.space()
        session.backspace()
        assertEquals("teh", field.toString())
        assertEquals("teh", session.composing)
        session.space()
        assertEquals("teh ", field.toString())
    }

    @Test
    fun `punctuation corrects and hugs the word`() {
        typeWord("dont")
        session.type("?")
        assertEquals("don't?", field.toString())
        session.space()
        typeWord("hello")
        session.space()
        session.type(",")
        assertEquals("don't? hello,", field.toString())
    }

    @Test
    fun `double space ends the sentence`() {
        typeWord("hello")
        session.space()
        now += 200
        session.space()
        assertEquals("hello. ", field.toString())
    }

    @Test
    fun `slow second space is just a space`() {
        typeWord("hello")
        session.space()
        now += 2_000
        session.space()
        assertEquals("hello  ", field.toString())
    }

    @Test
    fun `picking a suggestion replaces the word and adds a space`() {
        typeWord("wor")
        session.pick("world")
        assertEquals("world ", field.toString())
    }

    @Test
    fun `backspacing into a word reopens it`() {
        typeWord("hello")
        session.space()
        now += 2_000
        session.backspace()
        session.backspace()
        assertEquals("hell", field.toString())
        assertEquals("hell", session.composing)
    }

    @Test
    fun `delete word removes the last word`() {
        typeWord("hello")
        session.space()
        typeWord("world")
        session.space()
        session.deleteWord()
        assertEquals("hello ", field.toString())
    }

    @Test
    fun `no autocorrect when the field turns it off`() {
        session.options = TypingOptions(autoCorrect = false)
        typeWord("teh")
        session.space()
        assertEquals("teh ", field.toString())
    }

    @Test
    fun `remembers the previous word for predictions`() {
        typeWord("hello")
        session.space()
        typeWord("wo")
        assertEquals("hello", session.previousWord())
        session.space()
        session.type(".")
        session.space()
        assertEquals(null, session.previousWord())
    }

    @Test
    fun `rewriting a word teaches the fix`() {
        typeWord("wsp")
        session.space()
        now += 2_000
        repeat(4) { session.backspace() }
        assertEquals("", field.toString())
        typeWord("wassup")
        session.space()
        typeWord("wsp")
        session.space()
        assertEquals("wassup wassup ", field.toString())
    }

    @Test
    fun `the typed word in the strip undoes the correction`() {
        typeWord("teh")
        session.space()
        assertEquals("teh", session.revertible)
        session.pick("teh")
        assertEquals("teh ", field.toString())
        assertEquals(null, session.revertible)
        typeWord("teh")
        session.space()
        assertEquals("teh teh ", field.toString())
    }

    @Test
    fun `no autocorrect inside handles, tags and paths`() {
        session.type("@")
        typeWord("teh")
        session.space()
        session.type("#")
        typeWord("teh")
        session.space()
        assertEquals("@teh #teh ", field.toString())
    }

    @Test
    fun `remembers where each letter was pressed`() {
        val t = Qwerty.touch('t', 0.1f, 0f)
        session.type("t", t)
        session.type("h")
        assertEquals(listOf(t, null), session.touches)
        session.backspace()
        assertEquals(listOf(t), session.touches)
    }

    @Test
    fun `adding an ending to a word is not a fix to learn`() {
        repeat(2) {
            typeWord("hell")
            session.space()
            now += 2_000
            session.backspace()
            typeWord("o")
            session.space()
            now += 2_000
        }
        typeWord("hell")
        session.space()
        assertEquals("hello hello hell ", field.toString())
    }

    @Test
    fun `pasted text has no typing history to learn from`() {
        typeWord("teh")
        session.space()
        session.insert("the ")
        now += 2_000
        session.backspace()
        typeWord("n")
        session.space()
        assertEquals("the then ", field.toString())
        assertEquals("the", lexicon.suggest("teh").autoCorrect)
    }

    @Test
    fun `an abandoned rewrite teaches nothing`() {
        typeWord("wsp")
        session.space()
        now += 2_000
        session.backspace()
        session.deleteWord()
        session.insert("hello ")
        typeWord("wassup")
        session.space()
        typeWord("wsp")
        session.space()
        assertEquals("hello wassup wsp ", field.toString())
    }

    @Test
    fun `moving away forgets the undo`() {
        typeWord("teh")
        session.space()
        session.forgetHistory()
        assertEquals(null, session.revertible)
        session.pick("teh")
        assertEquals("the teh ", field.toString())
    }

    @Test
    fun `literal selection does not learn in a private field`() = checkTrustPath("literal", learn = false)

    @Test
    fun `backspace undo does not learn in a private field`() = checkTrustPath("backspace", learn = false)

    @Test
    fun `strip undo does not learn in a private field`() = checkTrustPath("strip", learn = false)

    @Test
    fun `literal selection trusts the word when learning is enabled`() = checkTrustPath("literal", learn = true)

    @Test
    fun `backspace undo trusts the word when learning is enabled`() = checkTrustPath("backspace", learn = true)

    @Test
    fun `strip undo trusts the word when learning is enabled`() = checkTrustPath("strip", learn = true)

    private fun checkTrustPath(path: String, learn: Boolean) {
        lexicon.learn("private", null)
        lexicon.learn("secret", "private")
        lexicon.learnFix("wsp", "wassup")
        val before = lexicon.exportLearned()
        session.options = TypingOptions(learn = learn)
        val typed = if (path == "literal") "zorbix" else "teh"
        typeWord(typed)
        when (path) {
            "literal" -> session.pick(typed)
            "backspace" -> {
                session.space()
                assertEquals("the ", field.toString())
                session.backspace()
            }
            "strip" -> {
                session.space()
                assertEquals("the ", field.toString())
                session.pick(typed)
            }
            else -> error("Unknown trust path: $path")
        }
        assertEquals(if (path == "backspace") typed else "$typed ", field.toString())
        assertEquals(if (path == "backspace") typed else "", session.composing)
        assertEquals(null, session.revertible)
        if (learn) {
            assertTrue(lexicon.isKnown(typed))
            assertTrue(lexicon.exportLearned().words.any { it.first == typed && it.second >= 2 })
        } else {
            assertEquals(before, lexicon.exportLearned())
        }
    }

}
