package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import com.wisperlow.mobile.keyboard.spatial.KeyChance
import com.wisperlow.mobile.keyboard.spatial.SpatialModel
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyResolverTest {
    private val centers = listOf(
        KeyCenter('t', 4.5f, 0.5f),
        KeyCenter('y', 5.5f, 0.5f),
    )

    private fun spatial(): SpatialModel = object : SpatialModel {
        val sigma = 0.5f
        override fun logLikelihood(touch: Touch, char: Char): Float {
            val c = centers.firstOrNull { it.char == char.lowercaseChar() } ?: return -100f
            val dx = touch.x - c.x
            val dy = touch.y - c.y
            return -(dx * dx + dy * dy) / (2f * sigma * sigma)
        }
        override fun candidates(touch: Touch, max: Int): List<KeyChance> {
            return centers.map { kc -> KeyChance(kc.char, logLikelihood(touch, kc.char)) }
                .sortedByDescending { it.logLikelihood }.take(max)
        }
    }

    private class FakeNext(private val data: Map<String, Map<Char, Float>>) : NextCharModel {
        override fun logProbs(prefix: String, context: IntArray): Map<Char, Float> =
            data.getOrElse(prefix) { emptyMap() }
    }

    @Test
    fun centreTapNeverChanges() {
        val next = FakeNext(mapOf("he" to mapOf('y' to 0f, 't' to -5f)))
        val r = KeyResolver(spatial(), next)
        val touch = Touch(4.5f, 0.5f)
        val landed = KeyCenter('t', 4.5f, 0.5f)
        assertEquals('t', r.resolve(touch, 't', landed, "he", intArrayOf()))
    }

    @Test
    fun borderTapAfterHeFavoursY() {
        val next = FakeNext(mapOf("he" to mapOf('y' to 0f, 't' to -5f)))
        val r = KeyResolver(spatial(), next)
        val touch = Touch(5.0f, 0.5f)
        val landed = KeyCenter('t', 4.5f, 0.5f)
        assertEquals('y', r.resolve(touch, 't', landed, "he", intArrayOf()))
    }

    @Test
    fun borderTapNeutralKeepsT() {
        val next = FakeNext(mapOf("he" to mapOf('t' to 0f, 'y' to 0f)))
        val r = KeyResolver(spatial(), next)
        val touch = Touch(5.0f, 0.5f)
        val landed = KeyCenter('t', 4.5f, 0.5f)
        assertEquals('t', r.resolve(touch, 't', landed, "he", intArrayOf()))
    }

    @Test
    fun emptyModelNoChange() {
        val next = FakeNext(emptyMap())
        val r = KeyResolver(spatial(), next)
        val touch = Touch(5.0f, 0.5f)
        val landed = KeyCenter('t', 4.5f, 0.5f)
        assertEquals('t', r.resolve(touch, 't', landed, "he", intArrayOf()))
    }

    @Test
    fun missingLetterHandled() {
        val next = FakeNext(mapOf("he" to mapOf('y' to 0f)))
        val r = KeyResolver(spatial(), next)
        val touch = Touch(5.0f, 0.5f)
        val landed = KeyCenter('t', 4.5f, 0.5f)
        assertEquals('y', r.resolve(touch, 't', landed, "he", intArrayOf()))
    }

    @Test
    fun uppercasePreserved() {
        val next = FakeNext(mapOf("he" to mapOf('y' to 0f, 't' to -5f)))
        val r = KeyResolver(spatial(), next)
        val touch = Touch(5.0f, 0.5f)
        val landed = KeyCenter('T', 4.5f, 0.5f)
        assertEquals('Y', r.resolve(touch, 'T', landed, "he", intArrayOf()))
    }

    @Test
    fun nonLetterReturned() {
        val next = FakeNext(mapOf("he" to mapOf('y' to 0f)))
        val r = KeyResolver(spatial(), next)
        val touch = Touch(5.0f, 0.5f)
        val landed = KeyCenter('1', 4.5f, 0.5f)
        assertEquals('1', r.resolve(touch, '1', landed, "he", intArrayOf()))
    }
}
