package com.wisperlow.mobile.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TapCapitalizationTest {
    @Test
    fun `overlapping letters consume one-shot shift once before recomposition`() {
        val state = TapCapitalization()
        assertEquals(listOf(true, false, false), List(3) { state.take(ShiftState.ONCE) })
    }

    @Test
    fun `caps lock survives rollover and a new one-shot shift can be used`() {
        val state = TapCapitalization()
        assertEquals(listOf(true, true, false, true, false), listOf(
            state.take(ShiftState.LOCKED),
            state.take(ShiftState.LOCKED),
            state.take(ShiftState.OFF),
            state.take(ShiftState.ONCE),
            state.take(ShiftState.ONCE),
        ))
    }
}
