package com.wisperlow.mobile.keyboard.lm

/**
 * The words an engine knows, by dense id. Surfaces keep their usual casing and
 * apostrophes ("I", "don't", "iPhone"); keys are what typed letters are matched against.
 */
class Vocabulary(surfaces: List<String>) {
    private val surfaces: Array<String> = surfaces.toTypedArray()
    private val keys: Array<String> = Array(this.surfaces.size) { keyOf(this.surfaces[it]) }
    private val bySurface = HashMap<String, Int>(this.surfaces.size * 2)
    private val byKey = HashMap<String, IntArray>(this.surfaces.size * 2)

    init {
        this.surfaces.forEachIndexed { i, s -> bySurface.putIfAbsent(s, i) }
        val grouped = HashMap<String, MutableList<Int>>()
        keys.forEachIndexed { i, k -> grouped.getOrPut(k) { ArrayList(1) } += i }
        grouped.forEach { (k, ids) -> byKey[k] = ids.toIntArray() }
    }

    val size: Int get() = surfaces.size

    fun surface(id: Int): String = surfaces[id]

    /** Lowercase with apostrophes removed: "Don't" and "dont" share the key "dont". */
    fun key(id: Int): String = keys[id]

    /** The id of exactly [surface], or [UNKNOWN]. */
    fun id(surface: String): Int = bySurface[surface] ?: UNKNOWN

    /** Every id whose key is [key], lowest id first. */
    fun idsForKey(key: String): IntArray = byKey[key] ?: EMPTY

    companion object {
        const val UNKNOWN = -1

        /** Stands for the start of a sentence in contexts and n-grams. */
        const val SENTENCE_START = "<s>"

        private val EMPTY = IntArray(0)

        fun keyOf(word: String): String = buildString(word.length) {
            for (c in word) if (c != '\'' && c != '’') append(c.lowercaseChar())
        }
    }
}
