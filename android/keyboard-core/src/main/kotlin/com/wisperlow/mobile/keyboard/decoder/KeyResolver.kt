package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.spatial.SpatialModel
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import kotlin.math.abs

data class ResolverParams(
    val anchorX: Float = 0.30f,
    val anchorY: Float = 0.30f,
    val lmWeight: Float = 1.0f,
    val switchMargin: Float = 0.5f,
    val missingLetterLogProb: Float = -9f,
)

class KeyResolver(
    private val spatial: SpatialModel,
    private val next: NextCharModel,
    private val params: ResolverParams = ResolverParams(),
) {
    /** The letter a tap types; [landedOn] is the key whose rectangle holds the tap, centred at [landed]. */
    fun resolve(touch: Touch, landedOn: Char, landed: KeyCenter, prefix: String, context: IntArray): Char {
        if (!landedOn.isLetter()) return landedOn
        val lowerLanded = landedOn.lowercaseChar()

        val dx = touch.x - landed.x
        val dy = touch.y - landed.y
        if (abs(dx) <= params.anchorX && abs(dy) <= params.anchorY) return landedOn

        val lm = next.logProbs(prefix, context)
        if (lm.isEmpty()) return landedOn

        fun nextLogProb(c: Char): Float =
            lm.getOrElse(c.lowercaseChar()) { params.missingLetterLogProb }

        val scored = spatial.candidates(touch, 4).map { kc ->
            kc.char.lowercaseChar() to (kc.logLikelihood + params.lmWeight * nextLogProb(kc.char))
        }
        if (scored.isEmpty()) return landedOn

        val best = scored.maxByOrNull { it.second } ?: return landedOn
        val landedScore = spatial.logLikelihood(touch, lowerLanded) +
            params.lmWeight * nextLogProb(lowerLanded)

        if (best.first == lowerLanded) return landedOn
        if (best.second - landedScore <= params.switchMargin) return landedOn

        return if (landedOn.isUpperCase()) best.first.uppercaseChar() else best.first
    }

    /** Every scored option, for a debug overlay and for tuning. */
    fun explain(touch: Touch, landedOn: Char, landed: KeyCenter, prefix: String, context: IntArray): List<Pair<Char, Float>> {
        if (!landedOn.isLetter()) return emptyList()
        val lm = next.logProbs(prefix, context)
        if (lm.isEmpty()) return emptyList()

        fun nextLogProb(c: Char): Float =
            lm.getOrElse(c.lowercaseChar()) { params.missingLetterLogProb }

        return spatial.candidates(touch, 4).map { kc ->
            kc.char.lowercaseChar() to (kc.logLikelihood + params.lmWeight * nextLogProb(kc.char))
        }
    }
}
