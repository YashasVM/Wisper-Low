package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.KeyboardGeometry
import com.wisperlow.mobile.keyboard.FieldKind
import com.wisperlow.mobile.keyboard.KeyboardLayout
import com.wisperlow.mobile.keyboard.Page
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractsTest {
    private val vocabulary = Vocabulary(listOf("<s>", "the", "they", "then", "don't", "dont", "I"))
    private val model = UnigramModel(vocabulary, longArrayOf(1, 100, 30, 20, 50, 2, 80))

    @Test
    fun `vocabulary keys ignore case and apostrophes`() {
        assertEquals("dont", vocabulary.key(vocabulary.id("don't")))
        assertArrayEquals(intArrayOf(4, 5), vocabulary.idsForKey("dont"))
        assertEquals(Vocabulary.UNKNOWN, vocabulary.id("nope"))
        assertEquals("i", vocabulary.key(vocabulary.id("I")))
    }

    @Test
    fun `unigram predictions skip the sentence start`() {
        assertEquals(listOf("the", "I"), model.predict(intArrayOf(), 2).map { vocabulary.surface(it.id) })
    }

    @Test
    fun `simple index walks keys and ranks completions`() {
        val index = SimpleWordIndex(model)
        val th = index.child(index.child(index.root, 't'), 'h')
        val the = index.child(th, 'e')
        assertArrayEquals(intArrayOf(1), index.words(the))
        assertEquals(listOf("the", "they", "then"), index.topWordsBelow(th, 3).map { vocabulary.surface(it) })
        assertArrayEquals(charArrayOf('m', 'n', 'y').filter { index.child(the, it) != WordIndex.NONE }.toCharArray(), index.children(the))
        assertEquals(model.unigramLogProb(1), index.bestBelow(th), 1e-6f)
        assertTrue(index.massBelow(th) > index.bestBelow(th))
    }

    @Test
    fun `letter centres line up with the qwerty grid`() {
        val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, numberRow = false)
        val placed = KeyboardGeometry.layout(rows, width = 1080f, rowHeight = 150f, side = 10f)
        val units = KeyboardGeometry.units(placed, rowHeight = 150f, side = 10f)
        val centers = KeyboardGeometry.letterCenters(placed, units).associateBy { it.char }
        assertEquals(26, centers.size)
        assertEquals(0.5f, centers.getValue('q').x, 1e-4f)
        assertEquals(1.0f, centers.getValue('a').x, 1e-4f)
        assertEquals(2.0f, centers.getValue('z').x, 1e-4f)
        assertEquals(2.5f, centers.getValue('z').y, 1e-4f)
    }
}
