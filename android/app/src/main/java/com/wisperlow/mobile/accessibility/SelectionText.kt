package com.wisperlow.mobile.accessibility

internal object SelectionText {
    fun replace(current: String, start: Int, end: Int, inserted: String): String {
        val first = start.coerceIn(0, current.length)
        val second = end.coerceIn(0, current.length)
        val lower = minOf(first, second)
        val upper = maxOf(first, second)
        return current.replaceRange(lower, upper, inserted)
    }
}
