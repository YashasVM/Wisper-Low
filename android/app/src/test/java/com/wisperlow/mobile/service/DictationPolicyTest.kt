package com.wisperlow.mobile.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPolicyTest {
    @Test
    fun captureLimitIsInclusiveAndNeverAllowsUnboundedGrowth() {
        assertFalse(DictationPolicy.reachedCaptureLimit(DictationPolicy.MAX_CAPTURE_CHUNKS - 1))
        assertTrue(DictationPolicy.reachedCaptureLimit(DictationPolicy.MAX_CAPTURE_CHUNKS))
        assertTrue(DictationPolicy.reachedCaptureLimit(DictationPolicy.MAX_CAPTURE_CHUNKS + 1))
    }

    @Test
    fun transcriptionIsRejectedAfterCancelOrStateChange() {
        assertTrue(DictationPolicy.acceptsTranscription(4, 4, processing = true))
        assertFalse(DictationPolicy.acceptsTranscription(4, 5, processing = true))
        assertFalse(DictationPolicy.acceptsTranscription(4, 4, processing = false))
    }
}
