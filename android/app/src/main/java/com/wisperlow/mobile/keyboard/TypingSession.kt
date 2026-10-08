package com.wisperlow.mobile.keyboard

/** The slice of an editor the typing logic needs; backed by an InputConnection in the keyboard. */
interface EditorField {
    fun before(count: Int): String
    fun after(count: Int): String
    /** Replaces the composing (underlined) text, or inserts it at the cursor. */
    fun setComposing(text: String)
    /** Replaces the composing text, if any, with [text] and moves the cursor after it. */
    fun commit(text: String)
    fun finishComposing()
    fun deleteBefore(count: Int)
    /** A real backspace key press, so the app handles selections and emoji itself. */
    fun backspaceKey()
    /** Groups edits so the app redraws once. */
    fun batch(block: () -> Unit)
}

/** Which typing aids apply in the focused field. */
data class TypingOptions(
    val suggestions: Boolean = true,
    val autoCorrect: Boolean = true,
    val learn: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
)

/**
 * Turns key presses into edits the way a phone keyboard does: the word being typed
 * stays composing so it can be corrected; space and punctuation commit it, fixing
 * typos; backspace right after a fix puts back what was typed.
 */
class TypingSession(
    private val field: EditorField,
    private val lexicon: () -> Lexicon?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var options = TypingOptions()

    private val word = StringBuilder()
    private var undo: Undo? = null
    private var lastSpaceAt = 0L

    private class Undo(val typed: String, val replacement: String)

    /** The word being typed, still open for correction. */
    val composing: String get() = word.toString()

    /** Called when the cursor moved or the field changed without us. */
    fun reset() {
        if (word.isNotEmpty()) field.finishComposing()
        word.clear()
        undo = null
    }

    /** Types a key's text: letters build the current word, anything else ends it. */
    fun type(text: String) {
        if (text.isEmpty()) return
        val c = text.singleOrNull()
        if (c != null && options.suggestions && isWordChar(c, word.isNotEmpty())) {
            undo = null
            word.append(c)
            field.setComposing(word.toString())
            return
        }
        field.batch {
            val corrected = endWord(autoCorrect = text in CORRECTING_PUNCTUATION, trailing = "")
            // "word ," reads wrong: closing punctuation hugs the word before it.
            if (text in HUGGING_PUNCTUATION && field.before(2).let { it.length == 2 && it[1] == ' ' && isWordChar(it[0], true) }) {
                field.deleteBefore(1)
            }
            field.commit(text)
            undo = corrected?.let { Undo(it.first, it.second + text) }
        }
    }

    fun space() {
        val now = clock()
        if (word.isNotEmpty()) {
            field.batch {
                val corrected = endWord(autoCorrect = true, trailing = " ")
                undo = corrected?.let { Undo(it.first, it.second + " ") }
            }
        } else {
            undo = null
            val before = field.before(2)
            // Two quick spaces after a word end the sentence, as on any phone keyboard.
            if (options.doubleSpacePeriod && now - lastSpaceAt < DOUBLE_SPACE_MS && before.length == 2 &&
                before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in "\")'")
            ) {
                field.batch {
                    field.deleteBefore(1)
                    field.commit(". ")
                }
                lastSpaceAt = 0L
                return
            }
            field.commit(" ")
        }
        lastSpaceAt = now
    }

    fun backspace() {
        val u = undo
        undo = null
        if (u != null && word.isEmpty() && field.before(u.replacement.length) == u.replacement) {
            // Undo the autocorrection and keep what the user actually typed from now on.
            field.batch {
                field.deleteBefore(u.replacement.length)
                word.append(u.typed)
                field.setComposing(u.typed)
            }
            lexicon()?.trust(u.typed)
            return
        }
        if (word.isNotEmpty()) {
            word.setLength(word.length - 1)
            field.setComposing(word.toString())
            return
        }
        field.backspaceKey()
        recomposeWordBeforeCursor()
    }

    /** Deletes the word before the cursor (held backspace). */
    fun deleteWord() {
        undo = null
        if (word.isNotEmpty()) {
            word.clear()
            field.setComposing("")
            return
        }
        val before = field.before(MAX_WORD * 2)
        if (before.isEmpty()) {
            field.backspaceKey()
            return
        }
        val trimmed = before.trimEnd()
        var start = trimmed.length
        while (start > 0 && isWordChar(trimmed[start - 1], true)) start--
        // A run of punctuation or emoji counts as one "word".
        if (start == trimmed.length) start = (trimmed.length - 1).coerceAtLeast(0)
        field.deleteBefore((before.length - start).coerceAtLeast(1))
    }

    /** Replaces the current word with a strip suggestion, or adds a predicted word. */
    fun pick(suggestion: String) {
        undo = null
        field.batch {
            if (word.isNotEmpty()) {
                val typed = word.toString()
                val previous = previousWord()
                word.clear()
                field.commit("$suggestion ")
                // Picking exactly what was typed over the correction means "I meant that".
                if (suggestion == typed) lexicon()?.trust(typed)
                learn(suggestion, previous)
            } else {
                val previous = previousWord()
                val before = field.before(1)
                val lead = if (before.isNotEmpty() && !before[0].isWhitespace()) " " else ""
                field.commit("$lead$suggestion ")
                learn(suggestion, previous)
            }
        }
    }

    /** Inserts text that is not typed key by key (emoji, clipboard), ending the current word as typed. */
    fun insert(text: String) {
        undo = null
        field.batch {
            endWord(autoCorrect = false, trailing = "")
            field.commit(text)
        }
    }

    /** Ends the current word as typed, e.g. before Enter or dictation. */
    fun finishWord() {
        undo = null
        if (word.isNotEmpty()) endWord(autoCorrect = false, trailing = "")
    }

    /** The last full word before the current one, for next-word predictions. */
    fun previousWord(): String? {
        val before = field.before(PREVIOUS_CONTEXT).dropLast(word.length)
        val match = LAST_WORD.find(before) ?: return null
        return match.groupValues[1]
    }

    /** Commits the open word, autocorrected if wanted; returns (typed, corrected) when it changed. */
    private fun endWord(autoCorrect: Boolean, trailing: String): Pair<String, String>? {
        if (word.isEmpty()) {
            if (trailing.isNotEmpty()) field.commit(trailing)
            return null
        }
        val typed = word.toString()
        val previous = previousWord()
        word.clear()
        val fix = if (autoCorrect && options.autoCorrect) lexicon()?.suggest(typed, previous)?.autoCorrect else null
        val out = fix ?: typed
        field.commit(out + trailing)
        learn(out, previous)
        return if (fix != null && fix != typed) typed to out else null
    }

    /** After backspacing into a word, make it editable again so its suggestions come back. */
    private fun recomposeWordBeforeCursor() {
        if (!options.suggestions) return
        val after = field.after(1)
        if (after.isNotEmpty() && isWordChar(after[0], true)) return
        val before = field.before(MAX_WORD + 1)
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1], true)) start--
        val tail = before.substring(start)
        if (tail.isEmpty() || tail.length > MAX_WORD || tail.none { it.isLetter() }) return
        field.batch {
            field.deleteBefore(tail.length)
            word.append(tail)
            field.setComposing(tail)
        }
    }

    private fun learn(word: String, previous: String?) {
        if (options.learn) lexicon()?.learn(word, previous)
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 450L
        private const val MAX_WORD = 32
        private const val PREVIOUS_CONTEXT = 48
        private const val CORRECTING_PUNCTUATION = ".,!?;:)\""
        private val HUGGING_PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")
        private val LAST_WORD = Regex("([\\p{L}][\\p{L}'’]*)\\s*$")

        fun isWordChar(c: Char, inWord: Boolean): Boolean =
            c.isLetterOrDigit() || (inWord && (c == '\'' || c == '’'))
    }
}
