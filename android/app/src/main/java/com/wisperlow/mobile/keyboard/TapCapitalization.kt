package com.wisperlow.mobile.keyboard

/** Consumes one-shot shift immediately, even before a rollover edit has recomposed the UI. */
internal class TapCapitalization {
    private var observed = ShiftState.OFF
    private var consumed = false

    fun take(shift: ShiftState): Boolean {
        if (shift != observed) {
            observed = shift
            consumed = false
        }
        return when (shift) {
            ShiftState.OFF -> false
            ShiftState.LOCKED -> true
            ShiftState.ONCE -> (!consumed).also { consumed = true }
        }
    }
}
