package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import java.io.File

/** One token of a sentence: its letters and apostrophes plus trailing punctuation. */
data class Tok(val word: String, val punct: String) {
    /** The word's engine key: lowercased, apostrophes gone. */
    val key: String get() = Lexicon.key(word)
    val hasWord: Boolean get() = word.any { it.isLetter() }
}

/** Evaluation-only data: sentences one per line, and wrong<TAB>right misspellings. */
object EvalData {
    /** Every *.txt under [dir], sorted by file name for stable runs. */
    fun sentences(dir: File): List<String> {
        val files = dir.listFiles { f -> f.isFile && f.extension == "txt" } ?: return emptyList()
        return files.sortedBy { it.name }.flatMap { f ->
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
    }

    /**
     * Every *misspellings*.tsv: "wrong<TAB>right" pairs, de-duplicated and sorted by
     * (wrong, right). The old HashSet iteration order made the pair list — and any
     * fingerprint over it — vary between runs and JVMs; sorting pins it down.
     */
    fun misspellings(dir: File): List<Pair<String, String>> {
        val files = dir.listFiles { f -> f.isFile && f.extension == "tsv" && f.name.contains("misspellings") } ?: return emptyList()
        val seen = HashSet<Pair<String, String>>()
        files.sortedBy { it.name }.forEach { f ->
            f.readLines().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val wrong = line.substring(0, tab).trim()
                val right = line.substring(tab + 1).trim()
                if (wrong.isNotEmpty() && right.isNotEmpty() && ' ' !in right) seen += wrong to right
            }
        }
        return seen.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** Splits a sentence into word and trailing-punctuation tokens. */
    fun tokens(sentence: String): List<Tok> = sentence
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { t ->
            val end = t.indexOfLast { it.isLetter() || Lexicon.isApostrophe(it) }
            if (end < 0) Tok("", t) else Tok(t.substring(0, end + 1), t.substring(end + 1))
        }
}
