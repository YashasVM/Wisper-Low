package com.wisperlow.mobile.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {
    private val lexicon by lazy {
        Lexicon(Lexicon.parse(File("src/main/assets/dict/en.txt").readLines().asSequence()))
    }

    private fun fix(typed: String, previous: String? = null) = lexicon.suggest(typed, previous).autoCorrect

    @Test
    fun `fixes common typos`() {
        assertEquals("the", fix("teh"))
        assertEquals("because", fix("becuase"))
        assertEquals("would", fix("woudl"))
        assertEquals("hello", fix("helo"))
        assertEquals("people", fix("peopel"))
        assertEquals("definitely", fix("definately"))
        assertEquals("tomorrow", fix("tommorow"))
        assertEquals("until", fix("untill"))
        assertEquals("really", fix("realy"))
    }

    @Test
    fun `adds apostrophes and capitals`() {
        assertEquals("don't", fix("dont"))
        assertEquals("I'm", fix("im"))
        assertEquals("can't", fix("cant"))
        assertEquals("I", fix("i"))
        assertEquals("Monday", fix("monday"))
        assertEquals("Don't", fix("Dont"))
    }

    @Test
    fun `leaves real words and ambiguous ones alone`() {
        assertNull(fix("its"))
        assertNull(fix("well"))
        assertNull(fix("the"))
        assertNull(fix("The"))
        assertNull(fix("cat"))
        assertNull(fix("form"))
        assertNull(fix("x"))
    }

    @Test
    fun `does not correct a word that is still being typed`() {
        assertNull(fix("tomor"))
        assertTrue(lexicon.suggest("tomor").words.contains("tomorrow"))
    }

    @Test
    fun `keeps typed capitals on corrections`() {
        assertEquals("The", fix("Teh"))
        assertEquals("THE", fix("TEH"))
    }

    @Test
    fun `suggests completions with the typed word first`() {
        val s = lexicon.suggest("hap")
        assertTrue(s.words.contains("hap"))
        assertTrue(s.words.toString(), s.words.any { it == "happy" || it == "happen" || it == "happened" })
    }

    @Test
    fun `learned words stop being corrected and get suggested`() {
        val l = Lexicon(listOf("the" to 1000L, "then" to 500L))
        l.trust("thw")
        assertNull(l.suggest("thw").autoCorrect)
        l.learn("Wisperlow", null)
        l.learn("Wisperlow", null)
        assertTrue(l.suggest("wisp").words.contains("Wisperlow"))
    }

    @Test
    fun `predicts next words from what the user typed`() {
        val l = Lexicon(listOf("good" to 10L, "morning" to 10L))
        l.learn("good", null)
        l.learn("morning", "good")
        assertEquals(listOf("morning"), l.predict("good"))
    }

    @Test
    fun `neighbouring keys are cheaper typos`() {
        assertTrue(Lexicon.distance("tge", "the", 2f) < Lexicon.distance("tze", "the", 2f))
    }

    @Test
    fun `is fast enough to run per keystroke`() {
        lexicon.suggest("warm")
        val start = System.nanoTime()
        repeat(50) { lexicon.suggest("recieved") }
        val perCall = (System.nanoTime() - start) / 50 / 1_000_000.0
        assertTrue("took $perCall ms", perCall < 25)
    }

    @Test
    fun `short words are not mangled`() {
        assertNull(fix("epp"))
    }

    @Test
    fun `completions take the middle slot while a word is unfinished`() {
        val words = lexicon.suggest("hap").words
        assertTrue(words.toString(), words[1] == "happy" || words[1] == "happen" || words[1] == "happened")
    }
}
