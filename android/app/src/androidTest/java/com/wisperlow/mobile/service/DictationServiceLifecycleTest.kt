package com.wisperlow.mobile.service

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wisperlow.mobile.MainActivity
import com.wisperlow.mobile.overlay.BubbleOverlay
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BubbleOverlayTestEntryPoint {
    fun bubbleOverlay(): BubbleOverlay
}

@RunWith(AndroidJUnit4::class)
class DictationServiceLifecycleTest {
    @Test
    fun serviceStartsIdleCancelsAStartAndStopsCleanly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(
            "Microphone permission is required",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        assumeTrue("Overlay permission is required", Settings.canDrawOverlays(context))

        val overlay = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BubbleOverlayTestEntryPoint::class.java,
        ).bubbleOverlay()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            DictationService.start(context)
            val ready = awaitState { DictationService.running.value && DictationService.phase.value is DictationPhase.Idle }
            assumeTrue("Dictation prerequisites are not installed", ready)
            instrumentation.waitForIdleSync()
            assertTrue("Dictation bubble was not shown", overlay.isVisible)

            instrumentation.runOnMainSync { overlay.onTap?.invoke() }
            val started = awaitState {
                DictationService.phase.value is DictationPhase.Initializing ||
                    DictationService.phase.value is DictationPhase.Listening
            }
            assertTrue("Dictation did not enter a start state", started)
            instrumentation.runOnMainSync { overlay.onCancelGesture?.invoke() }
            assertTrue("Cancellation did not return to idle", awaitState {
                DictationService.phase.value is DictationPhase.Idle
            })
            assertFalse("Cancelled start left dictation listening", DictationService.phase.value is DictationPhase.Listening)
        } finally {
            DictationService.stop(context)
            awaitState(timeoutMs = 5_000) { !DictationService.running.value }
            instrumentation.waitForIdleSync()
            assertFalse("Bubble remained visible after service stop", overlay.isVisible)
            scenario.close()
        }
    }

    private suspend fun awaitState(timeoutMs: Long = 10_000, predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            DictationService.phase.first { predicate() }
            true
        } ?: predicate()
}
