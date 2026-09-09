package com.wisperlow.mobile

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun launchAndRecreateExposeHomeContent() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        assertHomeText(instrumentation.uiAutomation.rootInActiveWindow)

        scenario.recreate()
        instrumentation.waitForIdleSync()
        assertHomeText(instrumentation.uiAutomation.rootInActiveWindow)
        scenario.close()
    }

    private fun assertHomeText(root: AccessibilityNodeInfo?) {
        assertNotNull("MainActivity did not expose an accessibility root", root)
        val matches = root?.findAccessibilityNodeInfosByText("Speak naturally.") ?: emptyList()
        assertNotNull("Home content was not exposed through accessibility", matches.firstOrNull())
    }
}
