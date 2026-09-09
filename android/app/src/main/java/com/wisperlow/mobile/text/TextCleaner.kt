package com.wisperlow.mobile.text

object TextCleaner {

    private val horizontalWhitespaceRun = Regex("[^\\S\n]+")
    private val spaceBeforePunct = Regex("\\s+([,.!?;:])")

    private val wordToken = Regex("[\\p{L}\\p{M}\\p{N}']+")

    /** Preserve the recognizer's words, case and punctuation; only normalize spacing. */
    fun clean(raw: String): String = normalizeSpaces(raw)

    fun looksLikeGibberish(raw: String): Boolean {
        // Heuristics based on English letters or repetition discard valid dictation
        // (numbers, names, multilingual speech, and deliberate repeated words).
        return raw.none { it.isLetterOrDigit() }
    }

    fun wordCount(raw: String): Int = wordToken.findAll(raw).count()

    private fun normalizeSpaces(text: String): String {
        val collapsed = horizontalWhitespaceRun.replace(text, " ")
        val joined = collapsed.split("\n").joinToString("\n") { it.trim() }.trim()
        return spaceBeforePunct.replace(joined) { it.groupValues[1] }
    }
}
