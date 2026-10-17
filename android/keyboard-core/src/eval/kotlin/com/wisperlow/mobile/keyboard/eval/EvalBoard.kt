package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.FieldKind
import com.wisperlow.mobile.keyboard.KeyKind
import com.wisperlow.mobile.keyboard.KeyboardGeometry
import com.wisperlow.mobile.keyboard.KeyboardLayout
import com.wisperlow.mobile.keyboard.Page
import com.wisperlow.mobile.keyboard.PlacedKey

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
}
