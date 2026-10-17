package com.wisperlow.mobile.keyboard.emoji

import java.text.Normalizer

/**
 * Emoji for a typed word, built from the generated CLDR keyword lines
 * (`keyword<TAB>emoji emoji ...`; `#` lines are comments).
 *
 * An exact keyword wins, then a plain English stripping ("hearts" -> "heart",
 * "laughing" -> "laugh"), then a prefix, so emoji keep appearing while the word
 * is still being typed. Keys are normalised the way the asset builder
 * normalises them, so Hindi and Kannada conjuncts match what the user types.
 */
class EmojiIndex(lines: Sequence<String>) {
    private val byKeyword = HashMap<String, MutableList<String>>()

    /** The keyword keys ascending, so a prefix is one contiguous slice found by bisection. */
    private val sortedKeys: Array<String>

    init {
        for (line in lines) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) continue
            val tab = text.indexOf('\t')
            if (tab <= 0) continue
            val key = normalise(text.substring(0, tab))
            if (key.isEmpty()) continue
            val emoji = byKeyword.getOrPut(key) { mutableListOf() }
            for (symbol in text.substring(tab + 1).split(' ')) {
                if (symbol.isNotEmpty() && symbol !in emoji) emoji += symbol
            }
        }
        sortedKeys = byKeyword.keys.toTypedArray().also { it.sort() }
    }

    /** Up to [max] emoji for [word], most relevant first; empty when none is known. */
    fun forWord(word: String, max: Int = 3): List<String> {
        if (max <= 0) return emptyList()
        val key = normalise(word)
        if (key.isEmpty()) return emptyList()
        byKeyword[key]?.let { return it.take(max) }
        for (form in strippedForms(key)) byKeyword[form]?.let { return it.take(max) }
        if (key.length >= MIN_PREFIX) return prefixed(key, max)
        return emptyList()
    }

    /** The English endings worth undoing to reach a CLDR keyword, best guess first. */
    private fun strippedForms(word: String): List<String> = buildList {
        if (word.length > 5 && word.endsWith("ing")) add(word.dropLast(3))
        if (word.length > 4 && word.endsWith("ies")) add(word.dropLast(3) + "y")
        if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) add(word.dropLast(1))
        if (word.length > 4 && word.endsWith("es")) add(word.dropLast(2))
    }

    /** Emoji for the keywords starting with [prefix], gathered keyword by keyword. */
    private fun prefixed(prefix: String, max: Int): List<String> {
        var low = 0
        var high = sortedKeys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedKeys[mid] < prefix) low = mid + 1 else high = mid
        }
        val found = ArrayList<String>()
        for (i in low until sortedKeys.size) {
            val key = sortedKeys[i]
            if (!key.startsWith(prefix)) break
            for (symbol in byKeyword.getValue(key)) {
                if (symbol !in found) found += symbol
                if (found.size >= max) return found
            }
        }
        return found
    }

    companion object {
        /** "sea" or "car" prefix-match dozens of keywords; too early to be useful. */
        private const val MIN_PREFIX = 4

        private fun normalise(word: String): String =
            Normalizer.normalize(word.trim().lowercase(), Normalizer.Form.NFC)
    }
}
