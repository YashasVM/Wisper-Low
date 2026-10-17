package com.wisperlow.mobile.keyboard.lm

import java.io.File

/**
 * Raw n-gram counts by dense word id, so millions of n-grams fit in a few primitive arrays
 * instead of an object graph. Word ids are indices into [vocabulary], which ranks unigrams by
 * count (highest first, ties by word) and always contains [Vocabulary.SENTENCE_START].
 * Bigram and trigram rows are parallel arrays sorted strictly by ids, so consumers can
 * binary search and walk a context's rows as one contiguous range.
 */
class NgramCounts(
    val vocabulary: List<String>,
    val unigramCounts: LongArray,
    val bigramFirst: IntArray,
    val bigramSecond: IntArray,
    val bigramCounts: IntArray,
    val trigramFirst: IntArray,
    val trigramSecond: IntArray,
    val trigramThird: IntArray,
    val trigramCounts: IntArray,
) {
    init {
        require(unigramCounts.size == vocabulary.size) { "one unigram count per word" }
        require(bigramSecond.size == bigramFirst.size && bigramCounts.size == bigramFirst.size) {
            "parallel bigram rows"
        }
        require(
            trigramSecond.size == trigramFirst.size &&
                trigramThird.size == trigramFirst.size &&
                trigramCounts.size == trigramFirst.size,
        ) { "parallel trigram rows" }
        require(isStrictlyOrdered(bigramFirst, bigramSecond, null)) { "bigram rows must be sorted and unique" }
        require(isStrictlyOrdered(trigramFirst, trigramSecond, trigramThird)) { "trigram rows must be sorted and unique" }
    }

    /** Index of the first bigram row whose first word is [first]; that word's rows are contiguous. */
    fun bigramStart(first: Int): Int {
        var lo = 0
        var hi = bigramFirst.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bigramFirst[mid] < first) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Index of the first trigram row for the context [first, second]; that context's rows are contiguous. */
    fun trigramStart(first: Int, second: Int): Int {
        var lo = 0
        var hi = trigramFirst.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val cmp = trigramFirst[mid].compareTo(first)
            val bySecond = if (cmp == 0) trigramSecond[mid].compareTo(second) else cmp
            if (bySecond < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /**
         * Reads the counter tool's UTF-8 TSV output: unigrams.tsv (word<TAB>count),
         * bigrams.tsv (w1<TAB>w2<TAB>count) and trigrams.tsv (w1<TAB>w2<TAB>w3<TAB>count).
         * Files are streamed line by line. Words are ranked by count and capped at
         * [maxVocabulary] (the sentence-start token is never cut, because it carries the
         * sentence contexts); n-grams naming a dropped word are skipped, and rows are
         * re-sorted by id because the files arrive in counter order.
         */
        fun read(dir: File, maxVocabulary: Int): NgramCounts {
            require(maxVocabulary > 0) { "maxVocabulary must be positive" }

            val countsByWord = HashMap<String, Long>()
            eachRow(File(dir, "unigrams.tsv"), 2) { fields ->
                countsByWord.merge(fields[0], fields[1].parseCount()) { a, b -> a + b }
            }
            // The counter may never emit <s> as a counted word, yet contexts need its id.
            countsByWord.putIfAbsent(Vocabulary.SENTENCE_START, 0L)
            val ranked = countsByWord.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            val selected = ranked.take(maxVocabulary).toMutableList()
            if (selected.none { it.key == Vocabulary.SENTENCE_START }) {
                selected[selected.lastIndex] = ranked.first { it.key == Vocabulary.SENTENCE_START }
            }
            val ids = HashMap<String, Int>(selected.size * 2)
            selected.forEachIndexed { id, row -> ids[row.key] = id }

            val bigramColumns = readHigher(File(dir, "bigrams.tsv"), 3, ids, 2)
            val trigramColumns = readHigher(File(dir, "trigrams.tsv"), 4, ids, 3)
            return NgramCounts(
                vocabulary = selected.map { it.key },
                unigramCounts = LongArray(selected.size) { selected[it].value },
                bigramFirst = bigramColumns[0],
                bigramSecond = bigramColumns[1],
                bigramCounts = bigramColumns[2],
                trigramFirst = trigramColumns[0],
                trigramSecond = trigramColumns[1],
                trigramThird = trigramColumns[2],
                trigramCounts = trigramColumns[3],
            )
        }

        /** Streams [file] (absent means an empty table) and visits its tab-separated [columns]. */
        private fun eachRow(file: File, columns: Int, visit: (List<String>) -> Unit) {
            if (!file.exists()) return
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isEmpty()) return@forEachLine
                val fields = line.split('\t')
                require(fields.size == columns) { "expected $columns columns in $file: '$line'" }
                visit(fields)
            }
        }

        /** Reads an n-gram file, keeps rows whose words all made the vocabulary, sorts and merges them. */
        private fun readHigher(file: File, columns: Int, ids: Map<String, Int>, wordColumns: Int): List<IntArray> {
            val collected = List(columns) { IntBuilder() }
            eachRow(file, columns) { fields ->
                val count = fields[columns - 1].parseCount()
                val known = (0 until wordColumns).all { ids[fields[it]] != null }
                if (known && count > 0 && count <= Int.MAX_VALUE) {
                    for (c in 0 until wordColumns) collected[c].add(ids.getValue(fields[c]))
                    collected[columns - 1].add(count.toInt())
                }
            }
            return orderAndMerge(collected.map { it.toArray() }, wordColumns)
        }

        /**
         * Orders streamed rows lexicographically by their word-id columns and sums duplicate
         * rows, so [NgramCounts] can promise sorted, unique rows whatever order files arrive in.
         */
        private fun orderAndMerge(columns: List<IntArray>, wordColumns: Int): List<IntArray> {
            val rowCount = columns.first().size
            val order = (0 until rowCount).sortedWith { a, b ->
                var cmp = 0
                for (c in 0 until wordColumns) {
                    cmp = columns[c][a].compareTo(columns[c][b])
                    if (cmp != 0) break
                }
                cmp
            }
            val merged = List(columns.size) { IntArray(rowCount) }
            var write = 0
            var i = 0
            while (i < rowCount) {
                var sum = columns[wordColumns][order[i]].toLong()
                var j = i + 1
                while (j < rowCount && sameRow(columns, wordColumns, order[i], order[j])) {
                    sum += columns[wordColumns][order[j]]
                    j++
                }
                for (c in 0 until wordColumns) merged[c][write] = columns[c][order[i]]
                merged[wordColumns][write] = sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                write++
                i = j
            }
            return merged.map { it.copyOf(write) }
        }

        private fun sameRow(columns: List<IntArray>, wordColumns: Int, a: Int, b: Int): Boolean {
            for (c in 0 until wordColumns) if (columns[c][a] != columns[c][b]) return false
            return true
        }

        private fun String.parseCount(): Long =
            toLongOrNull() ?: throw IllegalArgumentException("count is not a number: '$this'")
    }
}

/** Growable primitive column, so streaming a file never boxes every row. */
private class IntBuilder(initialCapacity: Int = 256) {
    private var data = IntArray(initialCapacity)
    private var size = 0

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/** True when rows increase lexicographically with no duplicates, which binary search relies on. */
private fun isStrictlyOrdered(first: IntArray, second: IntArray, third: IntArray?): Boolean {
    for (i in 1 until first.size) {
        val p = i - 1
        if (first[p] != first[i]) {
            if (first[p] > first[i]) return false
            continue
        }
        if (second[p] != second[i]) {
            if (second[p] > second[i]) return false
            continue
        }
        if (third == null) return false
        if (third[p] >= third[i]) return false
    }
    return true
}
