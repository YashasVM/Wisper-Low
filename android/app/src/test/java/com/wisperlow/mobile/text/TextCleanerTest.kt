package com.wisperlow.mobile.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    @Test
    fun fillerRemoval() {
        assertEquals("I want.", TextCleaner.clean("um i basically want"))
        assertEquals("You know this works.", TextCleaner.clean("you know this works"))
    }

    @Test
    fun fillerEatsFollowingCommaAndSpace() {
        assertEquals("It was weird.", TextCleaner.clean("it was um, weird"))
        assertEquals("Like, I said.", TextCleaner.clean("like, i said"))
    }

    @Test
    fun repetitionRemoval() {
        assertEquals("The quick brown fox.", TextCleaner.clean("the the quick quick brown fox fox"))
    }

    @Test
    fun shortWordsNotDedupedByLengthRuleButTripleRuleApplies() {
        assertEquals("That is is fine.", TextCleaner.clean("that is is is fine"))
    }

    @Test
    fun spokenPeriodBecomesDot() {
        assertEquals("This is great. really.", TextCleaner.clean("this is great period really"))
    }

    @Test
    fun fullStopAlsoBecomesDot() {
        assertEquals("Done. next.", TextCleaner.clean("done full stop next"))
    }

    @Test
    fun commaInsertion() {
        assertEquals("Wait, really.", TextCleaner.clean("wait comma really"))
    }

    @Test
    fun spokenNewLineBecomesLineBreak() {
        assertEquals("First line\nsecond line.", TextCleaner.clean("first line new line second line"))
    }

    @Test
    fun spokenNewParagraphBecomesDoubleBreak() {
        assertEquals("First para\n\nsecond para.", TextCleaner.clean("first para new paragraph second para"))
    }

    @Test
    fun questionAndExclamationAndColons() {
        assertEquals("Really?", TextCleaner.clean("really question mark"))
        assertEquals("Wow!", TextCleaner.clean("wow exclamation mark"))
        assertEquals("Note: this.", TextCleaner.clean("note colon this"))
        assertEquals("Yes; no.", TextCleaner.clean("yes semicolon no"))
    }

    @Test
    fun capitalizesFirstLetterOnly() {
        assertEquals("Hello world.", TextCleaner.clean("hello world"))
        assertEquals("Iphone stays.", TextCleaner.clean("iphone stays"))
    }

    @Test
    fun terminalPeriodAddedWhenMissing() {
        assertEquals("No punctuation here.", TextCleaner.clean("no punctuation here"))
    }

    @Test
    fun terminalPunctuationNotDuplicated() {
        assertEquals("Already done.", TextCleaner.clean("already done period"))
        assertEquals("Really?", TextCleaner.clean("really?"))
    }

    @Test
    fun emptyInputReturnsEmptyString() {
        assertEquals("", TextCleaner.clean(""))
        assertEquals("", TextCleaner.clean("   \t  "))
        assertEquals("", TextCleaner.clean("um uh actually"))
    }

    @Test
    fun gibberishTooFewLettersIsTrue() {
        assertTrue(TextCleaner.looksLikeGibberish(""))
        assertTrue(TextCleaner.looksLikeGibberish("a"))
        assertTrue(TextCleaner.looksLikeGibberish("12345"))
    }

    @Test
    fun gibberishLowLetterDiversityIsTrue() {
        assertTrue(TextCleaner.looksLikeGibberish("aaaaaaabbb"))
        assertTrue(TextCleaner.looksLikeGibberish("hahahaha"))
    }

    @Test
    fun gibberishRepeatedWordsIsTrue() {
        assertTrue(TextCleaner.looksLikeGibberish("cat cat cat cat"))
        assertTrue(TextCleaner.looksLikeGibberish("the the The THE"))
    }

    @Test
    fun normalTextIsNotGibberish() {
        assertFalse(TextCleaner.looksLikeGibberish("the quick brown fox jumps"))
        assertFalse(TextCleaner.looksLikeGibberish("ok"))
        assertFalse(TextCleaner.looksLikeGibberish("hello world again now"))
    }

    @Test
    fun wordCounting() {
        assertEquals(0, TextCleaner.wordCount(""))
        assertEquals(3, TextCleaner.wordCount("hello world again"))
        assertEquals(6, TextCleaner.wordCount("hello, don't stop-it 42 now"))
        assertEquals(5, TextCleaner.wordCount("state-of-the-art yes"))
    }

    @Test
    fun classifyCommands() {
        assertEquals("undo", TextCleaner.classifyCommand("Delete That."))
        assertEquals("undo", TextCleaner.classifyCommand("undo that"))
        assertEquals("undo", TextCleaner.classifyCommand("UNDO LAST INSERTION !"))
        assertEquals("paragraph", TextCleaner.classifyCommand("new paragraph!"))
        assertEquals("newline", TextCleaner.classifyCommand("new line "))
        assertEquals("send", TextCleaner.classifyCommand("Send Message ?"))
        assertEquals("cancel", TextCleaner.classifyCommand("cancel"))
        assertEquals("cancel", TextCleaner.classifyCommand("STOP"))
        assertNull(TextCleaner.classifyCommand("type this out"))
        assertNull(TextCleaner.classifyCommand(""))
    }
}
