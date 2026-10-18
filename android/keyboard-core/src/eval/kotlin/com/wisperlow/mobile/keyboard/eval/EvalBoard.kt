package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.FieldKind
import com.wisperlow.mobile.keyboard.Key
import com.wisperlow.mobile.keyboard.KeyKind
import com.wisperlow.mobile.keyboard.KeyboardGeometry
import com.wisperlow.mobile.keyboard.KeyboardLayout
import com.wisperlow.mobile.keyboard.Page
import com.wisperlow.mobile.keyboard.PlacedKey
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone board the simulator types on: the 411 dp phone at 2.625 px/dp, with the
 * 54 dp rows and 4 dp side padding used by KeyArea and KeyboardPanel, so a simulated
 * tap falls where a real thumb tap would. The letter page without a number row is the
 * board the engine is evaluated on.
 */
object EvalBoard {
    const val PX_PER_DP = 2.625f
    const val WIDTH_DP = 411f
    const val ROW_HEIGHT_DP = 54f
    const val SIDE_PADDING_DP = 4f

    val width = WIDTH_DP * PX_PER_DP
    val rowHeight = ROW_HEIGHT_DP * PX_PER_DP
    val side = SIDE_PADDING_DP * PX_PER_DP

    val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, numberRow = false)
    val placed: List<PlacedKey> = KeyboardGeometry.layout(rows, width, rowHeight, side)
    val units = KeyboardGeometry.units(placed, rowHeight, side)

    /** One-character keys by their lowercase text: every letter, plus "," and ".". */
    val byChar: Map<Char, PlacedKey> = placed
        .filter { it.key.kind == KeyKind.CHAR && it.key.text.length == 1 }
        .associateBy { it.key.text[0].lowercaseChar() }

    val space: PlacedKey = placed.first { it.key.kind == KeyKind.SPACE }

    /**
     * Canonical letter-key centres in key units, the grid the [KeyboardEngine]'s spatial
     * model is built over. This is the same conversion [KeyboardGeometry.letterCenters]
     * does for the real board, so the eval engine sees the coordinates the IME feeds it.
     */
    val letterCenters: List<KeyCenter> = KeyboardGeometry.letterCenters(placed, units)

    /** Letter-key centres by lowercase character, for the resolver's "landed" argument. */
    val centerByChar: Map<Char, KeyCenter> = letterCenters.associateBy { it.char }

    /**
     * KeyArea's hit test, unchanged: the row the tap fell in, the key under it, else the
     * nearest centre in that row. Shared by every adapter and by the simulator's raw
     * typed-input control probe, so all three resolve a tap exactly the way the app does.
     */
    fun hit(x: Float, y: Float): Key? {
        if (placed.isEmpty()) return null
        val row = (y / rowHeight).toInt().coerceIn(0, rows.lastIndex)
        val inRow = placed.filter { (it.top / rowHeight).roundToInt() == row }
        return (inRow.firstOrNull { x >= it.left && x < it.right } ?: inRow.minByOrNull { abs(it.centerX - x) })?.key
    }
}
