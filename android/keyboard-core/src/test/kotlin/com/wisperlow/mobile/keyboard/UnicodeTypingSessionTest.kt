package com.wisperlow.mobile.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** An editor with a cursor and a composing span, behaving like Android's BaseInputConnection. */
private class UnicodeField(initial: String = "") : EditorField {
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

/**
 * Explicitly taught Unicode replacements exercise native words committing, undoing, reopening
 * and deleting as one word. This is editor coverage, independent of the Latin regional modes.
 * Dependent vowels and virama stay part of their word; editor deletes count UTF-16 units.
 */
class UnicodeTypingSessionTest {
    private val lexicon = Lexicon(emptyList()).apply {
        learnFix("namaste", "नमस्ते")
        learnFix("accha", "अच्छा")
    }
    private var now = 0L
    private val field = UnicodeField()
    private val session = TypingSession(field, { lexicon }, { now })

    private fun typeWord(s: String) = s.forEach { session.type(it.toString()) }

    @Test
    fun `space commits the exact native word`() {
        typeWord("namaste")
        session.space()
        assertEquals("नमस्ते ", field.toString())
    }

    @Test
    fun `backspace right after the conversion restores the roman typed`() {
        typeWord("namaste")
        session.space()
        session.backspace()
        assertEquals("namaste", field.toString())
        assertEquals("namaste", session.composing)
        // What the user restored is theirs now, so space keeps it.
        session.space()
        assertEquals("namaste ", field.toString())
    }

    @Test
    fun `picking a native completion commits the whole word`() {
        typeWord("nama")
        session.pick("नमस्ते")
        assertEquals("नमस्ते ", field.toString())
    }

    @Test
    fun `backspacing into a committed native word reopens it whole`() {
        session.options = TypingOptions(autoCorrect = false)
        typeWord("namaste")
        session.pick("नमस्ते")
        assertEquals("नमस्ते ", field.toString())

        session.backspace()
        // The virama and dependent vowel stay inside the reopened word.
        assertEquals("नमस्ते", field.toString())
        assertEquals("नमस्ते", session.composing)
    }

    @Test
    fun `the previous word keeps its combining marks`() {
        typeWord("namaste")
        session.pick("नमस्ते")
        typeWord("accha")
        assertEquals("नमस्ते", session.previousWord())
    }

    @Test
    fun `delete word removes a native word whole`() {
        typeWord("namaste")
        session.pick("नमस्ते")
        typeWord("accha")
        session.pick("अच्छा")
        session.deleteWord()
        assertEquals("नमस्ते ", field.toString())
    }

    @Test
    fun `English typing still corrects typos`() {
        val english = Lexicon(listOf("the" to 100_000L, "then" to 20_000L))
        val englishField = UnicodeField()
        val englishSession = TypingSession(englishField, { english }, { now })
        "teh".forEach { englishSession.type(it.toString()) }
        englishSession.space()
        assertEquals("the ", englishField.toString())
    }
}
