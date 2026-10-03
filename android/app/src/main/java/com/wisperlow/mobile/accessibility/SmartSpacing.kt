package com.wisperlow.mobile.accessibility

/** Fits dictated text between the characters around the cursor like a typist would. */
internal object SmartSpacing {
    private const val NO_SPACE_BEFORE = ".,!?;:)]}%'\"…"
    private const val NO_SPACE_AFTER = "([{\"'/@#-\n"
    private const val SENTENCE_END = ".!?…"

    fun fit(before: String, after: String, text: String): String {
        var result = text.trim()
        if (result.isEmpty()) return ""
        val previous = before.lastOrNull()
        if (startsSentence(before)) result = capitalized(result)
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

    private fun startsSentence(before: String): Boolean {
        val last = before.trimEnd(' ', '\t').lastOrNull() ?: return true
        return last == '\n' || last in SENTENCE_END
    }

    /** Only plain lowercase leading words; "iPhone" or "e.g." style tokens keep their case. */
    private fun capitalized(text: String): String {
        val word = text.takeWhile { !it.isWhitespace() }
        if (!word.first().isLowerCase() || word.drop(1).any { it.isUpperCase() || it == '.' || it == '@' || it == '/' }) {
            return text
        }
        return text.replaceFirstChar { it.uppercaseChar() }
    }
}
