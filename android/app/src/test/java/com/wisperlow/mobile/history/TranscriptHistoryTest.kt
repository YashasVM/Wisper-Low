package com.wisperlow.mobile.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class TranscriptHistoryTest {

    @Test
    fun codecRoundTripsTextAndHistoryKeepsNewestHundred() {
        val original = TranscriptEntry("entry-1", 123L, "first\tline\nsecond line")
        assertEquals(original, TranscriptLineCodec.decode(TranscriptLineCodec.encode(original)))
        assertEquals("v2", TranscriptLineCodec.encode(original).substringBefore('\t'))
        assertNull(TranscriptLineCodec.decode("not a transcript"))
        assertEquals(4, original.wordCount)
        assertNull(TranscriptLineCodec.decode("entry-1\t123\t2\tnot-base64"))

        val entries = (101 downTo 1).map { TranscriptEntry("$it", it.toLong(), "word") }
        val bounded = TranscriptHistory.bound(entries)
        assertEquals(100, bounded.size)
        assertEquals("101", bounded.first().id)
        assertEquals("2", bounded.last().id)
    }

    @Test
    fun versionedCodecRetainsOriginalAndFinalText() {
        val entry = TranscriptEntry(
            id = "entry-2",
            timestampMillis = 456L,
            text = "Thursday at five.",
            originalText = "Tuesday sorry Thursday at five",
            finalText = "Thursday at five.",
        )

        val decoded = TranscriptLineCodec.decode(TranscriptLineCodec.encode(entry))

        assertEquals(entry, decoded)
        assertEquals("Tuesday sorry Thursday at five", decoded?.originalText)
        assertEquals("Thursday at five.", decoded?.finalText)
    }

    @Test
    fun legacyThreeColumnRecordsReadAsOriginalAndFinalText() {
        val encoded = Base64.getEncoder().encodeToString("legacy text".toByteArray())

        val decoded = TranscriptLineCodec.decode("legacy-id\t42\t$encoded")

        assertEquals("legacy-id", decoded?.id)
        assertEquals("legacy text", decoded?.text)
        assertEquals("legacy text", decoded?.originalText)
        assertEquals("legacy text", decoded?.finalText)
    }
}
