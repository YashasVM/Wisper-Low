package com.wisperlow.mobile.accessibility

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
        assertEquals("next line", SmartSpacing.fit("first\n", "", "next line"))
    }

    @Test
    fun blankDictationInsertsNothing() {
        assertEquals("", SmartSpacing.fit("a", "b", "   "))
    }
}
