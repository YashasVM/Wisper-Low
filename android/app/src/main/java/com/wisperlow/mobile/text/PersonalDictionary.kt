package com.wisperlow.mobile.text

object PersonalDictionary {

    private data class Entry(val key: String, val value: String)

    fun apply(text: String, dictionary: Map<String, String>): String {
        if (text.isEmpty() || dictionary.isEmpty()) return text
        val entries = dictionary.entries
            .mapNotNull { (key, value) -> key.trim().takeIf(String::isNotEmpty)?.let { Entry(it, value) } }
            .sortedWith(compareByDescending<Entry> { it.key.length }.thenBy { it.key.lowercase() })
        if (entries.isEmpty()) return text

        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val match = entries.firstNotNullOfOrNull { entry ->
                matchEnd(text, index, entry.key)?.let { end -> entry to end }
            }
            if (match == null) {
                val codePoint = text.codePointAt(index)
                result.appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            } else {
                result.append(match.first.value)
                index = match.second
            }
        }
        return result.toString()
    }

    private fun matchEnd(text: String, start: Int, key: String): Int? {
        var input = start
        var pattern = 0
        while (pattern < key.length) {
            if (key[pattern].isWhitespace()) {
                if (input >= text.length || !text[input].isWhitespace()) return null
                while (pattern < key.length && key[pattern].isWhitespace()) pattern++
                while (input < text.length && text[input].isWhitespace()) input++
                continue
            }
            if (input >= text.length || !text.regionMatches(input, key, pattern, 1, ignoreCase = true)) return null
            input++
            pattern++
        }
        val first = key.firstOrNull { !it.isWhitespace() } ?: return null
        val last = key.lastOrNull { !it.isWhitespace() } ?: return null
        if (isWord(first) && start > 0 && isWord(text.codePointBefore(start))) return null
        if (isWord(last) && input < text.length && isWord(text.codePointAt(input))) return null
        return input
    }

    private fun isWord(character: Char): Boolean = character == '_' || character.isLetterOrDigit()
    private fun isWord(codePoint: Int): Boolean = codePoint == '_'.code || Character.isLetterOrDigit(codePoint)
}
