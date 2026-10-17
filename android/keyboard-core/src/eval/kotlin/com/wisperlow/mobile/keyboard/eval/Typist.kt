package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import java.util.Random

/*
 * Seeded typist model.
 *
 * A letter tap lands on the key's centre plus a fixed per-typist aim offset plus
 * independent Gaussian noise per axis, with [TypistPreset.sigma] as the standard
 * deviation in key units (one standard key width horizontally, one row vertically).
 * The engine resolves each tap by hit-testing, so noise turns into wrong keys exactly
 * the way it does on a real board.
 *
 * Shift is perfect: capitalisation goes through the engine's auto-capitalise, and the
 * typist never touches the shift key. Spaces and punctuation taps land on centre:
 * this model measures word typing, not aim on the spacebar. Characters that are not
 * one tap away (apostrophes, "!", "?") come out of the long-press and popup flow,
 * which is precise, so the engine sees them typed exactly.
 *
 * On top of aim, each word may take one small mistake - a dropped letter, a doubled
 * letter, or a swapped pair - with per-preset rates, and it may be replaced wholesale
 * by a misspelling from the evaluation's misspelling list with the preset's
 * [TypistPreset.misspelling] rate. Everything is seeded per sentence, so two engines
 * over the same (preset, seed) see byte-identical input.
 */

/**
 * Noise strength per preset; sigma in key units, mistake rates as probabilities.
 *
 * [EXACT] is the true zero-noise control the simulator needs to separate engine
 * behaviour from touch error: every tap lands on its key's centre and no word takes a
 * mistake. [PRECISE] is NOT that control — it still draws Gaussian noise at sigma 0.15,
 * so a PRECISE run can misland on a neighbouring key.
 */
enum class TypistPreset(
    val id: String,
    val sigma: Float,
    val omission: Double,
    val insertion: Double,
    val transposition: Double,
    val misspelling: Double,
) {
    EXACT("exact", 0.0f, 0.0, 0.0, 0.0, 0.0),
    PRECISE("precise", 0.15f, 0.0, 0.0, 0.0, 0.0),
    NORMAL("normal", 0.25f, 0.01, 0.005, 0.01, 0.05),
    SLOPPY("sloppy", 0.35f, 0.02, 0.01, 0.02, 0.10),
    VERY_SLOPPY("very-sloppy", 0.45f, 0.03, 0.015, 0.03, 0.15),
}

/** One planned input event for the engine: a hit-tested tap, or precise typed text. */
sealed interface KeyEvent {
    data class Tap(val x: Float, val y: Float) : KeyEvent
    data class Typed(val text: String) : KeyEvent
}

/** One planned token: its intended word, the events that type it, and its trailing punctuation. */
data class PlannedToken(
    val intended: String,
    val typed: String,
    /** True when the typed form differs from the intended word. */
    val noisy: Boolean,
    val events: List<KeyEvent>,
    val punct: String,
    /** Events for the trailing punctuation, always exact. */
    val punctEvents: List<KeyEvent>,
)

/**
 * Turns intended tokens into key events for one (preset, seed). The aim offset is drawn
 * once per sentence run, the way one person's thumb bias holds within a message.
 */
class Typist(private val preset: TypistPreset) {
    private var aimX = 0f
    private var aimY = 0f

    private fun newAim(rnd: Random) {
        // A small constant thumb bias, within a tenth of a key in each direction.
        aimX = (rnd.nextDouble() * 0.2 - 0.1).toFloat()
        aimY = (rnd.nextDouble() * 0.2 - 0.1).toFloat()
    }

    /** Plans the whole sentence; [intended] must be EvalData's token list of it. */
    fun plan(intended: List<Tok>, misspellings: Map<String, String>, seed: Long): List<PlannedToken> {
        val rnd = Random(seed)
        newAim(rnd)
        return intended.map { tok ->
            val typed = if (rnd.nextDouble() < preset.misspelling) {
                misspellings[tok.key] ?: tok.word
            } else {
                noisy(tok.word, rnd)
            }
            PlannedToken(
                intended = tok.word,
                typed = typed,
                noisy = Lexicon.key(typed) != tok.key,
                events = charEvents(typed, rnd),
                punct = tok.punct,
                punctEvents = punctEvents(tok.punct),
            )
        }
    }

    /** A dropped letter, a doubled letter, or a swapped pair; never the first letter. */
    private fun noisy(word: String, rnd: Random): String {
        val letters = word.mapIndexed { i, c -> if (c.isLetter()) i else -1 }.filter { it >= 0 }
        if (letters.size < 3) return word
        var s = word
        when {
            rnd.nextDouble() < preset.omission -> {
                val at = letters[(rnd.nextDouble() * (letters.size - 1)).toInt().coerceIn(1, letters.lastIndex)]
                s = StringBuilder(s).removeRange(at, at + 1).toString()
            }
            rnd.nextDouble() < preset.insertion -> {
                val at = letters[(rnd.nextDouble() * letters.size).toInt().coerceIn(1, letters.lastIndex)]
                s = StringBuilder(s).insert(at, s[at]).toString()
            }
            rnd.nextDouble() < preset.transposition -> {
                val options = letters.indices.filter { it > 0 && it < letters.lastIndex }
                val i = if (options.isEmpty()) return s else options[rnd.nextInt(options.size)]
                val a = letters[i]
                val b = letters[i + 1]
                if (b != a + 1) return s
                s = s.substring(0, a) + s[b] + s[a] + s.substring(b + 1)
            }
        }
        return s
    }

    /** Taps for letters with aim and noise; precise typed text for popup characters. */
    private fun charEvents(text: String, rnd: Random): List<KeyEvent> = text.map { c ->
        val key = EvalBoard.byChar[c.lowercaseChar()]
        if (c.isLetter() && key != null) {
            val dx = aimX + (rnd.nextGaussian() * preset.sigma).toFloat()
            val dy = aimY + (rnd.nextGaussian() * preset.sigma).toFloat()
            KeyEvent.Tap(
                key.centerX + dx * EvalBoard.units.keyWidth,
                key.centerY + dy * EvalBoard.units.rowHeight,
            )
        } else {
            KeyEvent.Typed(c.toString())
        }
    }

    /** Punctuation taps land on centre; popup characters ("!", "?") are typed precisely. */
    private fun punctEvents(punct: String): List<KeyEvent> = punct.map { c ->
        val key = EvalBoard.byChar[c]
        if (key != null) KeyEvent.Tap(key.centerX, key.centerY) else KeyEvent.Typed(c.toString())
    }
}

/** Baseline keystrokes one intended token costs, word plus punctuation plus space. */
fun keystrokesFor(tok: Tok): Int = tok.word.length + tok.punct.length + 1

/** Trims edge punctuation and compares like the engine does, apostrophes gone. */
fun normKey(token: String): String = Lexicon.key(token.trim { !it.isLetter() && !Lexicon.isApostrophe(it) })

/** The same trim, keeping apostrophes, for exact (spelling-preserving) comparison. */
fun normExact(token: String): String = token.trim { !it.isLetter() && !Lexicon.isApostrophe(it) }.lowercase()
