package com.wisperlow.mobile.keyboard.spatial

import com.wisperlow.mobile.keyboard.FieldKind
import com.wisperlow.mobile.keyboard.KeyboardGeometry
import com.wisperlow.mobile.keyboard.KeyboardLayout
import com.wisperlow.mobile.keyboard.Page
import com.wisperlow.mobile.keyboard.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GaussianSpatialModelTest {

    private val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, false)
    private val placed = KeyboardGeometry.layout(rows, 300f, 50f, 50f)
    private val units = KeyboardGeometry.units(placed, 50f, 50f)
    private val centers = KeyboardGeometry.letterCenters(placed, units)

    private val model = GaussianSpatialModel(centers)

    @Test
    fun `tap on a key centre ranks that key first`() {
        for (center in centers) {
            val top = model.candidates(Touch(center.x, center.y), 1).firstOrNull()
            assertEquals("centre of ${center.char} should rank first", center.char, top?.char)
            // case-insensitive lookup
            assertEquals(
                model.logLikelihood(Touch(center.x, center.y), center.char),
                model.logLikelihood(Touch(center.x, center.y), center.char.uppercaseChar()),
                0f,
            )
        }
    }

    @Test
    fun `halfway between g and h gives near-equal likelihoods`() {
        val touch = Touch(5.5f, 1.5f) // g at x=5.0, h at x=6.0, both y=1.5
        val g = model.logLikelihood(touch, 'g')
        val h = model.logLikelihood(touch, 'h')
        val diff = g - h
        assertTrue("g and h should be near-equal halfway between them", diff < 0.01f && -diff < 0.01f)
    }

    @Test
    fun `offsetY shifts which key wins near a row boundary`() {
        // s at (2.0, 1.5) and z at (2.0, 2.5) share a column; boundary at y=2.0.
        val touch = Touch(2.0f, 2.04f)
        val defaultTop = model.candidates(touch, 2).map { it.char }
        assertEquals(listOf('s', 'z'), defaultTop)

        // With no downward offset the boundary sits at the midpoint and z wins.
        val shifted = GaussianSpatialModel(
            centers,
            SpatialParams(offsetY = 0f),
        )
        assertEquals('z', shifted.candidates(touch, 1).firstOrNull()?.char)
    }

    @Test
    fun `far keys are finite and candidates are sorted and respect max`() {
        val touch = Touch(100f, 100f)
        val chances = model.candidates(touch, 3)
        assertEquals(3, chances.size)
        assertTrue(chances.all { it.logLikelihood.isFinite() })
        for (i in 1 until chances.size) {
            assertTrue("candidates must be descending", chances[i - 1].logLikelihood >= chances[i].logLikelihood)
        }

        assertEquals(centers.size, model.candidates(touch, centers.size * 2).size)
        assertTrue(model.candidates(touch, 0).isEmpty())

        // A char with no key is finite and far worse than any real key.
        val noKey = model.logLikelihood(touch, '\u044f') // cyrillic ya, no QWERTY key
        assertTrue(noKey.isFinite())
        val worstKey = model.candidates(touch, 1).firstOrNull()?.logLikelihood
        assertTrue("no-key char should be worse than the worst key", noKey < worstKey ?: return)
    }

    @Test
    fun `KeyStats override changes only that key`() {
        val touch = Touch(5.0f, 1.5f) // g's centre
        val base = model.logLikelihood(touch, 'g')

        val overridden =
            GaussianSpatialModel(centers, SpatialParams(), mapOf('g' to KeyStats(sigmaX = 1.0f, sigmaY = 1.0f)))
        assertFalse("g's likelihood should change", overridden.logLikelihood(touch, 'g') == base)

        // every other key is untouched
        for (center in centers) {
            if (center.char != 'g') {
                assertEquals(
                    "${center.char} should be unaffected by g's override",
                    model.logLikelihood(touch, center.char),
                    overridden.logLikelihood(touch, center.char),
                    0f,
                )
            }
        }
    }
}
