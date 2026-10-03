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
    fun removesFillerWordsAndStutters() {
        assertEquals("actually", TextCleaner.clean("um uh actually"))
        assertEquals("The build is, ready", TextCleaner.clean("The build is, uh, ready"))
        assertEquals("I want the onboarding", TextCleaner.clean("I I want uh the the onboarding"))
    }

    @Test
    fun keepsFillerWordsThatAreBeingDiscussed() {
        val quoted = "Keep the word uh in the example sentence"
        assertEquals(quoted, TextCleaner.clean(quoted))
        val literal = "The note literally says quote um end quote before the title"
        assertEquals(literal, TextCleaner.clean(literal))
    }

    @Test
    fun convertsSpokenPunctuation() {
        assertEquals("Hello, world.", TextCleaner.clean("Hello comma world period"))
        assertEquals("Is it ready? Yes!", TextCleaner.clean("Is it ready question mark yes exclamation point"))
        assertEquals("First line\nSecond", TextCleaner.clean("First line new line Second"))
        assertEquals("One\n\nTwo", TextCleaner.clean("One new paragraph two"))
        assertEquals("Done. Next", TextCleaner.clean("Done period next"))
    }

    @Test
    fun leavesNumbersAndPunctuationWordsAsNouns() {
        assertEquals("Costs 1,000.50 or 5.6", TextCleaner.clean("Costs 1,000.50 or 5.6"))
        assertEquals("the period of the wave", TextCleaner.clean("the period of the wave"))
        assertEquals("a comma is punctuation", TextCleaner.clean("a comma is punctuation"))
    }

    @Test
    fun wordCounting() {
        assertEquals(0, TextCleaner.wordCount(""))
        assertEquals(3, TextCleaner.wordCount("hello world again"))
        assertEquals(6, TextCleaner.wordCount("hello, don't stop-it 42 now"))
        assertEquals(2, TextCleaner.wordCount("हिंदी भाषा"))
    }

    @Test
    fun periodAsNounIsNotPunctuation() {
        assertEquals("the grace period ends today", TextCleaner.clean("the grace period ends today"))
        assertEquals("a long time period applies", TextCleaner.clean("a long time period applies"))
    }
}
