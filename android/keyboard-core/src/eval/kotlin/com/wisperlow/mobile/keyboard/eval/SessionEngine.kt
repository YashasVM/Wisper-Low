package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.EditorField
import com.wisperlow.mobile.keyboard.KeyKind
import com.wisperlow.mobile.keyboard.Suggestions
import com.wisperlow.mobile.keyboard.TypingSession
import com.wisperlow.mobile.keyboard.WordEngine

/**
 * The contract the typing simulator drives every engine with, mirroring how the IME
 * service drives [TypingSession]: taps in board pixels are resolved to keys by the
 * engine, typed text and space and backspace are keys as the user pressed them, and
 * [strip] is what the suggestion bar would show right now, left slot first.
 *
 * The observations the simulator needs for honest metrics are part of the contract too:
 * [composing] is the word the engine currently holds before its dictionary sees it,
 * [revertible] is what a wrong autocorrection put back on offer, and [lastStripNanos]
 * times the strip. Keeping them on the interface is what lets one simulator drive the
 * legacy lexicon and the new engine without ever reaching into either.
 */
interface EngineUnderTest {
    /** Forgets the sentence so far, as when the cursor jumps to a fresh field. */
    fun reset()

    /** A touch at board-pixel ([x], [y]); the engine picks the key it hit. */
    fun tap(x: Float, y: Float)

    /** Types one character directly, bypassing hit-testing (long-press text, popups). */
    fun type(text: String)

    fun space()
    fun backspace()

    /** The current suggestion strip, left slot first. */
    fun strip(): List<String>

    /** Picks the [index]-th strip entry, as a tap on the suggestion would. */
    fun pick(index: Int)

    /** The field's full text. */
    fun text(): String

    /** The word being typed, still open for correction: the literal before the dictionary. */
    val composing: String

    /** What was typed before the last autocorrection, when a revert is on offer. */
    val revertible: String?

    /** How long the last [strip] call took, for the latency metrics. */
    val lastStripNanos: Long
}

/** An editor with a cursor and a composing span, like Android's BaseInputConnection. */
class EvalField : EditorField {
    val text = StringBuilder()
    var cursor = 0
    var composeStart = -1
    var composeEnd = -1

    fun reset() {
        text.clear()
        cursor = 0
        composeStart = -1
        composeEnd = -1
    }

    override fun before(count: Int) = text.substring((cursor - count).coerceAtLeast(0), cursor)
    override fun after(count: Int) = text.substring(cursor, (cursor + count).coerceAtMost(text.length))

    private fun replaceComposing(with: String) {
        val (s, e) = if (composeStart >= 0) composeStart to composeEnd else cursor to cursor
        text.replace(s, e, with)
        cursor = s + with.length
        composeStart = -1
        composeEnd = -1
    }

    override fun setComposing(new: String) {
        val start = if (composeStart >= 0) composeStart else cursor
        replaceComposing(new)
        if (new.isNotEmpty()) {
            composeStart = start
            composeEnd = start + new.length
        }
    }

    override fun commit(new: String) = replaceComposing(new)
    override fun finishComposing() {
        composeStart = -1
        composeEnd = -1
    }

    override fun deleteBefore(count: Int) {
        val n = count.coerceAtMost(cursor)
        text.delete(cursor - n, cursor)
        cursor -= n
    }

    override fun backspaceKey() = deleteBefore(1)
    override fun batch(block: () -> Unit) = block()
    override fun toString() = text.toString()
}

/**
 * The part every engine adapter shares: an [EvalField], a [TypingSession] over the
 * caller's [WordEngine], the EvalBoard hit test and the service's suggestion flow.
 * [BaselineEngine] and [SmartEngine] differ only in which engine they inject and, for
 * the new engine, [resolveTap] letting the engine read the touch before the session
 * sees the letter — exactly the order the IME service uses.
 */
open class SessionEngine(
    protected val lexicon: WordEngine,
) : EngineUnderTest {
    protected val field = EvalField()
    protected val session = TypingSession(field, { lexicon })

    private var shiftOnce = false
    private var shiftLocked = false

    private var stripNanos = 0L
    override val lastStripNanos: Long get() = stripNanos

    override val composing: String get() = session.composing
    override val revertible: String? get() = session.revertible

    override fun reset() {
        field.reset()
        session.reset()
        shiftOnce = false
        shiftLocked = false
    }

    override fun tap(x: Float, y: Float) {
        val key = EvalBoard.hit(x, y) ?: return
        when (key.kind) {
            KeyKind.CHAR -> {
                val upper = shiftOnce || shiftLocked || capsWanted()
                val landed = if (upper) key.text.uppercase() else key.text
                // The IME lets the engine read an eligible Latin tap before the session sees
                // it; every other character reaches the session exactly as picked.
                val typed = if (landed.length == 1 && landed[0].isLetter()) resolveTap(landed[0], x, y).toString() else landed
                session.type(typed, EvalBoard.units.touch(x, y))
                if (shiftOnce && !shiftLocked) shiftOnce = false
            }
            KeyKind.SPACE -> {
                session.space()
                shiftOnce = false
            }
            KeyKind.BACKSPACE -> session.backspace()
            KeyKind.ENTER -> {
                session.finishWord()
                shiftOnce = false
            }
            else -> Unit
        }
    }

    override fun type(text: String) {
        if (text.length == 1 && text[0].isLetter()) {
            val upper = shiftOnce || shiftLocked || capsWanted()
            session.type(if (upper) text.uppercase() else text)
            if (shiftOnce && !shiftLocked) shiftOnce = false
        } else {
            session.type(text)
        }
    }

    override fun space() {
        session.space()
        shiftOnce = false
    }

    override fun backspace() = session.backspace()

    override fun strip(): List<String> {
        val started = System.nanoTime()
        val word = session.composing
        val previous = session.previousWord()
        val revert = session.revertible
        val result = when {
            word.isNotEmpty() -> lexicon
                .suggest(word, previous, touches = session.touches)
                .let { if (session.autoCorrectsHere()) it else it.copy(autoCorrect = null) }
            revert != null -> Suggestions(listOf(revert) + lexicon.predict(previous, max = 2), revert = revert)
            else -> Suggestions(lexicon.predict(previous))
        }
        stripNanos = System.nanoTime() - started
        return result.words
    }

    override fun pick(index: Int) {
        val words = strip()
        if (index in words.indices) session.pick(words[index])
    }

    override fun text(): String = field.toString()

    /**
     * The character the engine reads for a tap on [landed] at board-pixel ([x], [y]).
     * The legacy lexicon types the key it was given; [SmartEngine] overrides this to
     * call the decoder's resolver with the original touch.
     */
    protected open fun resolveTap(landed: Char, x: Float, y: Float): Char = landed

    /**
     * Auto-capitalisation like the service's cursor caps: a capital when the cursor is
     * at the start of the field or right after a sentence end.
     */
    private fun capsWanted(): Boolean {
        if (session.composing.isNotEmpty()) return false
        val before = field.before(2)
        if (before.isEmpty()) return true
        return before.endsWith(". ") || before.endsWith("! ") || before.endsWith("? ") ||
            before.last() == '\n' || (before.length == 1 && before[0] in ".!?")
    }
}
