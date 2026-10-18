package com.wisperlow.mobile.keyboard.spatial

import com.wisperlow.mobile.keyboard.Touch

/** The centre of the key that types [char], in board key units (see [Touch]). */
data class KeyCenter(val char: Char, val x: Float, val y: Float)

/** A key a touch may have been meant for, with ln P(touch | that key). */
data class KeyChance(val char: Char, val logLikelihood: Float)

/** How touches scatter around the keys people mean to hit. */
interface SpatialModel {
    /** ln P(touch | the user meant [char]); very negative but finite when [char] has no key. */
    fun logLikelihood(touch: Touch, char: Char): Float

    /** Keys plausibly meant by [touch], most likely first, at most [max]. */
    fun candidates(touch: Touch, max: Int = 6): List<KeyChance>
}
