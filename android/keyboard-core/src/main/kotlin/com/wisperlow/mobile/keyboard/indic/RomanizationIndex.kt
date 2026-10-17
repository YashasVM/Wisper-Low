package com.wisperlow.mobile.keyboard.indic

import com.wisperlow.mobile.keyboard.Qwerty
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

/** How a native suggestion was reached, for callers that rank or style the kinds differently. */
enum class RomanizationMatch { EXACT, COMPLETION, TYPO }

/**
 * One ranked native candidate for a romanization query. [score] is natural-log counts,
 * less a penalty for completions and typos; the list [RomanizationIndex.suggestScored]
 * returns is already ordered by it, exact matches first.
 */
data class RomanizationSuggestion(
    val native: String,
    /** The romanization key this native came from: the query itself for an exact match. */
    val roman: String,
    val kind: RomanizationMatch,
    /** Counts of every lexicon row that produced this native, summed. */
    val count: Long,
    val score: Float,
)

/**
 * A lexicon-based romanization index: Latin keystrokes in, native-script words out.
 *
 * Rows are `roman<TAB>native<TAB>count`, NFC-normalised on the way in, with duplicate
 * `(roman, native)` pairs summed. This is a lookup over attested pairs, not a general
 * transliterator or WFST: a query with no attested romanization returns nothing rather
 * than inventing phonetics, and the caller keeps the raw Latin as its own candidate.
 *
 * The romanizations live in a flat array trie. A query walks the trie once ([child] is a
 * binary search), so an exact match and its completions never scan the vocabulary, and a
 * typo search is a bounded edit-distance walk that only visits prefixes near the query.
 *
 * Immutable once built, so a query is safe from any thread.
 */
class RomanizationIndex(rows: Sequence<String>) {
    private val keys: Array<String>

    /** Flat native table: key k owns slots nativeStart[k] until nativeStart[k + 1]. */
    private val nativeStart: IntArray
    private val nativeSurface: Array<String>
    private val nativeCount: LongArray
    private val nativeScore: FloatArray

    // Array trie over `keys`. A node's children are edgeLabels[childStart until childEnd];
    // its own words (keys ending exactly here) are keys[keyStart until keyEnd].
    private val childStart: IntArray
    private val childEnd: IntArray
    private val edgeLabels: CharArray
    private val childIds: IntArray
    private val keyStart: IntArray
    private val keyEnd: IntArray
    private val nodeDepth: IntArray
    private val nodeBest: FloatArray

    /** The number of distinct romanization keys. */
    val size: Int get() = keys.size

    init {
        val grouped = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        for (row in rows) {
            val parsed = parseRow(row) ?: continue
            val (roman, native, count) = parsed
            val natives = grouped.getOrPut(roman) { LinkedHashMap() }
            natives[native] = (natives[native] ?: 0L) + count
        }
        keys = grouped.keys.sorted().toTypedArray()

        val starts = IntArray(keys.size + 1)
        val surfaces = ArrayList<String>()
        val counts = ArrayList<Long>()
        val scores = ArrayList<Float>()
        for (i in keys.indices) {
            starts[i] = surfaces.size
            // Most attested first; a stable sort keeps first-seen order for equal counts.
            for (entry in grouped.getValue(keys[i]).entries.sortedByDescending { it.value }) {
                surfaces += entry.key
                counts += entry.value
                scores += ln(entry.value.toDouble() + 1.0).toFloat()
            }
        }
        starts[keys.size] = surfaces.size
        nativeStart = starts
        nativeSurface = surfaces.toTypedArray()
        nativeCount = counts.toLongArray()
        nativeScore = scores.toFloatArray()

        val childStarts = IntBag()
        val childEnds = IntBag()
        val labels = CharBag()
        val ids = IntBag()
        val wordStarts = IntBag()
        val wordEnds = IntBag()
        val depths = IntBag()
        val froms = IntBag()
        val tos = IntBag()

        fun newNode(depth: Int, from: Int, to: Int): Int {
            val id = depths.size
            depths.add(depth)
            froms.add(from)
            tos.add(to)
            wordStarts.add(0)
            wordEnds.add(0)
            childStarts.add(0)
            childEnds.add(0)
            return id
        }

        // Keys sort by (key, ...) and keys that end at a node's depth sort before keys that
        // extend it, so a shallow scan finds the node's own words and grouped children.
        newNode(0, 0, keys.size)
        var node = 0
        while (node < depths.size) {
            val depth = depths[node]
            val from = froms[node]
            val to = tos[node]
            var stop = from
            while (stop < to && keys[stop].length == depth) stop++
            wordStarts[node] = from
            wordEnds[node] = stop
            childStarts[node] = labels.size
            var j = stop
            while (j < to) {
                val c = keys[j][depth]
                var k = j + 1
                while (k < to && keys[k][depth] == c) k++
                val child = newNode(depth + 1, j, k)
                labels.add(c)
                ids.add(child)
                j = k
            }
            childEnds[node] = labels.size
            node++
        }

        childStart = childStarts.toArray()
        childEnd = childEnds.toArray()
        edgeLabels = labels.toArray()
        childIds = ids.toArray()
        keyStart = wordStarts.toArray()
        keyEnd = wordEnds.toArray()
        nodeDepth = depths.toArray()

        // Highest native score at or below a node, for bounded best-first completion search.
        val best = FloatArray(childStart.size)
        for (n in best.indices) {
            var b = Float.NEGATIVE_INFINITY
            for (k in keyStart[n] until keyEnd[n]) {
                for (i in nativeStart[k] until nativeStart[k + 1]) if (nativeScore[i] > b) b = nativeScore[i]
            }
            best[n] = b
        }
        // Children have higher ids than their parent, so one reverse pass finishes the tree.
        for (n in best.size - 1 downTo 0) {
            for (c in childStart[n] until childEnd[n]) {
                val childBest = best[childIds[c]]
                if (childBest > best[n]) best[n] = childBest
            }
        }
        nodeBest = best
    }

    /**
     * Up to [max] native words for [roman], best first. Exact romanizations come first,
     * ranked by summed count, then completions ranked with a per-letter length penalty,
     * and finally a bounded single-edit typo fix when nothing matched the exact prefix.
     * Empty for blank, non-Latin, punctuation, or unknown input; never invent phonetics.
     */
    fun suggest(roman: String, max: Int = 3): List<String> = suggestScored(roman, max).map { it.native }

    /** Like [suggest], but keeps the romanization, kind and count behind each native word. */
    fun suggestScored(roman: String, max: Int = 3): List<RomanizationSuggestion> {
        if (max <= 0) return emptyList()
        val query = normalizeQuery(roman) ?: return emptyList()
        val results = ArrayList<RomanizationSuggestion>(max)
        val seen = HashSet<String>()
        var node = 0
        for (c in query) {
            node = child(node, c)
            if (node == NONE) break
        }
        if (node != NONE) {
            exact(node, max, results, seen)
            if (results.size < max) complete(node, query, max, results, seen)
        }
        // Only fall back to an edit-distance fix when no key carries the typed prefix.
        if (results.isEmpty()) typo(query, max, results, seen)
        return results
    }

    private fun child(node: Int, c: Char): Int {
        var low = childStart[node]
        var high = childEnd[node] - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val label = edgeLabels[mid]
            when {
                label < c -> low = mid + 1
                label > c -> high = mid - 1
                else -> return childIds[mid]
            }
        }
        return NONE
    }

    /** The keys ending exactly at [node]; one key at most, since keys are unique. */
    private fun exact(
        node: Int,
        max: Int,
        out: MutableList<RomanizationSuggestion>,
        seen: MutableSet<String>,
    ) {
        for (k in keyStart[node] until keyEnd[node]) {
            for (i in nativeStart[k] until nativeStart[k + 1]) {
                if (out.size >= max) return
                val native = nativeSurface[i]
                if (seen.add(native)) {
                    out += RomanizationSuggestion(native, keys[k], RomanizationMatch.EXACT, nativeCount[i], nativeScore[i])
                }
            }
        }
    }

    /**
     * Completions below [node], best first. A max-heap over [nodeBest] walks the most
     * promising subtree first and stops once no unvisited node can beat the cutoff, so the
     * work is bounded by what is needed rather than by the size of the prefix range.
     */
    private fun complete(
        node: Int,
        query: String,
        max: Int,
        out: MutableList<RomanizationSuggestion>,
        seen: MutableSet<String>,
    ) {
        val need = max - out.size
        if (need <= 0) return
        val chosen = HashMap<String, RomanizationSuggestion>()
        val heap = BestHeap(nodeBest)
        val floor = ScoreFloor(need)
        for (c in childStart[node] until childEnd[node]) heap.push(childIds[c])
        while (!heap.isEmpty) {
            if (chosen.size >= need && nodeBest[heap.peek()] < floor.cutoff()) break
            val top = heap.pop()
            val extra = nodeDepth[top] - query.length
            for (k in keyStart[top] until keyEnd[top]) {
                for (i in nativeStart[k] until nativeStart[k + 1]) {
                    val native = nativeSurface[i]
                    if (native in seen) continue
                    val score = nativeScore[i] - COMPLETION_PENALTY * extra
                    val existing = chosen[native]
                    if (existing == null || score > existing.score) {
                        chosen[native] =
                            RomanizationSuggestion(native, keys[k], RomanizationMatch.COMPLETION, nativeCount[i], score)
                        floor.offer(score)
                    }
                }
            }
            for (c in childStart[top] until childEnd[top]) heap.push(childIds[c])
        }
        for (s in ranked(chosen.values)) {
            if (out.size >= max) break
            if (seen.add(s.native)) out += s
        }
    }

    /**
     * Single-edit fixes for a query that matched no prefix. A DP row walks the trie so only
     * prefixes within one edit of a query prefix are visited, and a node budget caps the walk.
     * Substituting a neighbouring key is cheaper, matching how people mistype.
     */
    private fun typo(
        query: String,
        max: Int,
        out: MutableList<RomanizationSuggestion>,
        seen: MutableSet<String>,
    ) {
        val found = HashMap<String, RomanizationSuggestion>()
        var budget = MAX_TYPO_NODES
        val rootRow = FloatArray(query.length + 1) { it.toFloat() }

        fun visit(node: Int, prev: FloatArray, prev2: FloatArray?, ch: Char, grand: Char) {
            if (budget-- <= 0) return
            val m = query.length
            val cur = FloatArray(m + 1)
            cur[0] = prev[0] + 1f
            var rowMin = cur[0]
            for (j in 1..m) {
                val qc = query[j - 1]
                val sub = when {
                    qc == ch -> 0f
                    ch != NO_CHAR && Qwerty.adjacent(qc, ch) -> NEAR_KEY_COST
                    else -> 1f
                }
                var v = minOf(cur[j - 1] + 1f, prev[j] + 1f, prev[j - 1] + sub)
                if (prev2 != null && j > 1 && qc == grand && query[j - 2] == ch) {
                    v = minOf(v, prev2[j - 2] + SWAP_COST)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (cur[m] <= MAX_TYPO_COST) {
                for (k in keyStart[node] until keyEnd[node]) {
                    for (i in nativeStart[k] until nativeStart[k + 1]) {
                        val native = nativeSurface[i]
                        if (native in seen) continue
                        val score = nativeScore[i] - TYPO_PENALTY * cur[m]
                        val existing = found[native]
                        if (existing == null || score > existing.score) {
                            found[native] = RomanizationSuggestion(native, keys[k], RomanizationMatch.TYPO, nativeCount[i], score)
                        }
                    }
                }
            }
            if (rowMin <= MAX_TYPO_COST) {
                for (c in childStart[node] until childEnd[node]) visit(childIds[c], cur, prev, edgeLabels[c], ch)
            }
        }

        for (c in childStart[0] until childEnd[0]) visit(childIds[c], rootRow, null, edgeLabels[c], NO_CHAR)
        for (s in ranked(found.values)) {
            if (out.size >= max) break
            if (seen.add(s.native)) out += s
        }
    }

    /** Score first, then key and surface, so equal counts rank the same on every device. */
    private fun ranked(values: Collection<RomanizationSuggestion>): List<RomanizationSuggestion> =
        values.sortedWith(compareByDescending<RomanizationSuggestion> { it.score }.thenBy { it.roman }.thenBy { it.native })

    private fun parseRow(row: String): Triple<String, String, Long>? {
        if (row.isEmpty()) return null
        val firstTab = row.indexOf('\t')
        if (firstTab <= 0) return null
        val roman = normalizeRoman(row.substring(0, firstTab))
        if (roman.isEmpty()) return null
        val rest = row.substring(firstTab + 1)
        val secondTab = rest.indexOf('\t')
        val native = normalizeNative(if (secondTab < 0) rest else rest.substring(0, secondTab))
        if (native.isEmpty()) return null
        val count = if (secondTab < 0) 1L else rest.substring(secondTab + 1).trim().toLongOrNull() ?: 1L
        if (count < 0) return null
        return Triple(roman, native, count)
    }

    /** A row key is only usable if it is a plain Latin word, so bad rows cannot be offered. */
    private fun normalizeRoman(text: String): String {
        val roman = Normalizer.normalize(text.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (roman.isEmpty() || roman.length > MAX_QUERY_LENGTH) return ""
        for (c in roman) if (!isLatinLetter(c)) return ""
        return roman
    }

    private fun normalizeNative(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)

    private fun normalizeQuery(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val query = Normalizer.normalize(trimmed, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (query.length > MAX_QUERY_LENGTH) return null
        for (c in query) if (!isLatinLetter(c)) return null
        return query
    }

    private fun isLatinLetter(c: Char): Boolean =
        c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    private companion object {
        const val NONE = -1
        const val NO_CHAR = '\u0000'

        /** Per extra completion letter: a completion is weaker evidence than an exact match. */
        const val COMPLETION_PENALTY = 1.2f

        /** Schwa-ish typo distance, and a slip onto a neighbouring key as cheaper than that. */
        const val MAX_TYPO_COST = 1f
        const val NEAR_KEY_COST = 0.7f
        const val SWAP_COST = 1f
        const val TYPO_PENALTY = 2.5f

        /** Longest plausible query or key; guards the DP row and prefix walk. */
        const val MAX_QUERY_LENGTH = 64

        /** Hard cap on trie nodes visited by one typo search. */
        const val MAX_TYPO_NODES = 4096
    }
}

/** A growable IntArray, used only while building. */
private class IntBag(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    operator fun get(index: Int): Int = data[index]

    operator fun set(index: Int, value: Int) {
        data[index] = value
    }

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/** A growable CharArray, used only while building. */
private class CharBag(capacity: Int = 16) {
    private var data = CharArray(capacity)
    var size = 0
        private set

    fun add(value: Char) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): CharArray = data.copyOf(size)
}

/** A max-heap of trie node ids ordered by [best], so the most promising subtree pops first. */
private class BestHeap(private val best: FloatArray) {
    private var heap = IntArray(64)
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    fun peek(): Int = heap[0]

    fun push(node: Int) {
        if (size == heap.size) heap = heap.copyOf(heap.size * 2)
        var i = size++
        heap[i] = node
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (best[heap[parent]] >= best[heap[i]]) break
            val t = heap[parent]
            heap[parent] = heap[i]
            heap[i] = t
            i = parent
        }
    }

    fun pop(): Int {
        val top = heap[0]
        heap[0] = heap[--size]
        var i = 0
        while (true) {
            val left = 2 * i + 1
            if (left >= size) break
            val right = left + 1
            var big = left
            if (right < size && best[heap[right]] > best[heap[left]]) big = right
            if (best[heap[i]] >= best[heap[big]]) break
            val t = heap[i]
            heap[i] = heap[big]
            heap[big] = t
            i = big
        }
        return top
    }
}

/** A min-heap of the top [capacity] scores; its root is the cutoff below which results cannot help. */
private class ScoreFloor(private val capacity: Int) {
    private val heap = FloatArray(capacity)
    private var size = 0

    fun cutoff(): Float = if (size >= capacity) heap[0] else Float.NEGATIVE_INFINITY

    fun offer(score: Float) {
        if (size < capacity) {
            var i = size++
            heap[i] = score
            while (i > 0) {
                val parent = (i - 1) ushr 1
                if (heap[parent] <= heap[i]) break
                val t = heap[parent]
                heap[parent] = heap[i]
                heap[i] = t
                i = parent
            }
        } else if (score > heap[0]) {
            heap[0] = score
            var i = 0
            while (true) {
                val left = 2 * i + 1
                if (left >= size) break
                val right = left + 1
                var small = left
                if (right < size && heap[right] < heap[left]) small = right
                if (heap[i] <= heap[small]) break
                val t = heap[i]
                heap[i] = heap[small]
                heap[small] = t
                i = small
            }
        }
    }
}
