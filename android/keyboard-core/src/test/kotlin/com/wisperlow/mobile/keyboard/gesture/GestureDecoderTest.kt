package com.wisperlow.mobile.keyboard.gesture

import com.wisperlow.mobile.keyboard.FieldKind
import com.wisperlow.mobile.keyboard.KeyboardGeometry
import com.wisperlow.mobile.keyboard.KeyboardLayout
import com.wisperlow.mobile.keyboard.Page
import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.decoder.CandidateKind
import com.wisperlow.mobile.keyboard.decoder.SimpleWordIndex
import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.ScoredWord
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GestureDecoderTest {
    private val centers: Map<Char, KeyCenter> = run {
        val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, numberRow = false)
        val placed = KeyboardGeometry.layout(rows, width = 1080f, rowHeight = 150f, side = 10f)
        val units = KeyboardGeometry.units(placed, rowHeight = 150f, side = 10f)
        KeyboardGeometry.letterCenters(placed, units).associateBy { it.char }
    }

    private val wordList = """
        the hello you good world keyboard be to of and a in that have it for not on with he as do at this but
        his by from they we say her she or an will my one all would there their what so up out if about who
        get which go me when make can like time no just him know take people into year your some could them
        see other than then now look only come its over think also back after use two how our work first well
        way even new want because any these give day most us is was are been has had were said did may part
        place where right still being here why while again little might much must such never own same another
        under long very house word water room mother father friend school family story book night light life
        hand eye head home side end few both between four five six seven eight nine ten man woman boy girl
        child name number letter keep put set found told asked called seemed help held hell hero yellow words
        worked keys board
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()

    private val surfaces = listOf(Vocabulary.SENTENCE_START) + wordList
    private val vocabulary = Vocabulary(surfaces)
    private val counts = LongArray(vocabulary.size) { (1000 - it).coerceAtLeast(1).toLong() }
    private val model = UnigramModel(vocabulary, counts)
    private val decoder = GestureDecoder(SimpleWordIndex(model), model, centers.values.toList())

    /** A swipe through the key centres of [letters], jittered by about 0.15 key units. */
    private fun swipe(letters: List<Char>, seed: Long, jitter: Float = 0.15f): List<Touch> {
        val rnd = Random(seed)
        fun jittered(): Float = (rnd.nextFloat() - 0.5f) * 2f * jitter
        val points = ArrayList<Touch>()
        var prev: Touch? = null
        for (c in letters) {
            val kc = centers.getValue(c)
            val p = Touch(kc.x + jittered(), kc.y + jittered())
            val q = prev
            if (q != null) {
                for (s in 1..4) {
                    val f = s / 5f
                    points.add(
                        Touch(
                            q.x + (p.x - q.x) * f + jittered() * 0.3f,
                            q.y + (p.y - q.y) * f + jittered() * 0.3f,
                        ),
                    )
                }
            }
            points.add(p)
            prev = p
        }
        return points
    }

    private fun best(path: List<Touch>): String? = decoder.decode(path, IntArray(0)).firstOrNull()?.word

    @Test
    fun `common swipes come out top one`() {
        assertEquals("hello", best(swipe(listOf('h', 'e', 'l', 'o'), seed = 1)))
        assertEquals("the", best(swipe(listOf('t', 'h', 'e'), seed = 2)))
        assertEquals("keyboard", best(swipe(listOf('k', 'e', 'y', 'b', 'o', 'a', 'r', 'd'), seed = 3)))
        assertEquals("world", best(swipe(listOf('w', 'o', 'r', 'l', 'd'), seed = 4)))
        assertEquals("good", best(swipe(listOf('g', 'o', 'd'), seed = 5)))
        assertEquals("you", best(swipe(listOf('y', 'o', 'u'), seed = 6)))
    }

    @Test
    fun `candidates are corrections ranked best first`() {
        val paths = decoder.decode(swipe(listOf('t', 'h', 'e'), seed = 7), IntArray(0))
        assertTrue(paths.isNotEmpty())
        assertTrue(paths.all { it.kind == CandidateKind.CORRECTION })
        assertTrue(paths.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun `a straight line across a row is a word or nothing`() {
        val q = centers.getValue('q')
        val p = centers.getValue('p')
        val line = listOf(Touch(q.x, q.y), Touch(p.x, p.y))
        val paths = decoder.decode(line, IntArray(0))
        assertTrue(paths.size <= 8)
        assertTrue(paths.all { it.wordId in 0 until vocabulary.size })
    }

    @Test
    fun `a tap length path is not a gesture`() {
        val tap = listOf(Touch(0.5f, 0.5f), Touch(0.55f, 0.52f), Touch(0.6f, 0.5f))
        assertNull(best(tap))
        assertTrue(decoder.decode(tap, IntArray(0)).isEmpty())
    }

    @Test
    fun `a contextual model breaks a spatial tie`() {
        val small = Vocabulary(listOf(Vocabulary.SENTENCE_START, "in", "on"))
        val index = SimpleWordIndex(UnigramModel(small, LongArray(small.size) { 1L }))
        val i = centers.getValue('i')
        val o = centers.getValue('o')
        val n = centers.getValue('n')
        // Start midway between i and o, so both readings fit the path equally well.
        val path = listOf(Touch((i.x + o.x) / 2f, (i.y + o.y) / 2f), Touch(n.x, n.y))

        val preferIn = ContextModel(small, small.id("in"))
        val preferOn = ContextModel(small, small.id("on"))
        assertEquals("in", GestureDecoder(index, preferIn, centers.values.toList()).decode(path, intArrayOf(0)).first().word)
        assertEquals("on", GestureDecoder(index, preferOn, centers.values.toList()).decode(path, intArrayOf(0)).first().word)
    }

    @Test
    fun `decoding stays under 40 ms on a 50k word vocabulary`() {
        val bigSurfaces = ArrayList<String>(50_001)
        bigSurfaces.add(Vocabulary.SENTENCE_START)
        val seen = HashSet<String>()
        val rnd = Random(11)
        while (bigSurfaces.size <= 50_000) {
            val len = 4 + rnd.nextInt(5)
            val sb = StringBuilder(len)
            repeat(len) { sb.append('a' + rnd.nextInt(26)) }
            val w = sb.toString()
            if (seen.add(w)) bigSurfaces.add(w)
        }
        val bigVocab = Vocabulary(bigSurfaces)
        val bigModel = UnigramModel(bigVocab, LongArray(bigVocab.size) { 1L })
        val big = GestureDecoder(SimpleWordIndex(bigModel), bigModel, centers.values.toList())
        val path = swipe(listOf('h', 'e', 'l', 'o'), seed = 8)

        repeat(3) { big.decode(path, IntArray(0)) }
        val start = System.nanoTime()
        repeat(10) { big.decode(path, IntArray(0)) }
        val ms = (System.nanoTime() - start) / 10.0 / 1_000_000.0
        assertTrue("a decode took $ms ms", ms < 40.0)
    }
}

/** A stand-in context model: every word is equal except [prefer], which wins wherever it fits. */
private class ContextModel(override val vocabulary: Vocabulary, private val prefer: Int) : LanguageModel {
    override fun logProb(word: Int, context: IntArray): Float = if (word == prefer) 3f else 0f

    override fun unigramLogProb(word: Int): Float = 0f

    override fun predict(context: IntArray, max: Int): List<ScoredWord> = emptyList()
}
