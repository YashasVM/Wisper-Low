package com.wisperlow.mobile.text

object PersonalDictionary {

    fun apply(text: String, dictionary: Map<String, String>): String {
        if (text.isEmpty() || dictionary.isEmpty()) return text
        val lookup = HashMap<String, String>(dictionary.size)
        for ((key, value) in dictionary) lookup[key.lowercase()] = value
        return text.split(' ').joinToString(" ") { token ->
            var start = 0
            var end = token.length
            while (start < end && !token[start].isLetterOrDigit()) start++
            while (end > start && !token[end - 1].isLetterOrDigit()) end--
            val replacement = lookup[token.substring(start, end).lowercase()] ?: return@joinToString token
            token.substring(0, start) + replacement + token.substring(end)
        }
    }
}
