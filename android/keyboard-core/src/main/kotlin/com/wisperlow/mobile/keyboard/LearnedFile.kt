package com.wisperlow.mobile.keyboard

import java.io.File

/** The learned-words file: one tab-separated line per word (w), word pair (p) or taught fix (f). */
object LearnedFile {
    private const val MAX_SAVED_PAIRS = 30_000

    fun read(file: File): Lexicon.LearnedData? {
        if (!file.isFile) return null
        val words = ArrayList<Pair<String, Int>>()
        val pairs = ArrayList<Triple<String, String, Int>>()
        val fixes = ArrayList<Triple<String, String, Int>>()
        file.forEachLine { line ->
            val parts = line.split('\t')
            when {
                parts.size == 3 && parts[0] == "w" -> parts[2].toIntOrNull()?.let { words += parts[1] to it }
                parts.size == 4 && parts[0] == "p" -> parts[3].toIntOrNull()?.let { pairs += Triple(parts[1], parts[2], it) }
                parts.size == 4 && parts[0] == "f" -> parts[3].toIntOrNull()?.let { fixes += Triple(parts[1], parts[2], it) }
            }
        }
        return Lexicon.LearnedData(words, pairs, fixes)
    }

    /** Writes to a temporary file first, so a crash mid-save never loses what was learned. */
    fun write(target: File, data: Lexicon.LearnedData) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.bufferedWriter().use { out ->
            data.words.forEach { (w, c) -> out.write("w\t$w\t$c\n") }
            data.fixes.forEach { (typed, fix, c) -> out.write("f\t$typed\t$fix\t$c\n") }
            // Keep the most used pairs so the file stays small.
            data.pairs.sortedByDescending { it.third }.take(MAX_SAVED_PAIRS).forEach { (a, b, c) -> out.write("p\t$a\t$b\t$c\n") }
        }
        if (!temp.renameTo(target)) temp.delete()
    }
}
