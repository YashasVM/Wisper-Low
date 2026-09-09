package com.wisperlow.mobile.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTextTest {
    @Test
    fun insertsAtCursor() {
        assertEquals("hello world", SelectionText.replace("helloworld", 5, 5, " "))
    }

    @Test
    fun replacesReverseSelection() {
        assertEquals("aXYd", SelectionText.replace("abcd", 3, 1, "XY"))
    }
}
