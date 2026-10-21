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
 * typos; backspace right after a fix puts back what was typed. Going back into a
 * word and rewriting it teaches the lexicon that fix.
 */
class TypingSession(
    private val field: EditorField,
    private val lexicon: () -> WordEngine?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var options = TypingOptions()

    private val word = StringBuilder()
    /** Where each character of [word] was pressed, if known. */
    private val presses = ArrayList<Touch?>()
    private var undo: Undo? = null
    private var lastSpaceAt = 0L

    /** The last word committed: what was typed and what went into the field. */
    private var last: Committed? = null

    /** What was first typed for the word now being rewritten, after going back into it. */
    private var rewriting: String? = null

    private class Undo(val typed: String, val corrected: String, val trailing: String) {
        val replacement get() = corrected + trailing
    }

    private class Committed(val typed: String, val out: String)

    /** The word being typed, still open for correction. */
    val composing: String get() = word.toString()

    /** Touch positions for [composing], one per character. */
    val touches: List<Touch?> get() = presses.toList()

    /** The word as typed before the autocorrection just made; picking it puts it back. */
    val revertible: String? get() = undo?.typed?.takeIf { word.isEmpty() }

    /** Called when the cursor moved or the field changed without us. */
    fun reset() {
        if (word.isNotEmpty()) field.finishComposing()
        clearWord()
        forgetHistory()
    }

    /**
     * Forgets what was just typed and corrected, e.g. when the cursor moved elsewhere,
     * so undo and fix learning never act on text the user did not just write.
     */
    fun forgetHistory() {
        // Only consecutive manual space taps may end a sentence.
        lastSpaceAt = 0L
        undo = null
        last = null
        rewriting = null
    }

    /** Types a key's text: letters build the current word, anything else ends it. */
    fun type(text: String, touch: Touch? = null) {
        if (text.isEmpty()) return
        val c = text.singleOrNull()
        if (c != null && options.suggestions && isWordChar(c, word.isNotEmpty())) {
            undo = null
            word.append(c)
            presses.add(touch)
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
            undo = corrected?.let { Undo(it.first, it.second, text) }
        }
    }

    fun space() {
        val now = clock()
        if (word.isNotEmpty()) {
            field.batch {
                val corrected = endWord(autoCorrect = true, trailing = " ")
                undo = corrected?.let { Undo(it.first, it.second, " ") }
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
                setWord(u.typed)
                field.setComposing(u.typed)
            }
            if (options.learn) lexicon()?.trust(u.typed)
            // If they go on to change it themselves, that is the fix they wanted.
            rewriting = u.typed
            last = null
            return
        }
        if (word.isNotEmpty()) {
            word.setLength(word.length - 1)
            presses.removeAt(presses.lastIndex)
            field.setComposing(word.toString())
            return
        }
        field.backspaceKey()
        recomposeWordBeforeCursor()
    }

    /** Deletes the word before the cursor (held backspace). */
    fun deleteWord() {
        undo = null
        // Wiping the word gives up on rewriting it.
        rewriting = null
        if (word.isNotEmpty()) {
            clearWord()
            field.setComposing("")
            return
        }
        last = null
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

    /** Replaces the current word with a strip suggestion, puts back an autocorrected word, or adds a predicted word. */
    fun pick(suggestion: String) {
        // Only consecutive manual space taps may end a sentence.
        lastSpaceAt = 0L
        val u = undo
        undo = null
        if (u != null && word.isEmpty() && suggestion == u.typed && field.before(u.replacement.length) == u.replacement) {
            field.batch {
                field.deleteBefore(u.replacement.length)
                field.commit(u.typed + u.trailing)
            }
            if (options.learn) lexicon()?.trust(u.typed)
            last = Committed(u.typed, u.typed)
            return
        }
        field.batch {
            if (word.isNotEmpty()) {
                val typed = word.toString()
                val previous = previousWord()
                clearWord()
                field.commit("$suggestion ")
                // Picking exactly what was typed over the correction means "I meant that".
                if (options.learn && suggestion == typed) lexicon()?.trust(typed)
                rewriting?.let { learnFix(it, suggestion) }
                rewriting = null
                last = Committed(typed, suggestion)
                learn(suggestion, previous)
            } else {
                val previous = previousWord()
                rewriting = null
                last = null
                val before = field.before(1)
                val lead = if (before.isNotEmpty() && !before[0].isWhitespace()) " " else ""
                field.commit("$lead$suggestion ")
                learn(suggestion, previous)
            }
        }
    }

    /** Inserts text that is not typed key by key (emoji, clipboard), ending the current word as typed. */
    fun insert(text: String) {
        // Only consecutive manual space taps may end a sentence.
        lastSpaceAt = 0L
        undo = null
        field.batch {
            endWord(autoCorrect = false, trailing = "")
            field.commit(text)
        }
        // What follows came from elsewhere; it has no typing history to learn from.
        last = null
        rewriting = null
    }

    /** Ends the current word as typed, e.g. before Enter or dictation. */
    fun finishWord() {
        // Only consecutive manual space taps may end a sentence.
        lastSpaceAt = 0L
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
        val touches = presses.toList()
        val fix = if (autoCorrect && autoCorrectsHere()) lexicon()?.suggest(typed, previous, touches = touches)?.autoCorrect else null
        clearWord()
        val out = fix ?: typed
        field.commit(out + trailing)
        learn(out, previous)
        rewriting?.let { learnFix(it, out) }
        rewriting = null
        last = Committed(typed, out)
        return if (fix != null && fix != typed) typed to out else null
    }

    /**
     * Whether the word being typed may be autocorrected here. Not straight after
     * characters that make it part of a handle, tag, path or code ("@name", "#tag",
     * "file.ts", "my_var").
     */
    fun autoCorrectsHere(): Boolean {
        if (!options.autoCorrect) return false
        val lead = field.before(word.length + 1).dropLast(word.length)
        return lead.isEmpty() || lead[0] !in CODE_LEAD
    }

    /** The user went back into [typed] and made it [intended]: worth remembering if it looks like a fix. */
    private fun learnFix(typed: String, intended: String) {
        if (!options.learn) return
        val a = Lexicon.key(typed)
        val b = Lexicon.key(intended)
        if (a.length < 2 || a == b || b.isEmpty() || a[0] != b[0]) return
        // Adding or trimming an ending ("test" to "tests", "run" to "running") is editing, not fixing.
        if (b.startsWith(a) || a.startsWith(b)) return
        // A slip ("teh" to "the") or a shortcut spelled out ("tmrw" to "tomorrow").
        val related = Lexicon.distance(a, b, FIX_DISTANCE) <= FIX_DISTANCE || isAbbreviation(a, b)
        if (related) lexicon()?.learnFix(typed, intended)
    }

    private fun setWord(text: String) {
        clearWord()
        word.append(text)
        repeat(text.length) { presses.add(null) }
    }

    private fun clearWord() {
        word.clear()
        presses.clear()
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
        val l = last
        rewriting = if (l != null && l.out == tail) l.typed else null
        last = null
        field.batch {
            field.deleteBefore(tail.length)
            setWord(tail)
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
        private const val FIX_DISTANCE = 2.5f
        private const val CODE_LEAD = "@#/\\_.:~\$`<>=|"
        private const val CORRECTING_PUNCTUATION = ".,!?;:)\""
        private val HUGGING_PUNCTUATION = setOf(".", ",", "!", "?", ";", ":")
        private val LAST_WORD = Regex("([\\p{L}][\\p{L}\\p{M}'’]*)\\s*$")

        /** "tmrw" in "tomorrow": every letter of [short], in order, starting the same way. */
        fun isAbbreviation(short: String, long: String): Boolean {
            if (short.length >= long.length) return false
            var i = 0
            for (c in long) if (i < short.length && c == short[i]) i++
            return i == short.length
        }

        /**
         * True for a character that stays inside the word being typed. A dependent vowel or
         * virama only counts after a letter, so "नमस्ते" is one word while a stray mark is not.
         */
        fun isWordChar(c: Char, inWord: Boolean): Boolean =
            c.isLetterOrDigit() || (inWord && (c == '\'' || c == '’' || Lexicon.isCombiningMark(c)))
    }
}
