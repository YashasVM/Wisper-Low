package com.wisperlow.mobile.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {
    private val lexicon by lazy {
        val base = Lexicon.parse(File("src/main/assets/dict/en.txt").readLines().asSequence())
        val modern = Lexicon.parse(File("src/main/assets/dict/en_modern.txt").readLines().asSequence())
        Lexicon(Lexicon.merge(base, modern))
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

    @Test
    fun `leaves slang alone`() {
        for (w in listOf("lowkey", "ngl", "lmao", "idc", "wyd", "rizz", "sus", "bruh", "tryna", "delulu", "fr", "istg", "bestie", "ok")) {
            assertNull(w, fix(w))
        }
    }

    @Test
    fun `knows developer words and their spelling`() {
        assertEquals("GitHub", fix("github"))
        assertEquals("API", fix("api"))
        assertEquals("TypeScript", fix("typescript"))
        assertEquals("GitHub", fix("Github"))
        assertEquals("refactor", fix("refacotr"))
        assertNull(fix("repo"))
        assertNull(fix("localhost"))
        assertNull(fix("cursor"))
    }

    @Test
    fun `stretched words and laughter are not typos`() {
        for (w in listOf("sooo", "yesss", "noooo", "hahaha", "lmaooo", "omggg", "hiii")) assertNull(w, fix(w))
        assertTrue(lexicon.suggest("sooo").words.contains("so"))
    }

    @Test
    fun `capitals inside a word are deliberate`() {
        assertNull(fix("useEffect"))
        assertNull(fix("myVar"))
    }

    @Test
    fun `where the key was touched breaks ties`() {
        val l = Lexicon(listOf("ten" to 1_000_000L, "tan" to 1_000_000L))
        assertNull(l.suggest("tsn").autoCorrect)
        // "s" pressed on its upper-right edge, towards "e".
        val nearE = listOf(null, Qwerty.touch('s', 0.35f, -0.45f), null)
        assertEquals("ten", l.suggest("tsn", touches = nearE).autoCorrect)
        // "s" pressed on its left edge, towards "a".
        val nearA = listOf(null, Qwerty.touch('s', -0.45f, 0f), null)
        assertEquals("tan", l.suggest("tsn", touches = nearA).autoCorrect)
    }

    @Test
    fun `a touch far from a key makes it an unlikely slip`() {
        val centre = Qwerty.touch('s', 0f, 0f)!!
        val edge = Qwerty.touch('s', -0.45f, 0f)!!
        assertTrue(Qwerty.slipCost(edge, 'a') < Qwerty.slipCost(centre, 'a'))
        assertEquals(1f, Qwerty.slipCost(centre, 'p'))
    }

    @Test
    fun `fixes the user taught are applied`() {
        val l = Lexicon(listOf("the" to 1000L, "was" to 5000L))
        l.learnFix("wsp", "wassup")
        assertEquals("wassup", l.suggest("wsp").autoCorrect)
        assertEquals("Wassup", l.suggest("Wsp").autoCorrect)
        assertTrue(l.suggest("wass").words.contains("wassup"))
    }

    @Test
    fun `a fix that turns one real word into another needs teaching twice`() {
        val l = Lexicon(listOf("form" to 5000L, "from" to 50000L))
        l.learnFix("form", "from")
        assertNull(l.suggest("form").autoCorrect)
        l.learnFix("form", "from")
        assertEquals("from", l.suggest("form").autoCorrect)
    }

    @Test
    fun `undoing a taught fix drops it`() {
        val l = Lexicon(listOf("the" to 1000L))
        l.learnFix("wsp", "wassup")
        l.trust("wsp")
        assertNull(l.suggest("wsp").autoCorrect)
    }

    @Test
    fun `one learned word can be forgotten`() {
        val l = Lexicon(listOf("the" to 1000L))
        l.learn("skrrt", null)
        l.learn("skrrt", null)
        assertTrue(l.isLearned("skrrt"))
        assertTrue(l.suggest("skr").words.contains("skrrt"))
        l.forget("skrrt")
        assertTrue(!l.isLearned("skrrt"))
        assertTrue(!l.suggest("skr").words.contains("skrrt"))
        assertTrue(!l.isLearned("the"))
    }

    @Test
    fun `taught fixes survive saving`() {
        val l = Lexicon(listOf("the" to 1000L))
        l.learnFix("wsp", "wassup")
        val copy = Lexicon(listOf("the" to 1000L))
        copy.importLearned(l.exportLearned())
        assertEquals("wassup", copy.suggest("wsp").autoCorrect)
    }
}
