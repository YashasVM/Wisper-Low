package com.wisperlow.mobile.service

import com.wisperlow.mobile.settings.InsertionPreference
import com.wisperlow.mobile.text.PolishResult
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

    @Test
    fun quickInsertRequiresExplicitPreferenceAndSafeResult() {
        assertTrue(
            DictationPolicy.shouldQuickInsert(
                InsertionPreference.QUICK_INSERT,
                PolishResult.Unchanged("original"),
            ),
        )
        assertTrue(
            DictationPolicy.shouldQuickInsert(
                InsertionPreference.QUICK_INSERT,
                PolishResult.Polished("raw", "Polished.", requiresReview = false),
            ),
        )
        assertFalse(
            DictationPolicy.shouldQuickInsert(
                InsertionPreference.REVIEW_FIRST,
                PolishResult.Polished("raw", "Polished.", requiresReview = false),
            ),
        )
        assertFalse(
            DictationPolicy.shouldQuickInsert(
                InsertionPreference.QUICK_INSERT,
                PolishResult.Polished("raw", "Changed 5 to 6.", requiresReview = true),
            ),
        )
        assertFalse(
            DictationPolicy.shouldQuickInsert(
                InsertionPreference.QUICK_INSERT,
                PolishResult.Unavailable("raw", "model missing"),
            ),
        )
    }
}
