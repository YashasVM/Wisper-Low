package com.wisperlow.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    @Test
    fun accessibilityIsOptionalWhenCoreSetupIsReady() {
        val state = SetupState(
            microphoneReady = true,
            overlayReady = true,
            accessibilityReady = false,
            modelReady = true,
        )

        assertTrue(state.isReady)
        assertTrue(state.completedCount < SetupState.TOTAL_STEPS)
    }

    @Test
    fun corePermissionOrModelStillBlocksDictation() {
        assertFalse(
            SetupState(
                microphoneReady = false,
                overlayReady = true,
                accessibilityReady = true,
                modelReady = true,
            ).isReady,
        )
        assertFalse(
            SetupState(
                microphoneReady = true,
                overlayReady = true,
                accessibilityReady = true,
                modelReady = false,
            ).isReady,
        )
    }
}
