package com.wisperlow.mobile.accessibility

/** Fits dictated text between the characters around the cursor like a typist would. */
internal object SmartSpacing {
    private const val NO_SPACE_BEFORE = ".,!?;:)]}%'\"…"
    private const val NO_SPACE_AFTER = "([{\"'/@#-\n"

    fun fit(before: String, after: String, text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return ""
        val previous = before.lastOrNull()
        if (previous != null && !previous.isWhitespace() && previous !in NO_SPACE_AFTER &&
            result.first() !in NO_SPACE_BEFORE
        ) {
            result = " $result"
        }
        val next = after.firstOrNull()
        if (next != null && !next.isWhitespace() && next !in NO_SPACE_BEFORE) {
            result = "$result "
        }
        return result
    }
}
