package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrayWordIndexTest {
    private val alphabet = "abcdefgh"

    @Test
    fun `matches simple index on every node and on random walks`() {
        val random = Random(20261017)
        val vocabulary = pseudoVocabulary(random, 5000)
        val model = UnigramModel(vocabulary, randomCounts(random, vocabulary.size))
        val simple = SimpleWordIndex(model)
        val array = ArrayWordIndex(model)

        walkAll(array, simple, array.root, simple.root)

        repeat(3000) {
            var s = simple.root
            var a = array.root
            compareCheap(simple, array, s, a)
            for (c in randomKey(random)) {
                val nextS = simple.child(s, c)
                val nextA = array.child(a, c)
                assertEquals("child divergence under \"$c\"", nextS == WordIndex.NONE, nextA == WordIndex.NONE)
                if (nextA == WordIndex.NONE) break
                s = nextS
                a = nextA
                compareCheap(simple, array, s, a)
            }
        }
    }

    @Test
    fun `top completions match the simple index`() {
        val random = Random(4242)
        val vocabulary = pseudoVocabulary(random, 5000)
        val model = UnigramModel(vocabulary, randomCounts(random, vocabulary.size))
        val simple = SimpleWordIndex(model)
        val array = ArrayWordIndex(model)

        val reached = ArrayList<Int>()
        var node = array.root
        compareTop(simple, array, simple.root, node, listOf(1, 3, 5, 20, 100))
        repeat(150) {
            val key = randomKey(random)
            var s = simple.root
            node = array.root
            for (c in key) {
                s = simple.child(s, c)
                node = array.child(node, c)
                if (node == WordIndex.NONE) break
            }
            if (node != WordIndex.NONE && node !in reached) {
                reached += node
                compareTop(simple, array, s, node, listOf(1, 3, 5, 20))
            }
        }
    }

    @Test
    fun `ties keep the simple index order`() {
        val vocabulary = Vocabulary(listOf("<s>", "cat", "car", "cab", "can", "cap", "ca"))
        val model = UnigramModel(vocabulary, LongArray(vocabulary.size) { 1L })
        val simple = SimpleWordIndex(model)
        val array = ArrayWordIndex(model)
        val caSimple = simple.child(simple.child(simple.root, 'c'), 'a')
        val caArray = array.child(array.child(array.root, 'c'), 'a')
        assertEquals(simple.topWordsBelow(simple.root, 6).toList(), array.topWordsBelow(array.root, 6).toList())
        assertEquals(simple.topWordsBelow(caSimple, 5).toList(), array.topWordsBelow(caArray, 5).toList())
    }

    @Test
    fun `empty vocabulary`() {
        assertSame(emptyList(), longArrayOf())
    }

    @Test
    fun `single letter words`() {
        assertSame(listOf("<s>", "a", "i", "o", "u", "e"), longArrayOf(1, 90, 80, 10, 5, 60))
    }

    @Test
    fun `a word that is a prefix of another`() {
        assertSame(
            listOf("<s>", "the", "they", "them", "the", "then"),
            longArrayOf(1, 50, 40, 30, 70, 20),
        )
    }

    @Test
    fun `apostrophe twins share a key`() {
        assertSame(listOf("<s>", "don't", "dont", "it's", "its", "its"), longArrayOf(1, 40, 40, 30, 30, 10))
    }

    @Test
    fun `builds 200k words quickly`() {
        val random = Random(7)
        val surfaces = ArrayList<String>(200_001)
        surfaces += "<s>"
        repeat(200_000) { surfaces += randomLetters(random, 3 + random.nextInt(8)) }
        val vocabulary = Vocabulary(surfaces)
        val model = UnigramModel(vocabulary, randomCounts(random, vocabulary.size))

        val start = System.nanoTime()
        val index = ArrayWordIndex(model)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        println("ArrayWordIndex built ${vocabulary.size} words in $millis ms")

        assertTrue("build took $millis ms", millis < 5000.0)
        assertTrue(index.bestBelow(index.root).isFinite())
    }

    private fun assertSame(surfaces: List<String>, counts: LongArray) {
        val vocabulary = Vocabulary(surfaces)
        val model = UnigramModel(vocabulary, counts)
        val simple = SimpleWordIndex(model)
        val array = ArrayWordIndex(model)
        walkAll(array, simple, array.root, simple.root)
        compareTop(simple, array, simple.root, array.root, listOf(0, 1, 2, 10, 100))
    }

    private fun walkAll(array: ArrayWordIndex, simple: SimpleWordIndex, a: Int, s: Int) {
        compareCheap(simple, array, s, a)
        val open = array.children(a)
        for (c in open) {
            val childA = array.child(a, c)
            val childS = simple.child(s, c)
            assertTrue("missing child($c)", childS != WordIndex.NONE)
            walkAll(array, simple, childA, childS)
        }
        for (c in alphabet) {
            if (c !in open) assertEquals("missing child($c)", WordIndex.NONE, simple.child(s, c))
        }
    }

    private fun compareCheap(simple: SimpleWordIndex, array: ArrayWordIndex, s: Int, a: Int) {
        assertArrayEquals("children", simple.children(s), array.children(a))
        assertArrayEquals("words", simple.words(s), array.words(a))
        assertEquals("bestBelow", simple.bestBelow(s), array.bestBelow(a), 0f)
        assertEquals("massBelow", simple.massBelow(s), array.massBelow(a), 1e-6f)
    }

    private fun compareTop(
        simple: SimpleWordIndex,
        array: ArrayWordIndex,
        s: Int,
        a: Int,
        sizes: List<Int>,
    ) {
        for (max in sizes) {
            assertArrayEquals("topWordsBelow($max)", simple.topWordsBelow(s, max), array.topWordsBelow(a, max))
        }
    }

    private fun pseudoVocabulary(random: Random, count: Int): Vocabulary {
        val stems = ArrayList<String>()
        val surfaces = ArrayList<String>(count)
        surfaces += "<s>"
        while (surfaces.size < count) {
            val stem = if (stems.size < 24) {
                randomLetters(random, 1 + random.nextInt(3)).also { stems += it }
            } else {
                stems[random.nextInt(stems.size)]
            }
            surfaces += stem + randomLetters(random, random.nextInt(5))
        }
        surfaces += listOf("don't", "dont", "it's", "its")
        return Vocabulary(surfaces)
    }

    private fun randomCounts(random: Random, size: Int): LongArray = LongArray(size) { 1L + random.nextInt(100_000) }

    private fun randomKey(random: Random): String {
        val length = random.nextInt(10)
        val sb = StringBuilder(length)
        repeat(length) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun randomLetters(random: Random, length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }
}
