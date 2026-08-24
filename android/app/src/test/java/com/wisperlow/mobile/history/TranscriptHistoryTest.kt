package com.wisperlow.mobile.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptHistoryTest {

    @Test
    fun codecRoundTripsTextAndHistoryKeepsNewestHundred() {
        val original = TranscriptEntry("entry-1", 123L, "first\tline\nsecond line")
        assertEquals(original, TranscriptLineCodec.decode(TranscriptLineCodec.encode(original)))
        assertNull(TranscriptLineCodec.decode("not a transcript"))
        assertEquals(3, original.wordCount)
        assertNull(TranscriptLineCodec.decode("entry-1\t123\t2\tnot-base64"))

        val entries = (101 downTo 1).map { TranscriptEntry("$it", it.toLong(), "word") }
        val bounded = TranscriptHistory.bound(entries)
        assertEquals(100, bounded.size)
        assertEquals("101", bounded.first().id)
        assertEquals("2", bounded.last().id)
    }
}
