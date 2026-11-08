package com.wisperlow.mobile.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {
    @Test
    fun `clipboard validation preserves exact text for history and suggestion limits`() {
        val text = "  indented\ttext\n\n"
        assertEquals(text, clipboardText(text, 2_000))
        assertEquals(text, clipboardText(text, ClipboardHistory.MAX_LENGTH))
        assertNull(clipboardText(null, 2_000))
        assertNull(clipboardText(" \t\n", 2_000))
        assertNull(clipboardText("x".repeat(2_000) + " ", 2_000))
        assertEquals("x".repeat(2_000), clipboardText("x".repeat(2_000), 2_000))
    }

    @Test
    fun `history retains leading spaces and trailing newlines`() {
        val history = ClipboardHistory()
        val text = "  indented\ttext\n\n"
        history.add(text, 1_000)
        assertEquals(listOf(ClipEntry(text, 1_000)), history.entries)
    }

    @Test
    fun `history deduplicates exact text and retains pins`() {
        val history = ClipboardHistory()
        history.add("text", 1_000)
        history.add(" text\n", 2_000)
        history.togglePin(" text\n")
        history.add(" text\n", 3_000)
        assertEquals(listOf(ClipEntry(" text\n", 3_000, true), ClipEntry("text", 1_000)), history.entries)
        history.remove(" text\n")
        assertEquals(listOf(ClipEntry("text", 1_000)), history.entries)
    }

    @Test
    fun `history rejects blank and oversized raw text`() {
        val history = ClipboardHistory()
        history.add(" \t\n", 1_000)
        history.add("x".repeat(ClipboardHistory.MAX_LENGTH) + " ", 1_000)
        assertTrue(history.entries.isEmpty())
        history.add("x".repeat(ClipboardHistory.MAX_LENGTH), 1_000)
        assertEquals(ClipboardHistory.MAX_LENGTH, history.entries.single().text.length)
    }
}
