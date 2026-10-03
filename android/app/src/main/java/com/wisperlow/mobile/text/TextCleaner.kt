package com.wisperlow.mobile.text

object TextCleaner {

    private val horizontalWhitespaceRun = Regex("[^\\S\n]+")
    private val spaceBeforePunct = Regex("\\s+([,.!?;:])")

    private val wordToken = Regex("[\\p{L}\\p{M}\\p{N}']+")

    private val fillers = setOf("um", "uh", "umm", "uhh", "er", "erm", "uh-huh", "hmm", "mm")
    private val stutterWords = setOf(
        "i", "a", "the", "to", "and", "we", "you", "it", "of", "in", "my", "so", "but", "is", "are", "for",
    )

    /** Words that mean the next filler/punctuation word is being talked about, not dictated. */
    private val mentionCues = setOf("word", "words", "quote", "literal", "literally", "say", "says", "the", "a", "this", "that", "of")
    private val nounLeaders = setOf("a", "the", "this", "that", "of", "full", "decimal", "my", "each", "every", "no", "word", "and",
        // Nouns that take "period" as their head: "grace period", "time period".
        "time", "grace", "trial", "waiting", "same", "last", "first", "long", "short", "whole", "given", "certain",
    )
    private val verbFollowers = setOf("is", "are", "was", "were", "of", "means", "has")

    private val spoken: List<Pair<List<String>, String>> = listOf(
        listOf("question", "mark") to "?",
        listOf("exclamation", "point") to "!",
        listOf("exclamation", "mark") to "!",
        listOf("new", "paragraph") to "\n\n",
        listOf("new", "line") to "\n",
        listOf("full", "stop") to ".",
        listOf("semicolon") to ";",
        listOf("comma") to ",",
        listOf("period") to ".",
    )

    /** Normalizes spacing, drops fillers and stutters, and turns spoken punctuation into symbols. */
    fun clean(raw: String): String = normalizeSpaces(applySpoken(removeFillers(raw)))

    fun looksLikeGibberish(raw: String): Boolean {
        // Heuristics based on English letters or repetition discard valid dictation
        // (numbers, names, multilingual speech, and deliberate repeated words).
        return raw.none { it.isLetterOrDigit() }
    }

    fun wordCount(raw: String): Int = wordToken.findAll(raw).count()

    private fun bare(token: String) = token.trim(',', '.', '!', '?', ';', ':').lowercase()

    private fun removeFillers(text: String): String {
        val lines = text.split("\n").map { line ->
            val tokens = line.split(Regex("[^\\S\n]+")).filter { it.isNotEmpty() }
            val out = ArrayList<String>()
            for ((i, token) in tokens.withIndex()) {
                val word = bare(token)
                val prev = tokens.getOrNull(i - 1)?.let(::bare)
                if (word in fillers && prev !in mentionCues && !isQuoted(tokens, i)) {
                    // Keep trailing punctuation the filler carried ("uh," between clauses).
                    val punct = token.takeLastWhile { it in ",.!?;:" }
                    if (punct.isNotEmpty() && out.isNotEmpty() && out.last().last() !in ",.!?;:") out[out.lastIndex] += punct
                    continue
                }
                if (word in stutterWords && prev == word && token.last() !in ",.!?;:") continue
                out += token
            }
            out.joinToString(" ")
        }
        return lines.joinToString("\n")
    }

    /** True between a spoken "quote" and the matching "end quote". */
    private fun isQuoted(tokens: List<String>, i: Int): Boolean {
        val before = tokens.take(i).map(::bare)
        val open = before.lastIndexOf("quote")
        if (open < 0) return false
        val closed = (open until before.size - 1).any { before[it] == "end" && before[it + 1] == "quote" }
        return !closed
    }

    private fun applySpoken(text: String): String = text.split("\n").joinToString("\n") { applySpokenLine(it) }

    private fun applySpokenLine(text: String): String {
        val out = StringBuilder()
        val tokens = text.split(Regex("[^\\S\n]+")).filter { it.isNotEmpty() }
        var i = 0
        var capitalizeNext = false
        while (i < tokens.size) {
            val match = spoken.firstOrNull { (words, _) ->
                words.indices.all { k -> tokens.getOrNull(i + k)?.let(::bare) == words[k] && tokens[i + k].all { c -> c.isLetter() } }
            }
            val prev = tokens.getOrNull(i - 1)?.let(::bare)
            val next = tokens.getOrNull(i + (match?.first?.size ?: 1))?.let(::bare)
            val afterMatch = i + (match?.first?.size ?: 1)
            // "period" is also a common noun ("Jurassic period was long"), so it only
            // becomes punctuation at the end of the utterance or before a line break.
            val isStop = match?.second == "."
            val stopAllowed = !isStop || afterMatch >= tokens.size || (
                tokens.getOrNull(afterMatch)?.let(::bare) == "new" &&
                    tokens.getOrNull(afterMatch + 1)?.let(::bare) in setOf("line", "paragraph")
                )
            val usable = match != null && stopAllowed && out.isNotEmpty() && prev !in nounLeaders && next !in verbFollowers &&
                (match.second.startsWith("\n") || prev != null)
            if (match == null || !usable) {
                if (out.isNotEmpty() && !out.endsWith("\n")) out.append(' ')
                out.append(if (capitalizeNext) capitalizeWord(tokens[i]) else tokens[i])
                capitalizeNext = false
                i++
                continue
            }
            val symbol = match.second
            while (out.endsWith(" ")) out.setLength(out.length - 1)
            out.append(symbol)
            capitalizeNext = symbol in listOf(".", "?", "!") || symbol.startsWith("\n")
            i += match.first.size
        }
        return out.toString()
    }

    private fun capitalizeWord(word: String): String =
        if (word.firstOrNull()?.isLowerCase() == true && word.drop(1).none { it.isUpperCase() }) {
            word.replaceFirstChar { it.uppercaseChar() }
        } else {
            word
        }

    private fun normalizeSpaces(text: String): String {
        val collapsed = horizontalWhitespaceRun.replace(text, " ")
        // Keep a trailing line break (a spoken "new line" at the end); drop other edge whitespace.
        val joined = collapsed.split("\n").joinToString("\n") { it.trim() }.trimStart().trimEnd(' ', '\t')
        return spaceBeforePunct.replace(joined) { it.groupValues[1] }
    }
}
