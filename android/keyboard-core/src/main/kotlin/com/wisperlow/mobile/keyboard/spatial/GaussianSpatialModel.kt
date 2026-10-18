package com.wisperlow.mobile.keyboard.spatial

import com.wisperlow.mobile.keyboard.Touch
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val PI = 3.141592653589793f
private const val NO_KEY_PENALTY = 1e6f
private const val MIX_FLOOR = 1e-30f
private const val MIN_SIGMA = 1e-6f

/**
 * Defaults for [GaussianSpatialModel].
 *
 * The sigmas follow the research defaults: roughly 0.3–0.4 key widths horizontally and
 * 0.25–0.35 rows vertically. [offsetY] is slightly positive because phone touches tend
 * to land a bit low (farther down the board) relative to the intended key centre. [floor]
 * mixes in a uniform background over the board so every result stays finite.
 */
data class SpatialParams(
    val sigmaX: Float = 0.35f,
    val sigmaY: Float = 0.30f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0.08f,
    val floor: Float = 0.01f,
)

/**
 * Per-key override of the touch distribution's offset and spread, in key units.
 *
 * [meanDx]/[meanDy] replace the systematic [SpatialParams.offsetX]/[offsetY] for that key;
 * a non-null [sigmaX]/[sigmaY] replace the global sigmas. Null fields fall back to the
 * global parameters. Supplied later by per-user learning.
 */
data class KeyStats(
    val meanDx: Float = 0f,
    val meanDy: Float = 0f,
    val sigmaX: Float? = null,
    val sigmaY: Float? = null,
)

/**
 * P(touch | key) modelled as an axis-aligned 2D Gaussian at each key centre, plus a
 * systematic offset, mixed with a uniform background over the board.
 *
 * All log-likelihoods are natural logs and always finite. Character lookups are
 * case-insensitive because key centres are stored lowercase.
 */
class GaussianSpatialModel(
    private val keys: List<KeyCenter>,
    private val params: SpatialParams = SpatialParams(),
    private val perKey: Map<Char, KeyStats> = emptyMap(),
) : SpatialModel {

    private val lookup: Map<Char, KeyCenter> = keys.associateBy { it.char.lowercaseChar() }

    /** 1 / area of the keys' bounding box (in key units^2), for the uniform term. */
    private val uniformDensity: Float

    init {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (k in keys) {
            minX = min(minX, k.x)
            maxX = max(maxX, k.x)
            minY = min(minY, k.y)
            maxY = max(maxY, k.y)
        }
        // Letter keys are about 1 x 1 key unit, so expand the centre box by half a key
        // to approximate the keys' hit bodies.
        val pad = 0.5f
        val width = max(maxX - minX, 0f) + pad * 2
        val height = max(maxY - minY, 0f) + pad * 2
        val area = max(width * height, 1e-6f)
        uniformDensity = 1f / area
    }

    override fun logLikelihood(touch: Touch, char: Char): Float {
        val c = char.lowercaseChar()
        val center = lookup[c] ?: return noKeyLogLikelihood()

        val stats = perKey[c]
        val meanX = center.x + (stats?.meanDx ?: params.offsetX)
        val meanY = center.y + (stats?.meanDy ?: params.offsetY)
        val sx = max(stats?.sigmaX ?: params.sigmaX, MIN_SIGMA)
        val sy = max(stats?.sigmaY ?: params.sigmaY, MIN_SIGMA)

        val dx = touch.x - meanX
        val dy = touch.y - meanY
        val exponent = -0.5f * (dx * dx / (sx * sx) + dy * dy / (sy * sy))
        val gauss = (1f / (2f * PI * sx * sy)) * exp(exponent)
        val mix = (1f - params.floor) * gauss + params.floor * uniformDensity
        return Math.log(max(mix, MIX_FLOOR).toDouble()).toFloat()
    }

    /** Chars with no key: the uniform background only, pushed far below any real key. */
    private fun noKeyLogLikelihood(): Float =
        Math.log(max(params.floor * uniformDensity, MIX_FLOOR).toDouble()).toFloat() - NO_KEY_PENALTY

    override fun candidates(touch: Touch, max: Int): List<KeyChance> {
        val n = keys.size
        val limit = max.coerceAtLeast(0).coerceAtMost(n)
        if (limit == 0) return emptyList()

        val scores = FloatArray(n)
        for (i in keys.indices) {
            scores[i] = logLikelihood(touch, keys[i].char)
        }

        val indices = Array(n) { i -> i }
        indices.sortWith(compareByDescending { scores[it] })

        val result = ArrayList<KeyChance>(limit)
        for (i in 0 until limit) {
            val idx = indices[i]
            result += KeyChance(keys[idx].char, scores[idx])
        }
        return result
    }
}
