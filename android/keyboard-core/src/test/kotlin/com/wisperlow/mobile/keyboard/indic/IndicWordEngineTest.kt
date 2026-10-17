package com.wisperlow.mobile.keyboard.indic

import com.wisperlow.mobile.keyboard.KeyboardLanguage
import com.wisperlow.mobile.keyboard.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of [IndicWordEngine] on tiny, hand-written fixtures. Production Hindi and Kannada
 * data is owned by the data pipeline, so every expectation here is a literal.
 */
class IndicWordEngineTest {
    private val hindiIndex = RomanizationIndex(
        sequenceOf(
            "namaste\tनमस्ते\t120",
            "namaskar\tनमस्कार\t60",
            "accha\tअच्छा\t200",
        ),
    )

    private val kannadaIndex = RomanizationIndex(
        sequenceOf(
            "namaskara\tನಮಸ್ಕಾರ\t500",
            "hegiddiya\tಹೇಗಿದ್ದೀಯ\t400",
        ),
    )

    @Test
    fun `an exact romanization offers the native word as an auto correction`() {
        val engine = IndicWordEngine(hindiIndex)
        val suggestions = engine.suggest("namaste")
        assertEquals("नमस्ते", suggestions.autoCorrect)
        // The raw Latin stays first, so the typed spelling is always reachable.
        assertEquals("namaste", suggestions.words.first())
        assertTrue("नमस्ते" in suggestions.words)
    }

    @Test
    fun `a completion is a suggestion but is never auto committed`() {
        val engine = IndicWordEngine(hindiIndex)
        val suggestions = engine.suggest("nama")
        assertNull(suggestions.autoCorrect)
        assertEquals("nama", suggestions.words.first())
        assertTrue("नमस्ते" in suggestions.words)
    }

    @Test
    fun `unknown latin is preserved and never converted`() {
        val engine = IndicWordEngine(hindiIndex)
        val suggestions = engine.suggest("zzzz")
        assertEquals(listOf("zzzz"), suggestions.words)
        assertNull(suggestions.autoCorrect)
    }

    @Test
    fun `typo fixes are manual only`() {
        val engine = IndicWordEngine(hindiIndex)
        val suggestions = engine.suggest("namste")
        assertNull(suggestions.autoCorrect)
        assertTrue("नमस्ते" in suggestions.words)
    }

    @Test
    fun `an explicitly learned fix overrides the index`() {
        val engine = IndicWordEngine(RomanizationIndex(sequenceOf("haan\tहाँ\t100")))
        engine.learnFix("haan", "हाँजी")
        assertEquals("हाँजी", engine.suggest("haan").autoCorrect)
    }

    @Test
    fun `trusting what was typed stops the conversion`() {
        val engine = IndicWordEngine(hindiIndex)
        engine.trust("namaste")
        val suggestions = engine.suggest("namaste")
        assertNull(suggestions.autoCorrect)
        assertEquals("namaste", suggestions.words.first())
    }

    @Test
    fun `native words learn, predict, persist and unlearn`() {
        val engine = IndicWordEngine(hindiIndex)
        engine.learn("नमस्ते", null)
        engine.learn("अच्छा", "नमस्ते")
        assertTrue(engine.isLearned("अच्छा"))
        assertEquals(listOf("अच्छा"), engine.predict("नमस्ते"))

        // A fresh engine reads the same learned data back, as the keyboard does from disk.
        val restored = IndicWordEngine(hindiIndex)
        restored.importLearned(engine.exportLearned())
        assertEquals(listOf("अच्छा"), restored.predict("नमस्ते"))

        restored.forget("अच्छा")
        assertTrue(restored.predict("नमस्ते").isEmpty())
        assertFalse(restored.isLearned("अच्छा"))
    }

    @Test
    fun `Kannada romanizations reach their literal script`() {
        val engine = IndicWordEngine(kannadaIndex)
        assertEquals("ನಮಸ್ಕಾರ", engine.suggest("namaskara").autoCorrect)
        assertEquals("ಹೇಗಿದ್ದೀಯ", engine.suggest("hegiddiya").autoCorrect)
    }

    @Test
    fun `native typed text is kept as is`() {
        val engine = IndicWordEngine(hindiIndex)
        assertEquals(listOf("नमस्ते"), engine.suggest("नमस्ते").words)
        assertNull(engine.suggest("नमस्ते").autoCorrect)
    }

    @Test
    fun `lexicon word text accepts native letters and combining marks`() {
        assertTrue(Lexicon.isWordText("नमस्ते"))
        assertTrue(Lexicon.isWordText("ಅಚ್ಛಾ"))
        // English behaviour is unchanged, apostrophes included.
        assertTrue(Lexicon.isWordText("don't"))
        assertTrue(Lexicon.isWordText("hello"))
        assertFalse(Lexicon.isWordText("real-ly"))
        assertFalse(Lexicon.isWordText("two words"))
        assertFalse(Lexicon.isWordText(""))
    }

    @Test
    fun `language codes round trip and unknown values fall back to English`() {
        KeyboardLanguage.entries.forEach { assertEquals(it, KeyboardLanguage.fromCode(it.code)) }
        assertEquals(KeyboardLanguage.ENGLISH, KeyboardLanguage.fromCode(null))
        assertEquals(KeyboardLanguage.ENGLISH, KeyboardLanguage.fromCode(""))
        assertEquals(KeyboardLanguage.ENGLISH, KeyboardLanguage.fromCode("de"))
        assertEquals(KeyboardLanguage.HINDI, KeyboardLanguage.fromCode(" HI "))
        assertTrue(KeyboardLanguage.HINDI.isIndic)
        assertFalse(KeyboardLanguage.ENGLISH.isIndic)
    }
}
