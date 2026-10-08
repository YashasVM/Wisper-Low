package com.wisperlow.mobile.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSpacingTest {
    @Test
    fun emptyFieldGetsTextAsIs() {
        assertEquals("Hello there.", SmartSpacing.fit("", "", "  Hello there. "))
    }

    @Test
    fun appendingAfterAWordAddsOneSpace() {
        assertEquals(" and more", SmartSpacing.fit("Some text", "", "and more"))
        assertEquals("and more", SmartSpacing.fit("Some text ", "", "and more"))
    }

    @Test
    fun insertingBeforeAWordSeparatesIt() {
        assertEquals(" middle ", SmartSpacing.fit("start", "end", "middle"))
    }

    @Test
    fun punctuationHugsTheSurroundingText() {
        assertEquals(", right", SmartSpacing.fit("Okay", "", ", right"))
        assertEquals("quoted", SmartSpacing.fit("say \"", "\"", "quoted"))
        assertEquals("Next line", SmartSpacing.fit("first\n", "", "next line"))
    }

    @Test
    fun blankDictationInsertsNothing() {
        assertEquals("", SmartSpacing.fit("a", "b", "   "))
    }

    @Test
    fun capitalizesAtFieldStartAndAfterSentenceEnd() {
        assertEquals("Hello there", SmartSpacing.fit("", "", "hello there"))
        assertEquals(" Next one", SmartSpacing.fit("Done.", "", "next one"))
        assertEquals("Next one", SmartSpacing.fit("Done.\n", "", "next one"))
        assertEquals("Why", SmartSpacing.fit("Done? ", "", "why"))
    }

    @Test
    fun keepsCaseMidSentenceAndForMixedCaseWords() {
        assertEquals(" and more", SmartSpacing.fit("Some text", "", "and more"))
        assertEquals("iPhone is out", SmartSpacing.fit("", "", "iPhone is out"))
        assertEquals(" and", SmartSpacing.fit("Hi,", "", "and"))
    }

    @Test
    fun neverStacksSpacesOrPunctuation() {
        assertEquals("ok", SmartSpacing.fit("Hi  ", "", "ok"))
        assertEquals(".", SmartSpacing.fit("Hi", "", "."))
    }
}
