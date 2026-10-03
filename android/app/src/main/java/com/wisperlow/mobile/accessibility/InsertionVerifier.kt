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
     * A field that ignored the action is unchanged; one that reformatted our text
     * still changed and contains the dictated words, which counts as applied.
     */
    fun check(before: String, after: String, insertion: String): Outcome {
        if (after == before) return Outcome.NOT_APPLIED
        val core = insertion.trim()
        return if (core.isNotEmpty() && after.contains(core)) Outcome.APPLIED else Outcome.NOT_APPLIED
    }

    /** Terminal emulators expose their whole buffer as text, so replacing it would wipe the screen. */
    fun isTerminal(packageName: String?, className: String?): Boolean =
        packageName in terminalPackages || className?.contains("Terminal", ignoreCase = true) == true
}
