package com.wisperlow.mobile.keyboard

import com.wisperlow.mobile.keyboard.spatial.KeyCenter

/** A key laid out on the board, in pixels from the board's top-left corner. */
data class PlacedKey(val key: Key, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Where every key sits; shared by the key grid, touch decoding and the typing simulator. */
object KeyboardGeometry {
    /**
     * Lays [rows] out across [width] pixels, each row [rowHeight] tall, with [side]
     * pixels of padding at both ends. A row's inset shifts its first key right.
     */
    fun layout(rows: List<KeyRow>, width: Float, rowHeight: Float, side: Float): List<PlacedKey> {
        val out = ArrayList<PlacedKey>()
        rows.forEachIndexed { r, row ->
            val unit = (width - side * 2) / row.units
            var x = side + row.inset * unit
            for (key in row.keys) {
                val w = key.width * unit
                out += PlacedKey(key, x, r * rowHeight, x + w, (r + 1) * rowHeight)
                x += w
            }
        }
        return out
    }

    /** Key units for a laid-out board: the usual letter key's width, the row height and the side padding. */
    fun units(placed: List<PlacedKey>, rowHeight: Float, side: Float): BoardUnits {
        val widths = placed.filter { isLetterKey(it.key) }.map { it.width }.sorted()
        val keyWidth = widths.getOrNull(widths.size / 2) ?: placed.firstOrNull()?.width ?: 1f
        return BoardUnits(keyWidth, rowHeight, side)
    }

    /** Centres of the single-letter keys, in key units, for the spatial model. */
    fun letterCenters(placed: List<PlacedKey>, units: BoardUnits): List<KeyCenter> = placed
        .filter { isLetterKey(it.key) }
        .map { units.touch(it.centerX, it.centerY).let { t -> KeyCenter(it.key.text[0].lowercaseChar(), t.x, t.y) } }

    private fun isLetterKey(key: Key) = key.kind == KeyKind.CHAR && key.text.length == 1 && key.text[0].isLetter()
}

/**
 * Converts board pixels to key units, the coordinates [Touch] uses: x in standard key
 * widths from the left padding, y in rows from the top of the board.
 */
data class BoardUnits(val keyWidth: Float, val rowHeight: Float, val side: Float) {
    fun touch(px: Float, py: Float): Touch = Touch((px - side) / keyWidth, py / rowHeight)
}
