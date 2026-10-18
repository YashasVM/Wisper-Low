package com.wisperlow.mobile.keyboard.gesture

import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.decoder.Candidate
import com.wisperlow.mobile.keyboard.decoder.CandidateKind
import com.wisperlow.mobile.keyboard.decoder.WordIndex
import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import kotlin.math.hypot

/** Tuning for [GestureDecoder]; every constant the algorithm uses lives here. */
data class GestureParams(
    /** Points both the swipe and each candidate's ideal path are resampled to before comparing. */
    val points: Int = 64,
    /** A path shorter than this many key units is a tap, not a gesture. */
    val minPathLength: Float = 1f,
    /** The first letter's key must sit this close to the start of the swipe. */
    val startRadius: Float = 1f,
    /** The last letter's key must sit this close to the end of the swipe. */
    val endRadius: Float = 1f,
    /** Every letter's key must pass this close to the path, in order. */
    val passRadius: Float = 0.8f,
    /** Stop the tree walk once this many words have been found. */
    val maxCandidates: Int = 400,
    /** Stop the tree walk after this many nodes, whatever the vocabulary. */
    val maxNodes: Int = 20_000,
    /** Spread of the location channel, in key units. */
    val sigmaLoc: Float = 0.5f,
    /** Spread of the shape channel, for paths normalised to a unit bounding-box diagonal. */
    val sigmaShape: Float = 0.2f,
    /** How much the language model counts against the gesture fit. */
    val lmWeight: Float = 0.5f,
)

/** A resampled 2D path: [x] and [y] are the same length. */
private class Path(val x: FloatArray, val y: FloatArray) {
    val size: Int get() = x.size
}

/**
 * Turns a swipe across the keys into words, SHARK2-style: walk the letter tree guided by the
 * path, then rank the words reached by how well their key centres match the swipe.
 */
class GestureDecoder(
    private val index: WordIndex,
    private val model: LanguageModel,
    keys: List<KeyCenter>,
    private val params: GestureParams = GestureParams(),
) {
    private val keyCenters: Map<Char, KeyCenter> = keys.associateBy { it.char }

    init {
        require(params.points >= 2) { "a comparison needs at least two points" }
    }

    /** The likeliest words a swipe from [path] spells after [context], best first, at most [max]. */
    fun decode(path: List<Touch>, context: IntArray, max: Int = 8): List<Candidate> {
        if (path.size < 2 || max <= 0) return emptyList()
        val px = FloatArray(path.size) { path[it].x }
        val py = FloatArray(path.size) { path[it].y }
        if (polylineLength(px, py) < params.minPathLength) return emptyList()

        val n = params.points
        val swipe = resample(px, py, n)
        val startX = swipe.x[0]
        val startY = swipe.y[0]
        val endX = swipe.x[n - 1]
        val endY = swipe.y[n - 1]

        val passSq = params.passRadius * params.passRadius
        val startSq = params.startRadius * params.startRadius
        val endSq = params.endRadius * params.endRadius
        val nearPath = HashMap<Char, IntArray>(keyCenters.size * 2)
        val startKeys = HashSet<Char>()
        val endKeys = HashSet<Char>()
        for (kc in keyCenters.values) {
            val hits = ArrayList<Int>(8)
            for (i in 0 until n) if (sqDist(swipe.x[i], swipe.y[i], kc.x, kc.y) <= passSq) hits.add(i)
            nearPath[kc.char] = hits.toIntArray()
            if (sqDist(startX, startY, kc.x, kc.y) <= startSq) startKeys.add(kc.char)
            if (sqDist(endX, endY, kc.x, kc.y) <= endSq) endKeys.add(kc.char)
        }

        val found = ArrayList<Int>()
        var visited = 0

        fun walk(node: Int, from: Int, lastChar: Char, depth: Int) {
            if (visited >= params.maxNodes || found.size >= params.maxCandidates) return
            for (c in index.children(node)) {
                if (visited >= params.maxNodes || found.size >= params.maxCandidates) return
                val kc = keyCenters[c] ?: continue
                if (depth == 0 && c !in startKeys) continue
                val at = nearPath[c] ?: continue
                // A shifted letter moves the cursor forward; a doubled letter may reuse its point.
                val from2 = if (depth > 0 && c != lastChar) from + 1 else from
                val idx = firstAtLeast(at, from2)
                if (idx < 0) continue
                visited++
                val child = index.child(node, c)
                if (c in endKeys && index.words(child).isNotEmpty()) found.add(child)
                walk(child, idx, c, depth + 1)
            }
        }
        walk(index.root, 0, '\u0000', 0)

        if (found.isEmpty()) return emptyList()
        return score(found, swipe, context, max)
    }

    private fun score(nodes: List<Int>, swipe: Path, context: IntArray, max: Int): List<Candidate> {
        val twoLoc = 2f * params.sigmaLoc * params.sigmaLoc
        val twoShape = 2f * params.sigmaShape * params.sigmaShape
        val out = ArrayList<Candidate>(nodes.size)
        for (node in nodes) {
            val ideal = idealPath(node) ?: continue
            val loc = meanDistance(swipe, ideal)
            val shape = shapeDistance(swipe, ideal)
            val spatial = -(loc * loc) / twoLoc - (shape * shape) / twoShape
            for (id in index.words(node)) {
                val lm = model.logProb(id, context)
                out.add(
                    Candidate(
                        wordId = id,
                        word = model.vocabulary.surface(id),
                        score = spatial + params.lmWeight * lm,
                        spatialScore = spatial,
                        lmScore = lm,
                        kind = CandidateKind.CORRECTION,
                        edits = 0,
                    ),
                )
            }
        }
        return out.sortedByDescending { it.score }.take(max)
    }

    /** The ideal straight-line path a word's swipe would follow, with doubled letters collapsed. */
    private fun idealPath(node: Int): Path? {
        val ids = index.words(node)
        if (ids.isEmpty()) return null
        val key = model.vocabulary.key(ids[0])
        val xs = ArrayList<Float>(key.length)
        val ys = ArrayList<Float>(key.length)
        var prev = '\u0000'
        for (c in key) {
            if (c == prev) continue
            val kc = keyCenters[c] ?: return null
            xs.add(kc.x)
            ys.add(kc.y)
            prev = c
        }
        if (xs.isEmpty()) return null
        return resample(xs.toFloatArray(), ys.toFloatArray(), params.points)
    }

    private fun resample(px: FloatArray, py: FloatArray, n: Int): Path {
        if (px.size == 1 || polylineLength(px, py) <= 1e-6f) {
            return Path(FloatArray(n) { px[0] }, FloatArray(n) { py[0] })
        }
        val m = px.size
        val cum = FloatArray(m)
        for (i in 1 until m) cum[i] = cum[i - 1] + hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        val total = cum[m - 1]
        val rx = FloatArray(n)
        val ry = FloatArray(n)
        var seg = 1
        for (i in 0 until n) {
            val target = total * i / (n - 1)
            while (seg < m - 1 && cum[seg] < target) seg++
            val t0 = cum[seg - 1]
            val t1 = cum[seg]
            val f = if (t1 - t0 > 1e-9f) (target - t0) / (t1 - t0) else 0f
            rx[i] = px[seg - 1] + (px[seg] - px[seg - 1]) * f
            ry[i] = py[seg - 1] + (py[seg] - py[seg - 1]) * f
        }
        return Path(rx, ry)
    }

    /** Mean distance between corresponding points of two equally long paths. */
    private fun meanDistance(a: Path, b: Path): Float {
        var sum = 0f
        for (i in 0 until a.size) sum += hypot(a.x[i] - b.x[i], a.y[i] - b.y[i])
        return sum / a.size
    }

    /** The location distance again, after centring and scaling each path to a unit diagonal. */
    private fun shapeDistance(a: Path, b: Path): Float = meanDistance(normalize(a), normalize(b))

    private fun normalize(p: Path): Path {
        val n = p.size
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += p.x[i]
            cy += p.y[i]
        }
        cx /= n
        cy /= n
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        for (i in 0 until n) {
            val x = p.x[i] - cx
            val y = p.y[i] - cy
            nx[i] = x
            ny[i] = y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag > 1e-6f) {
            val s = 1f / diag
            for (i in 0 until n) {
                nx[i] *= s
                ny[i] *= s
            }
        }
        return Path(nx, ny)
    }

    private fun polylineLength(px: FloatArray, py: FloatArray): Float {
        var total = 0f
        for (i in 1 until px.size) total += hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        return total
    }

    /** The first index in the sorted, ascending [a] that is at least [bound], or -1. */
    private fun firstAtLeast(a: IntArray, bound: Int): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < bound) lo = mid + 1 else hi = mid
        }
        return if (lo < a.size) a[lo] else -1
    }

    private fun sqDist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }
}
