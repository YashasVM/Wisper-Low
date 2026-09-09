package com.wisperlow.mobile

import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun launchAndRecreateExposeHomeContent() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            repeat(2) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertTrue("Compose content was not attached", content.childCount > 0)
                    assertTrue("Activity window was not visible", activity.window.decorView.isShown)
                }
                if (it == 0) scenario.recreate()
            }
        } finally {
            scenario.close()
        }
    }
}
