package com.wisperlow.mobile.keyboard

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
}
