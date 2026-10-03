package com.wisperlow.mobile.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechDetectorTest {
    // 32 ms windows, matching the production 512-sample Silero window.
    private fun feed(detector: SpeechDetector, probability: Float, windows: Int): List<VadEvent> =
        (0 until windows).mapNotNull { detector.step(probability) }

    @Test
    fun speechThenSilenceEmitsStartPauseAndEndInOrder() {
        val detector = SpeechDetector().apply { reset(endSilenceMs = 1_600L) }

        val events = feed(detector, 0.9f, 30) + feed(detector, 0.05f, 60)

        assertEquals(listOf(VadEvent.SpeechStart, VadEvent.Pause, VadEvent.EndOfSpeech), events)
    }

    @Test
    fun shortClicksAreNotSpeech() {
        val detector = SpeechDetector().apply { reset(endSilenceMs = 1_000L) }

        val events = feed(detector, 0.9f, 2) + feed(detector, 0.05f, 100)

        assertTrue(events.isEmpty())
        assertFalse(detector.heardSpeech)
    }

    @Test
    fun manualModeNeverEndsOnItsOwn() {
        val detector = SpeechDetector().apply { reset(endSilenceMs = 0L) }

        val events = feed(detector, 0.9f, 30) + feed(detector, 0.05f, 500)

        assertEquals(listOf(VadEvent.SpeechStart, VadEvent.Pause), events)
    }

    @Test
    fun resumedSpeechAllowsAnotherPause() {
        val detector = SpeechDetector().apply { reset(endSilenceMs = 4_000L) }

        val events = feed(detector, 0.9f, 20) + feed(detector, 0.05f, 20) +
            feed(detector, 0.9f, 20) + feed(detector, 0.05f, 20)

        assertEquals(listOf(VadEvent.SpeechStart, VadEvent.Pause, VadEvent.Pause), events)
    }

    @Test
    fun uncertainProbabilitiesDoNotCountAsSilence() {
        val detector = SpeechDetector().apply { reset(endSilenceMs = 1_000L) }

        val events = feed(detector, 0.9f, 20) + feed(detector, 0.5f, 200)

        assertEquals(listOf(VadEvent.SpeechStart), events)
    }
}
