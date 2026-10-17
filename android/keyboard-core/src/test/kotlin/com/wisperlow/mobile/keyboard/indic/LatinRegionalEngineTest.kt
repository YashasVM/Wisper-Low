package com.wisperlow.mobile.keyboard.indic

import com.wisperlow.mobile.keyboard.KeyboardLanguage
import com.wisperlow.mobile.keyboard.Lexicon
import com.wisperlow.mobile.keyboard.Qwerty
import org.junit.Assert.*
import org.junit.Test

class LatinRegionalEngineTest {
    private val regional = listOf("namaskara" to 100L, "hegiddiya" to 80L, "hegidiya" to 1L, "illa" to 50L)
    private val english = listOf("hence" to Long.MAX_VALUE, "match" to Long.MAX_VALUE, "bar" to Long.MAX_VALUE, "hello" to 1000L)

    @Test fun `user phrases survive adversarial English vocabulary without being training words`() {
        val engine = LatinRegionalEngine(regional, english)
        listOf("macha", "henge edhiya", "baro le").forEach { phrase ->
            phrase.split(' ').forEach { word ->
                assertEquals(word, engine.suggest(word).words.first())
                assertNull(engine.suggest(word).autoCorrect)
            }
        }
    }

    @Test fun `attested rare variants and capitals remain literal`() {
        val engine = LatinRegionalEngine(regional + ("hegidhiya" to 1000000L), english)
        listOf("hegidiya", "Hegidiya", "HEGIDIYA", "hEgIdIyA").forEach {
            assertNull(engine.suggest(it).autoCorrect)
            assertEquals(it, engine.suggest(it).words.first())
        }
    }

    @Test fun `unique touch supported transposition corrects into regional Latin with matching case`() {
        val engine = LatinRegionalEngine(regional, english)
        val touches = "namaskara".map { Qwerty.touch(it, 0f, 0f) }
        assertNull(engine.suggest("namaksara").autoCorrect)
        assertEquals("namaskara", engine.suggest("namaksara", touches = touches).autoCorrect)
        assertEquals("Namaskara", engine.suggest("Namaksara", touches = touches).autoCorrect)
        assertEquals("NAMASKARA", engine.suggest("NAMAKSARA", touches = touches).autoCorrect)
        assertNull(engine.suggest("naMaksara", touches = touches).autoCorrect)
    }

    @Test fun `adjacent consonant substitution needs touch evidence`() {
        val engine = LatinRegionalEngine(regional, english)
        val raw = "namaskafa"
        assertNull(engine.suggest(raw).autoCorrect)
        val touches = raw.map { Qwerty.touch(it, 0f, 0f) }.toMutableList()
        touches[7] = Qwerty.touch('r', 0f, 0f)
        assertEquals("namaskara", engine.suggest(raw, touches = touches).autoCorrect)
        assertNull(engine.suggest(raw, touches = raw.map { Qwerty.touch(it, 0f, 0f) }).autoCorrect)
    }

    @Test fun `vowel styles omissions repeated letters and ambiguous swaps stay manual`() {
        val engine = LatinRegionalEngine(regional + listOf("abdcdef" to 90L, "bacddef" to 80L), english)
        listOf("hegiddiyaa", "hegiddya", "namaskera", "abcddef").forEach {
            assertNull(engine.suggest(it).autoCorrect)
        }
        assertTrue("namaskara" in engine.suggest("namaskera").words)
        assertNull(engine.suggest("namaskera", touches = "namaskara".map { Qwerty.touch(it, 0f, 0f) }).autoCorrect)
        assertNull(engine.suggest("abcddef", touches = "badcdef".map { Qwerty.touch(it, 0f, 0f) }).autoCorrect)
    }

    @Test fun `attested heldout spellings remain literal near dictionary transpositions`() {
        // Distinct real romanizations observed in the source data. They are validation examples,
        // never production spelling exceptions or training entries for their unknown forms.
        val engine = LatinRegionalEngine(listOf("amruta" to 1000000L, "angiya" to 1000000L, "aadmi" to 1000000L), english)
        listOf("amurta", "agniya", "aadim").forEach {
            assertNull(engine.suggest(it).autoCorrect)
            assertEquals(it, engine.suggest(it).words.first())
        }
    }

    @Test fun `empty regional data never becomes English autocorrection`() {
        val engine = LatinRegionalEngine(emptyList(), english)
        assertEquals("helo", engine.suggest("helo").words.first())
        assertNull(engine.suggest("helo").autoCorrect)
        assertTrue("hello" in engine.suggest("hel").words)
        assertTrue(engine.isKnown("hello"))
        assertEquals(emptyList<String>(), engine.predict(null))
    }

    @Test fun `both regional and English completions remain reachable`() {
        val engine = LatinRegionalEngine(regional, english + ("namaste" to Long.MAX_VALUE))
        val suggestions = engine.suggest("nama")
        assertEquals("nama", suggestions.words.first())
        assertTrue("namaskara" in suggestions.words)
        assertTrue("namaste" in suggestions.words)
        assertNull(suggestions.autoCorrect)
    }

    @Test fun `corpus next words are separate from personal persistence and follow personal picks`() {
        val engine = LatinRegionalEngine(regional, english, listOf(Triple("namaskara", "illa", 8L)))
        assertEquals("illa", engine.predict("namaskara").first())
        assertTrue(engine.exportLearned().pairs.isEmpty())
        engine.learn("hello", "namaskara")
        assertEquals("hello", engine.predict("namaskara").first())
        val restored = LatinRegionalEngine(regional, english)
        restored.importLearned(engine.exportLearned())
        assertEquals("hello", restored.predict("namaskara").first())
        restored.forget("hello")
        assertFalse(restored.isLearned("hello"))
        assertFalse("hello" in restored.predict("namaskara"))
    }

    @Test fun `explicit fixes survive persistence trust and forgetting`() {
        val engine = LatinRegionalEngine(regional, english)
        engine.learnFix("nmkr", "namaskara")
        assertEquals("namaskara", engine.suggest("nmkr").autoCorrect)
        val restored = LatinRegionalEngine(regional, english)
        restored.importLearned(engine.exportLearned())
        assertEquals("namaskara", restored.suggest("nmkr").autoCorrect)
        restored.trust("nmkr")
        assertNull(restored.suggest("nmkr").autoCorrect)
        restored.learnFix("nmkr", "namaskara")
        restored.forget("namaskara")
        assertNull(restored.suggest("nmkr").autoCorrect)
    }

    @Test fun `trusted unknown spellings disable geometry correction`() {
        val engine = LatinRegionalEngine(regional, english)
        val touches = "namaskara".map { Qwerty.touch(it, 0f, 0f) }
        engine.trust("namaksara")
        assertNull(engine.suggest("namaksara", touches = touches).autoCorrect)
        assertTrue(engine.isLearned("namaksara"))
        engine.forget("namaksara")
        assertEquals("namaskara", engine.suggest("namaksara", touches = touches).autoCorrect)
    }

    @Test fun `teaching a target spelling removes a previous fix away from that spelling`() {
        val engine = LatinRegionalEngine(regional, english)
        engine.learnFix("typoone", "namaskara")
        engine.learnFix("typotwo", "typoone")
        assertNull(engine.suggest("typoone").autoCorrect)
        assertEquals("typoone", engine.suggest("typotwo").autoCorrect)
    }

    @Test fun `imported pair and fix keys ignore casing and apostrophes like live learning`() {
        val engine = LatinRegionalEngine(regional, english)
        engine.importLearned(Lexicon.LearnedData(listOf("fresh" to 2),
            listOf(Triple("HEL'LO", "FRESH", 3)), listOf(Triple("O'OPS", "fresh", 1))))
        assertEquals("fresh", engine.predict("hello").first())
        assertEquals("fresh", engine.suggest("oops").autoCorrect)
        engine.forget("fresh")
        assertNull(engine.suggest("oops").autoCorrect)
        assertFalse("fresh" in engine.predict("hello"))
    }

    @Test fun `native script and malformed data cannot enter suggestions predictions or learned export`() {
        val engine = LatinRegionalEngine(regional + listOf("नमस्ते" to 99L, "bad1" to 90L, "bad" to -2L), english,
            listOf(Triple("hello", "नमस्ते", 9L), Triple("hello", "illa", 1L)))
        engine.learn("नमस्ते", "hello")
        engine.trust("ನಮಸ್ಕಾರ")
        engine.learnFix("oops", "नमस्ते")
        engine.importLearned(Lexicon.LearnedData(listOf("नमस्ते" to 5, "fresh" to 2),
            listOf(Triple("hello", "नमस्ते", 5)), listOf(Triple("oops", "ನಮಸ್ಕಾರ", 5))))
        assertEquals(listOf("fresh" to 2), engine.exportLearned().words)
        assertTrue(engine.exportLearned().pairs.isEmpty())
        assertTrue(engine.exportLearned().fixes.isEmpty())
        assertTrue(engine.suggest("नम").words.isEmpty())
        assertTrue(engine.predict("hello").all { it.all { c -> c in 'a'..'z' } })
    }

    @Test fun `nonpositive limits and empty input yield nothing`() {
        val engine = LatinRegionalEngine(regional, english)
        assertTrue(engine.suggest("nama", max = 0).words.isEmpty())
        assertNull(engine.suggest("namaksara", max = -1).autoCorrect)
        assertTrue(engine.predict("hello", 0).isEmpty())
        assertTrue(engine.suggest("").words.isEmpty())
    }

    @Test fun `stable language codes identify Latin text modes`() {
        KeyboardLanguage.entries.forEach { assertEquals(it, KeyboardLanguage.fromCode(it.code)) }
        assertEquals(KeyboardLanguage.ENGLISH, KeyboardLanguage.fromCode(null))
        assertEquals(KeyboardLanguage.ENGLISH, KeyboardLanguage.fromCode("de"))
        assertEquals(KeyboardLanguage.HINDI, KeyboardLanguage.fromCode(" HI "))
        assertTrue(KeyboardLanguage.KANNADA.isIndic)
    }

    @Test fun `evicted taught fixes behave the same after save and reload`() {
        val engine = LatinRegionalEngine(regional, english)
        val sources = (0..2000).map { index ->
            var value = index
            "typo" + buildString {
                repeat(4) {
                    append('a' + value % 26)
                    value /= 26
                }
            }
        }
        sources.forEach { engine.learnFix(it, "namaskara") }
        val data = engine.exportLearned()
        val persistedSources = data.fixes.mapTo(HashSet()) { it.first }
        val evicted = sources.first { it !in persistedSources }
        val restored = LatinRegionalEngine(regional, english).apply { importLearned(data) }
        assertEquals(restored.suggest(evicted).autoCorrect, engine.suggest(evicted).autoCorrect)
    }

    @Test fun `incrementing maximum imported counts does not discard a fix on reload`() {
        val engine = LatinRegionalEngine(regional, english)
        engine.importLearned(Lexicon.LearnedData(listOf("namaskara" to Int.MAX_VALUE),
            listOf(Triple("hello", "namaskara", Int.MAX_VALUE)),
            listOf(Triple("nmkr", "namaskara", Int.MAX_VALUE))))
        engine.learn("namaskara", "hello")
        engine.learnFix("nmkr", "namaskara")
        val data = engine.exportLearned()
        val restored = LatinRegionalEngine(regional, english).apply { importLearned(data) }
        assertEquals("namaskara", restored.suggest("nmkr").autoCorrect)
        assertTrue(data.words.all { it.second > 0 })
        assertTrue(data.pairs.all { it.third > 0 })
        assertTrue(data.fixes.all { it.third > 0 })
    }

    @Test fun `a single available strip slot never hides an automatic replacement`() {
        val engine = LatinRegionalEngine(regional, english)
        engine.learnFix("nmkr", "namaskara")
        val suggestions = engine.suggest("nmkr", max = 1)
        assertTrue(suggestions.autoCorrect == null || suggestions.autoCorrect in suggestions.words)
    }

    @Test fun `duplicate and conflicting imports use the same replacement before and after reload`() {
        val engine = LatinRegionalEngine(regional, english)
        engine.importLearned(Lexicon.LearnedData(emptyList(), emptyList(),
            listOf(Triple("o'ops", "illa", 3), Triple("OOPS", "namaskara", 2))))
        assertEquals("namaskara", engine.suggest("oops").autoCorrect)
        engine.importLearned(Lexicon.LearnedData(emptyList(), emptyList(), listOf(Triple("oops", "hello", 1))))
        assertEquals("hello", engine.suggest("oops").autoCorrect)
        val restored = LatinRegionalEngine(regional, english).apply { importLearned(engine.exportLearned()) }
        assertEquals("hello", restored.suggest("oops").autoCorrect)
        restored.trust("oops")
        assertNull(restored.suggest("oops").autoCorrect)
    }

    @Test fun `duplicate maximum corpus counts keep their ranking without overflowing`() {
        val engine = LatinRegionalEngine(listOf("namaskara" to Long.MAX_VALUE, "namaskara" to Long.MAX_VALUE, "illa" to 100L), english,
            listOf(Triple("hello", "namaskara", Long.MAX_VALUE), Triple("hello", "namaskara", Long.MAX_VALUE), Triple("hello", "illa", 100L)))
        assertEquals("namaskara", engine.predict(null).first())
        assertEquals("namaskara", engine.predict("hello").first())
    }

    @Test fun `expressive input remains literal until explicitly taught`() {
        val engine = LatinRegionalEngine(regional, english)
        listOf("haaaaa", "hahaha", "noooo").forEach { raw ->
            assertEquals(raw, engine.suggest(raw).words.first())
            assertNull(engine.suggest(raw).autoCorrect)
        }
        engine.learnFix("hahaha", "hello")
        assertEquals("hello", engine.suggest("hahaha").autoCorrect)
        engine.trust("hahaha")
        assertNull(engine.suggest("hahaha").autoCorrect)
    }
}
