package com.wisperlow.mobile.text

object TextCleaner {

    private val horizontalWhitespaceRun = Regex("[^\\S\n]+")
    private val spaceBeforePunct = Regex("\\s+([,.!?;:])")
    private val firstPersonPronoun = Regex("\\bi\\b")

    private val conservativeGrammarRules = listOf(
        Regex("\\bi has\\b", RegexOption.IGNORE_CASE) to "I have",
        Regex("\\bi is\\b", RegexOption.IGNORE_CASE) to "I am",
        Regex("\\byou is\\b", RegexOption.IGNORE_CASE) to "you are",
        Regex("\\b(we|they) is\\b", RegexOption.IGNORE_CASE) to "$1 are",
        Regex("\\b(he|she|it) don't\\b", RegexOption.IGNORE_CASE) to "$1 doesn't",
        Regex("\\bdidn't went\\b", RegexOption.IGNORE_CASE) to "didn't go",
        Regex("\\bdidn't saw\\b", RegexOption.IGNORE_CASE) to "didn't see",
        Regex("\\bdidn't came\\b", RegexOption.IGNORE_CASE) to "didn't come",
        Regex("\\b(can|could) able to\\b", RegexOption.IGNORE_CASE) to "$1",
        Regex("\\bdiscuss about\\b", RegexOption.IGNORE_CASE) to "discuss",
        Regex("\\breturn back\\b", RegexOption.IGNORE_CASE) to "return",
        Regex("\\bmore better\\b", RegexOption.IGNORE_CASE) to "better",
        Regex("\\ban (working|local|model|transcript|smooth|reliable|fast|good)\\b", RegexOption.IGNORE_CASE) to "a $1",
        Regex("\\ba (app|error|issue|idea|example|audio)\\b", RegexOption.IGNORE_CASE) to "an $1",
    )

    private val filler = Regex(
        "\\b(um+|uh+|erm|ah+|basically|actually)\\b[,\\s]*",
        RegexOption.IGNORE_CASE
    )

    private val spokenPunctuation = listOf(
        "new paragraph" to "\n\n",
        "new line" to "\n",
        "comma" to ",",
        "period" to ".",
        "full stop" to ".",
        "question mark" to "?",
        "exclamation mark" to "!",
        "colon" to ":",
        "semicolon" to ";"
    )

    private val commandMap = mapOf(
        "delete that" to "undo",
        "undo that" to "undo",
        "undo last insertion" to "undo",
        "new paragraph" to "paragraph",
        "new line" to "newline",
        "send message" to "send",
        "cancel" to "cancel",
        "stop" to "cancel"
    )

    private val wordToken = Regex("[A-Za-z0-9']+")

    fun clean(raw: String): String {
        var text = normalizeSpaces(raw)
        text = removeRepetitions(text)
        text = filler.replace(text) { "" }
        for ((phrase, symbol) in spokenPunctuation) {
            text = Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
                .replace(text) { symbol }
        }
        text = firstPersonPronoun.replace(text, "I")
        for ((pattern, replacement) in conservativeGrammarRules) {
            text = pattern.replace(text, replacement)
        }
        text = text.replace(" \n", "\n").replace("\n ", "\n")
        text = normalizeSpaces(text)
        if (text.isEmpty()) return ""
        text = capitalizeSentenceStarts(text)
        if (text.last() !in ".!?:;\n") text += "."
        return text
    }

    fun classifyCommand(raw: String): String? {
        val normalized = raw.trim().lowercase().trimEnd(' ', '.', '!', '?')
        return commandMap[normalized]
    }

    fun looksLikeGibberish(raw: String): Boolean {
        val compact = raw.filter { it in 'a'..'z' || it in 'A'..'Z' }
        if (compact.length < 2) return true
        if (compact.length > 6 && compact.lowercase().toSet().size <= 2) return true
        val words = raw.split(' ').filter { it.isNotEmpty() }
        return words.size >= 4 && words.map { it.lowercase() }.toSet().size <= 2
    }

    fun wordCount(raw: String): Int = wordToken.findAll(raw).count()

    private fun normalizeSpaces(text: String): String {
        val collapsed = horizontalWhitespaceRun.replace(text, " ")
        val joined = collapsed.split("\n").joinToString("\n") { it.trim() }.trim()
        return spaceBeforePunct.replace(joined) { it.groupValues[1] }
    }

    private fun removeRepetitions(text: String): String {
        val cleaned = ArrayList<String>()
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            val low = word.lowercase()
            if (cleaned.isNotEmpty() && cleaned.last().lowercase() == low && word.length > 2) continue
            if (cleaned.size >= 2 &&
                cleaned[cleaned.size - 1].lowercase() == low &&
                cleaned[cleaned.size - 2].lowercase() == low
            ) continue
            cleaned.add(word)
        }
        return cleaned.joinToString(" ")
    }

    private fun capitalizeSentenceStarts(text: String): String {
        val result = StringBuilder(text.length)
        var capitalizeNext = true
        for (character in text) {
            val output = if (capitalizeNext && character.isLetter()) {
                character.uppercaseChar()
            } else {
                character
            }
            result.append(output)
            when {
                character.isLetterOrDigit() -> capitalizeNext = false
                character == '.' || character == '!' || character == '?' || character == '\n' -> {
                    capitalizeNext = true
                }
            }
        }
        return result.toString()
    }
}
