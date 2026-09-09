package com.wisperlow.mobile.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {
    @Test
    fun preservesMeaningAndIntentionalRepetitions() {
        val text = "Actually, I had had a very very good period."
        assertEquals(text, TextCleaner.clean(text))
        assertEquals("They is more better", TextCleaner.clean("They is more better"))
        assertEquals("um uh actually", TextCleaner.clean("um uh actually"))
    }

    @Test
    fun preservesNamesCaseNumbersAndSpokenPunctuationWords() {
        assertEquals("iPhone costs 123.45", TextCleaner.clean("iPhone costs 123.45"))
        assertEquals("a comma is punctuation", TextCleaner.clean("a comma is punctuation"))
    }

    @Test
    fun normalizesSpacingWithoutLosingParagraphs() {
        assertEquals("Hello, world!\n\nNext line", TextCleaner.clean("  Hello ,  world!\n\n Next\tline  "))
        assertEquals("", TextCleaner.clean("   \t "))
    }

    @Test
    fun acceptsShortNumericMultilingualAndRepeatedSpeech() {
        listOf("I", "a", "12345", "हिंदी भाषा", "你好", "cat cat cat cat", "hahahaha").forEach {
            assertFalse(it, TextCleaner.looksLikeGibberish(it))
        }
        assertTrue(TextCleaner.looksLikeGibberish(""))
        assertTrue(TextCleaner.looksLikeGibberish("... !"))
    }

    @Test
    fun wordCounting() {
        assertEquals(0, TextCleaner.wordCount(""))
        assertEquals(3, TextCleaner.wordCount("hello world again"))
        assertEquals(6, TextCleaner.wordCount("hello, don't stop-it 42 now"))
        assertEquals(2, TextCleaner.wordCount("हिंदी भाषा"))
    }

}
