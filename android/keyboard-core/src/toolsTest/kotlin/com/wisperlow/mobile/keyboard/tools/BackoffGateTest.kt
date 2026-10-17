package com.wisperlow.mobile.keyboard.tools

import org.junit.Test
import kotlin.math.ln

class BackoffGateTest {
    @Test(expected = CompilerException::class)
    fun `v1 collapsed majority fails the gate`() {
        checkBackoffCollapse(floatArrayOf(ln(1e-6).toFloat(), ln(1e-6).toFloat(), 0f), "test")
    }

    @Test
    fun `sparse high count contexts and empty orders pass`() {
        checkBackoffCollapse(floatArrayOf(ln(1e-8).toFloat(), ln(0.3).toFloat(), 0f), "test")
        checkBackoffCollapse(floatArrayOf(), "test")
    }
}
