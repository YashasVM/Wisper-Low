package com.wisperlow.mobile.accessibility

/** Pure checks that decide whether an insertion really happened, so we never insert twice. */
internal object InsertionVerifier {
    enum class Outcome { APPLIED, NOT_APPLIED }

    private val terminalPackages = setOf(
        "com.termux", "org.connectbot", "com.offsec.nethunter", "jackpal.androidterm",
        "org.woltage.irssiconnectbot", "com.sonelli.juicessh",
    )

    /**
     * [before] is the field text prior to the action, [after] the re-read text.
     * A field that ignored the action is unchanged; any change counts as applied.
     */
    fun check(before: String, after: String, @Suppress("UNUSED_PARAMETER") insertion: String): Outcome {
        // Any change means the field took the action (it may have reformatted our text);
        // pasting on top of that would insert the dictation twice.
        return if (after == before) Outcome.NOT_APPLIED else Outcome.APPLIED
    }

    /** Terminal emulators expose their whole buffer as text, so replacing it would wipe the screen. */
    fun isTerminal(packageName: String?, className: String?): Boolean =
        packageName in terminalPackages || className?.contains("Terminal", ignoreCase = true) == true
}
