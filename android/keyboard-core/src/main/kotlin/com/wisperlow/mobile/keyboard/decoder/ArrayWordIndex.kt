package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import kotlin.math.exp
import kotlin.math.ln

/**
 * A [WordIndex] in flat primitive arrays. Building sorts the word ids by (key, id) and walks
 * the keys breadth-first, so a node's children and its words are contiguous ranges and no
 * per-node object survives construction. [child] is a binary search; [topWordsBelow] is a
 * best-first walk that stops as soon as no unvisited node can improve the answer.
 */
class ArrayWordIndex(private val model: LanguageModel) : WordIndex {
    private val childStart: IntArray
    private val childEnd: IntArray
    private val labels: CharArray
    private val childIds: IntArray
    private val wordStart: IntArray
    private val wordEnd: IntArray

    /** Word ids sorted by (key, id); a node's words are a slice of this. */
    private val wordIds: IntArray
    private val best: FloatArray
    private val mass: FloatArray

    /** Pre-order position of each word, so probability ties break exactly like [SimpleWordIndex]. */
    private val rank: IntArray

    override val root: Int get() = 0

    init {
        val vocabulary = model.vocabulary

        val order = ArrayList<Int>(vocabulary.size)
        for (id in 0 until vocabulary.size) {
            val key = vocabulary.key(id)
            if (key.isEmpty() || key == Vocabulary.SENTENCE_START) continue
            order.add(id)
        }
        order.sortWith(Comparator { a, b ->
            val byKey = vocabulary.key(a).compareTo(vocabulary.key(b))
            if (byKey != 0) byKey else a.compareTo(b)
        })
        val sorted = order.toIntArray()

        val starts = IntBuf(256)
        val ends = IntBuf(256)
        val wordsAt = IntBuf(256)
        val wordsStop = IntBuf(256)
        val bests = FloatBuf(256)
        val masses = DoubleBuf(256)
        val labelBuf = CharBuf(256)
        val childBuf = IntBuf(256)
        // Ranges over `sorted` (and the prefix length) are only needed while building.
        val lo = IntBuf(256)
        val hi = IntBuf(256)
        val depth = IntBuf(256)

        fun newNode(d: Int, from: Int, to: Int): Int {
            val node = starts.size
            starts.add(0)
            ends.add(0)
            wordsAt.add(from)
            wordsStop.add(from)
            bests.add(Float.NEGATIVE_INFINITY)
            masses.add(0.0)
            lo.add(from)
            hi.add(to)
            depth.add(d)
            return node
        }

        newNode(0, 0, sorted.size)
        var node = 0
        while (node < starts.size) {
            val d = depth[node]
            val from = lo[node]
            val to = hi[node]
            // Keys that end exactly here sort before keys that extend the prefix, so a scan finds them.
            var wStop = from
            while (wStop < to && vocabulary.key(sorted[wStop]).length == d) wStop++
            wordsAt[node] = from
            wordsStop[node] = wStop
            for (j in from until wStop) {
                val id = sorted[j]
                val p = model.unigramLogProb(id)
                if (p > bests[node]) bests[node] = p
                masses[node] = masses[node] + exp(p.toDouble())
            }
            starts[node] = labelBuf.size
            var j = wStop
            while (j < to) {
                val c = vocabulary.key(sorted[j])[d]
                var k = j + 1
                while (k < to && vocabulary.key(sorted[k])[d] == c) k++
                val child = newNode(d + 1, j, k)
                labelBuf.add(c)
                childBuf.add(child)
                j = k
            }
            ends[node] = labelBuf.size
            node++
        }

        childStart = starts.trimmed()
        childEnd = ends.trimmed()
        labels = labelBuf.trimmed()
        childIds = childBuf.trimmed()
        wordStart = wordsAt.trimmed()
        wordEnd = wordsStop.trimmed()
        wordIds = sorted

        val bestArr = FloatArray(starts.size) { bests[it] }
        var n = starts.size - 1
        while (n >= 0) {
            var sum = masses[n]
            var top = bestArr[n]
            for (c in childStart[n] until childEnd[n]) {
                val child = childIds[c]
                sum += masses[child]
                if (bestArr[child] > top) top = bestArr[child]
            }
            masses[n] = sum
            bestArr[n] = top
            n--
        }
        best = bestArr
        mass = FloatArray(starts.size) { if (masses[it] > 0.0) ln(masses[it]).toFloat() else Float.NEGATIVE_INFINITY }

        val ranks = IntArray(vocabulary.size) { -1 }
        val stack = IntArray(starts.size)
        var sp = 0
        stack[sp++] = 0
        var counter = 0
        while (sp > 0) {
            val current = stack[--sp]
            for (j in wordStart[current] until wordEnd[current]) ranks[wordIds[j]] = counter++
            for (c in childEnd[current] - 1 downTo childStart[current]) stack[sp++] = childIds[c]
        }
        rank = ranks
    }

    override fun child(node: Int, c: Char): Int {
        var low = childStart[node]
        var high = childEnd[node] - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val label = labels[mid]
            when {
                label < c -> low = mid + 1
                label > c -> high = mid - 1
                else -> return childIds[mid]
            }
        }
        return WordIndex.NONE
    }

    override fun children(node: Int): CharArray = labels.copyOfRange(childStart[node], childEnd[node])

    override fun words(node: Int): IntArray = wordIds.copyOfRange(wordStart[node], wordEnd[node])

    override fun bestBelow(node: Int): Float = best[node]

    override fun massBelow(node: Int): Float = mass[node]

    override fun topWordsBelow(node: Int, max: Int): IntArray {
        if (max <= 0) return IntArray(0)
        val found = ArrayList<Int>()

        // Max-heap of node ids by bestBelow.
        var heap = IntArray(64)
        var heapSize = 0
        fun heapPush(value: Int) {
            if (heapSize == heap.size) heap = heap.copyOf(heap.size * 2)
            var i = heapSize++
            heap[i] = value
            while (i > 0) {
                val parent = (i - 1) ushr 1
                if (best[heap[parent]] >= best[heap[i]]) break
                val tmp = heap[parent]
                heap[parent] = heap[i]
                heap[i] = tmp
                i = parent
            }
        }

        fun heapPop(): Int {
            val top = heap[0]
            heap[0] = heap[--heapSize]
            var i = 0
            while (true) {
                val left = 2 * i + 1
                if (left >= heapSize) break
                val right = left + 1
                var big = left
                if (right < heapSize && best[heap[right]] > best[heap[left]]) big = right
                if (best[heap[i]] >= best[heap[big]]) break
                val tmp = heap[i]
                heap[i] = heap[big]
                heap[big] = tmp
                i = big
            }
            return top
        }

        // Bounded min-heap of the largest `max` probabilities seen; its root is the cutoff.
        val cutoffHeap = FloatArray(max)
        var cutoffSize = 0
        fun cutoffSeen(p: Float) {
            if (cutoffSize < max) {
                var i = cutoffSize++
                cutoffHeap[i] = p
                while (i > 0) {
                    val parent = (i - 1) ushr 1
                    if (cutoffHeap[parent] <= cutoffHeap[i]) break
                    val tmp = cutoffHeap[parent]
                    cutoffHeap[parent] = cutoffHeap[i]
                    cutoffHeap[i] = tmp
                    i = parent
                }
            } else if (p > cutoffHeap[0]) {
                cutoffHeap[0] = p
                var i = 0
                while (true) {
                    val left = 2 * i + 1
                    if (left >= cutoffSize) break
                    val right = left + 1
                    var small = left
                    if (right < cutoffSize && cutoffHeap[right] < cutoffHeap[left]) small = right
                    if (cutoffHeap[i] <= cutoffHeap[small]) break
                    val tmp = cutoffHeap[i]
                    cutoffHeap[i] = cutoffHeap[small]
                    cutoffHeap[small] = tmp
                    i = small
                }
            }
        }

        fun cutoff(): Float = if (cutoffSize >= max) cutoffHeap[0] else Float.NEGATIVE_INFINITY

        heapPush(node)
        while (heapSize > 0) {
            val top = heap[0]
            // Stop once every unvisited node is strictly below the max-th word found so far.
            if (found.size >= max && best[top] < cutoff()) break
            heapPop()
            for (j in wordStart[top] until wordEnd[top]) {
                val id = wordIds[j]
                found.add(id)
                cutoffSeen(model.unigramLogProb(id))
            }
            val bound = cutoff()
            for (c in childStart[top] until childEnd[top]) {
                val child = childIds[c]
                if (found.size < max || best[child] >= bound) heapPush(child)
            }
        }

        found.sortWith(Comparator { a, b ->
            val pa = model.unigramLogProb(a)
            val pb = model.unigramLogProb(b)
            when {
                pa > pb -> -1
                pa < pb -> 1
                else -> rank[a] - rank[b]
            }
        })
        return IntArray(minOf(max, found.size)) { found[it] }
    }
}

/** A growable primitive array, used only while building. */
private class IntBuf(capacity: Int) {
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

    fun trimmed(): IntArray = data.copyOf(size)
}

private class FloatBuf(capacity: Int) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    operator fun get(index: Int): Float = data[index]

    operator fun set(index: Int, value: Float) {
        data[index] = value
    }

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }
}

private class DoubleBuf(capacity: Int) {
    private var data = DoubleArray(capacity)
    var size = 0
        private set

    operator fun get(index: Int): Double = data[index]

    operator fun set(index: Int, value: Double) {
        data[index] = value
    }

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }
}

private class CharBuf(capacity: Int) {
    private var data = CharArray(capacity)
    var size = 0
        private set

    fun add(value: Char) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun trimmed(): CharArray = data.copyOf(size)
}
