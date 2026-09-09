package com.wisperlow.mobile.service

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wisperlow.mobile.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationServiceLifecycleTest {
    @Test
    fun serviceStartsIdleAndStopsCleanly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(
            "Microphone permission is required",
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        assumeTrue("Overlay permission is required", Settings.canDrawOverlays(context))

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            DictationService.start(context)
            val ready = awaitState { DictationService.running.value && DictationService.phase.value is DictationPhase.Idle }
            assertTrue("Service failed to become ready with installed prerequisites", ready)
            instrumentation.waitForIdleSync()
            assertFalse("Service started recording without a tap", DictationService.phase.value is DictationPhase.Listening)
        } finally {
            DictationService.stop(context)
            assertTrue("Service did not stop", awaitState(timeoutMs = 5_000) { !DictationService.running.value })
            scenario.close()
        }
    }

    private suspend fun awaitState(timeoutMs: Long = 10_000, predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            combine(DictationService.phase, DictationService.running) { _, _ -> predicate() }.first { it }
            true
        } ?: predicate()
}
