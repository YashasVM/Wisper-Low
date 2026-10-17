package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.EditorField
import com.wisperlow.mobile.keyboard.KeyKind
import com.wisperlow.mobile.keyboard.Lexicon
import com.wisperlow.mobile.keyboard.Suggestions
import com.wisperlow.mobile.keyboard.TypingSession
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The contract the typing simulator drives every engine with, mirroring how the IME
 * service drives [TypingSession]: taps in board pixels are resolved to keys by the
 * engine, typed text and space and backspace are keys as the user pressed them, and
 * [strip] is what the suggestion bar would show right now, left slot first.
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
}

/**
 * Today's engine, wired the way the IME runs it: a [Lexicon] over en.txt and
 * en_modern.txt merged like LexiconRepository, a [TypingSession] over an in-memory
 * field, and the service's suggestion flow. Hit-testing mirrors KeyArea's touch
 * handler and the board matches EvalBoard, so taps land where the app would resolve
 * them. Auto-capitalisation stands in for the service's cursor-caps shift, which the
 * perfect-shift typist relies on.
 */
class BaselineEngine(assets: File) : EngineUnderTest {
    private val lexicon = loadLexicon(assets)

    private val field = EvalField()
    private val session = TypingSession(field, { lexicon })

    private var shiftOnce = false
    private var shiftLocked = false

    /** How long the last [strip] call took, for the latency metrics. */
    var lastStripNanos: Long = 0L
        private set

    /** The word currently being typed, for the simulator's strip logic. */
    val composing: String get() = session.composing

    /** What was typed before the last autocorrection, when a revert is on offer. */
    val revertible: String? get() = session.revertible

    override fun reset() {
        field.reset()
        session.reset()
        shiftOnce = false
        shiftLocked = false
    }

    override fun tap(x: Float, y: Float) {
        val key = hit(x, y) ?: return
        when (key.kind) {
            KeyKind.CHAR -> {
                val upper = shiftOnce || shiftLocked || capsWanted()
                session.type(if (upper) key.text.uppercase() else key.text, EvalBoard.units.touch(x, y))
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
        lastStripNanos = System.nanoTime() - started
        return result.words
    }

    override fun pick(index: Int) {
        val words = strip()
        if (index in words.indices) session.pick(words[index])
    }

    override fun text(): String = field.toString()

    /** KeyArea's hit test: the row the tap fell in, the key under it, else the nearest centre. */
    private fun hit(x: Float, y: Float): com.wisperlow.mobile.keyboard.Key? {
        if (EvalBoard.placed.isEmpty()) return null
        val row = (y / EvalBoard.rowHeight).toInt().coerceIn(0, EvalBoard.rows.lastIndex)
        val inRow = EvalBoard.placed.filter { (it.top / EvalBoard.rowHeight).roundToInt() == row }
        return (inRow.firstOrNull { x >= it.left && x < it.right } ?: inRow.minByOrNull { abs(it.centerX - x) })?.key
    }

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

    /** Reads and merges the word lists exactly as LexiconRepository does. */
    private fun loadLexicon(assets: File): Lexicon {
        val base = File(assets, "dict/en.txt")
        val modern = File(assets, "dict/en_modern.txt")
        require(base.isFile && modern.isFile) { "word lists missing under $assets" }
        val entries = base.bufferedReader().useLines { Lexicon.parse(it) }
        return Lexicon(Lexicon.merge(entries, modern.bufferedReader().useLines { Lexicon.parse(it) }))
    }
}

/** An editor with a cursor and a composing span, like Android's BaseInputConnection. */
private class EvalField : EditorField {
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
