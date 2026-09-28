package com.wisperlow.mobile.service

import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wisperlow.mobile.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationServiceLifecycleTest {
    @Test
    fun serviceStartsAndStopsCleanly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Microphone and overlay access are required", DictationService.canStart(context))
        assumeTrue("Overlay permission is required", Settings.canDrawOverlays(context))

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            instrumentation.runOnMainSync { DictationService.start(context) }
            val started = withTimeoutOrNull(10_000) { DictationService.running.first { it } } ?: false
            assertTrue("Bubble service did not start", started)
        } finally {
            DictationService.stop(context)
            val stopped = withTimeoutOrNull(5_000) { DictationService.running.first { !it } } != null
            assertTrue("Bubble service did not stop", stopped)
            scenario.close()
        }
    }
}
