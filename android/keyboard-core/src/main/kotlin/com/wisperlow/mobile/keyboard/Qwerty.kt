package com.wisperlow.mobile.keyboard

import kotlin.math.abs
import kotlin.math.sqrt

/** Where a key was pressed, in key widths across and rows down the letter board. */
data class Touch(val x: Float, val y: Float)

/** Key positions on a QWERTY board, for judging which typos are likely. */
object Qwerty {
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val offsets = floatArrayOf(0f, 0.5f, 1.5f)
    private val position = HashMap<Char, Pair<Float, Int>>().apply {
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> put(ch, (c + offsets[r]) to r) } }
    }

    fun adjacent(a: Char, b: Char): Boolean {
        val pa = position[a] ?: return false
        val pb = position[b] ?: return false
        return abs(pa.second - pb.second) <= 1 && abs(pa.first - pb.first) <= 1.01f
    }

    /**
     * The board position of a press on [key], [dx] and [dy] being how far from the key's
     * centre it landed as a fraction of the key's size. Null for keys off the letter rows.
     */
    fun touch(key: Char, dx: Float, dy: Float): Touch? {
        val p = position[key.lowercaseChar()] ?: return null
        return Touch(p.first + 0.5f + dx, p.second + 0.5f + dy)
    }

    /**
     * How unlikely it is that a press at [t] was meant for [key]: cheap when it landed
     * near that key's edge, a full typo when it was far away.
     */
    fun slipCost(t: Touch, key: Char): Float {
        val p = position[key] ?: return 1f
        val dx = t.x - (p.first + 0.5f)
        val dy = t.y - (p.second + 0.5f)
        val d = sqrt(dx * dx + dy * dy)
        return ((d - 0.5f) / 0.8f).coerceIn(MIN_SLIP, 1f)
    }

    private const val MIN_SLIP = 0.25f
}
