package com.wisperlow.mobile.dictation

import com.wisperlow.mobile.stt.SttEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTuningTest {
    @Test fun leadStartNeverNegative() {
        assertEquals(0, DictationTuning.leadStart(100))
        assertEquals(16_000 * 3 - 12_800, DictationTuning.leadStart(16_000 * 3))
    }

    @Test fun forcesBoundaryOnlyOnLongSegments() {
        assertFalse(DictationTuning.shouldForceBoundary(16_000 * 19, 0))
        assertTrue(DictationTuning.shouldForceBoundary(16_000 * 21, 0))
        assertFalse(DictationTuning.shouldForceBoundary(16_000 * 21, 16_000 * 5))
    }

    @Test fun trimReleaseRules() {
        assertTrue(DictationTuning.shouldReleaseOnTrim(15, true))
        assertFalse(DictationTuning.shouldReleaseOnTrim(20, true))
        assertTrue(DictationTuning.shouldReleaseOnTrim(20, false))
        assertTrue(DictationTuning.shouldReleaseOnTrim(40, true))
        assertFalse(DictationTuning.shouldReleaseOnTrim(5, false))
    }

    @Test fun threadsScaleWithCoresWithinBounds() {
        assertEquals(2, SttEngine.threadsFor(2))
        assertEquals(2, SttEngine.threadsFor(4))
        assertEquals(3, SttEngine.threadsFor(6))
        assertEquals(4, SttEngine.threadsFor(16))
    }
}
