package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.FieldKind
import com.wisperlow.mobile.keyboard.KeyboardGeometry
import com.wisperlow.mobile.keyboard.KeyboardLayout
import com.wisperlow.mobile.keyboard.Page
import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.ScoredWord
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import com.wisperlow.mobile.keyboard.spatial.KeyChance
import com.wisperlow.mobile.keyboard.spatial.SpatialModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class BeamTypingDecoderTest {

    // --- the board: real letter centres from the shared layout, in key units ---------------

    private val centers: Map<Char, Touch> = run {
        val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, numberRow = false)
        val placed = KeyboardGeometry.layout(rows, width = 1080f, rowHeight = 150f, side = 10f)
        val units = KeyboardGeometry.units(placed, rowHeight = 150f, side = 10f)
        KeyboardGeometry.letterCenters(placed, units).associate { it.char to Touch(it.x, it.y) }
    }

    /** An isotropic Gaussian over the key centres, as in the research notes' first implementation. */
    private inner class GaussianSpatial(private val sigma: Float = 0.35f) : SpatialModel {
        override fun logLikelihood(touch: Touch, char: Char): Float {
            val center = centers[char.lowercaseChar()] ?: return -30f // keys off the letter rows
            val dx = touch.x - center.x
            val dy = touch.y - center.y
            return -(dx * dx + dy * dy) / (2f * sigma * sigma)
        }

        override fun candidates(touch: Touch, max: Int): List<KeyChance> = centers.keys
            .map { KeyChance(it, logLikelihood(touch, it)) }
            .sortedByDescending { it.logLikelihood }
            .take(max)
    }

    /** A tap on [c]'s centre, or [dx]/[dy] key units off it. */
    private fun at(c: Char, dx: Float = 0f, dy: Float = 0f): Touch {
        val center = centers.getValue(c)
        return Touch(center.x + dx, center.y + dy)
    }

    /** Taps on key centres, the way a careful thumb lands. */
    private fun typed(word: String): List<TypedKey> = word.map { TypedKey(it, at(it)) }

    /** Taps on centres except for one letter, which landed [dx], [dy] away from its centre. */
    private fun typedOff(word: String, letter: Char, dx: Float, dy: Float = 0f): List<TypedKey> =
        word.map { TypedKey(it, if (it == letter) at(it, dx, dy) else at(it)) }

    /** Taps with no known position at all, e.g. a word recomposed after backspace. */
    private fun unplaced(word: String): List<TypedKey> = word.map { TypedKey(it, null) }

    // --- a dictionary of a few hundred ordinary words, with realistic counts ---------------

    private val common: List<String> = """
        a about after again against all also am an and another any are around as at back be because
        become became been before being below between both but by call came can cant come could day
        did do does doing done down each even every feel few find first for form from get give go
        good got great had half hand has have he head hear help her here high him his how however
        i if in into is it its just know large last later leave left let life like little long look
        made make man many may me mean men might more most much must my near need never new next no
        not now number of off often old on once one only open or other our out over own part people
        place point put read real really reason right room run said same saw say see seem set she
        should show side since small so some something state still such take tell than that the
        their them then there these they thing think this those though thought three through time to
        today together too took two under until up us use used very want was way we well went were
        what when where which while who why will with without word work world would write year yes
        yet you your hello hell held hero here here's don't dont wont can't cant im i'm its it's
        we're that's you're didn't doesn't wasn't hello helping helped helps until untils
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Counts fall off with rank, as in real text, so "the" dwarfs a misspelling. */
    private fun ordinary(): List<Pair<String, Long>> {
        val counts = LinkedHashMap<String, Long>()
        common.distinct().forEachIndexed { rank, word -> counts[word] = 2_000_000L / (rank + 10L) }
        counts += mapOf(
            "become" to 90_000L,
            "hello" to 60_000L,
            "because" to 300_000L,
            "until" to 120_000L,
            "form" to 60_000L,
            "from" to 40_000L,
            "dont" to 400L,
            "don't" to 150_000L,
            "can't" to 80_000L,
            "teh" to 500L,
            "thw" to 2L,
        )
        return counts.map { (word, count) -> word to count }
    }

    private fun vocabularyOf(words: List<Pair<String, Long>>): Vocabulary = Vocabulary(listOf("<s>") + words.map { it.first })

    private fun modelOf(words: List<Pair<String, Long>>): UnigramModel =
        UnigramModel(vocabularyOf(words), longArrayOf(1L) + words.map { it.second }.toLongArray())

    private fun decoderOf(words: List<Pair<String, Long>>, spatial: SpatialModel = GaussianSpatial()): BeamTypingDecoder {
        val model = modelOf(words)
        return BeamTypingDecoder(SimpleWordIndex(model), model, spatial)
    }

    // --- the tests -------------------------------------------------------------------------

    @Test
    fun `swapped adjacent letters come back in order`() {
        val decoder = decoderOf(ordinary())
        val best = decoder.decode(typed("teh"), intArrayOf(), 3).first()
        assertEquals("the", best.word)
        assertEquals(CandidateKind.CORRECTION, best.kind)
    }

    @Test
    fun `a skipped doubled letter is a cheap omission`() {
        val decoder = decoderOf(ordinary())
        val best = decoder.decode(typed("helo"), intArrayOf(), 3).first()
        assertEquals("hello", best.word)
    }

    @Test
    fun `a tap just off its key still reads as the intended word`() {
        val decoder = decoderOf(ordinary())
        // The thumb aimed for "e" but the "w" key caught it.
        val best = decoder.decode(typedOff("thw", 'w', 0.45f), intArrayOf(), 3).first()
        assertEquals("the", best.word)
    }

    @Test
    fun `a distant transposition is still corrected`() {
        val decoder = decoderOf(ordinary())
        val best = decoder.decode(typed("becuase"), intArrayOf(), 3).first()
        assertEquals("because", best.word)
    }

    @Test
    fun `apostrophe words come back apostrophised as exact`() {
        val decoder = decoderOf(ordinary())
        val best = decoder.decode(typed("dont"), intArrayOf(), 3).first()
        assertEquals("don't", best.word)
        assertEquals(CandidateKind.EXACT, best.kind)
    }

    @Test
    fun `an unfinished word offers completions`() {
        val decoder = decoderOf(ordinary())
        val candidates = decoder.decode(typed("beca"), intArrayOf(), 12)
        val because = candidates.firstOrNull { it.word == "because" }
        assertEquals(CandidateKind.COMPLETION, because?.kind)
    }

    @Test
    fun `the literal reading is always offered`() {
        val decoder = decoderOf(ordinary())
        for (word in listOf("beca", "becuase", "thw")) {
            val candidates = decoder.decode(typed(word), intArrayOf(), 12)
            assertTrue(candidates.toString(), candidates.any { it.word == word && it.kind == CandidateKind.LITERAL })
        }
    }

    @Test
    fun `context can flip which of two neighbours wins`() {
        val words = ordinary()
        val model = modelOf(words)
        val index = SimpleWordIndex(model)
        val spatial = GaussianSpatial()
        val plain = BeamTypingDecoder(index, model, spatial)
        val vocabulary = model.vocabulary
        val boosted = BoostedModel(model, mapOf((vocabulary.id("the") to vocabulary.id("from")) to 5f))
        val contextual = BeamTypingDecoder(index, boosted, spatial)
        val input = typed("form")
        assertEquals("form", plain.decode(input, intArrayOf(), 3).first().word)
        assertEquals("from", contextual.decode(input, intArrayOf(vocabulary.id("the")), 3).first().word)
    }

    @Test
    fun `touches may be unknown`() {
        val decoder = decoderOf(ordinary())
        assertEquals("the", decoder.decode(unplaced("teh"), intArrayOf(), 3).first().word)
        assertEquals("hello", decoder.decode(unplaced("helo"), intArrayOf(), 3).first().word)
    }

    @Test
    fun `a five thousand word vocabulary decodes in well under thirty milliseconds`() {
        val words = ordinary() + filler(5_000)
        val ms = averageDecodeMillis(words, typed("because"))
        assertTrue("took $ms ms", ms < 30)
    }

    @Test
    fun `a fifty thousand word vocabulary stays near the keystroke budget`() {
        val words = ordinary() + filler(50_000)
        val ms = averageDecodeMillis(words, typed("because"))
        assertTrue("took $ms ms", ms < 25)
    }

    /** Words of no particular language, to fill a vocabulary to a realistic size. */
    private fun filler(count: Int): List<Pair<String, Long>> {
        val random = java.util.Random(11)
        val out = ArrayList<Pair<String, Long>>(count)
        while (out.size < count) {
            val length = 3 + random.nextInt(8)
            val word = buildString(length) { repeat(length) { append('a' + random.nextInt(26)) } }
            out += word to (random.nextInt(900) + 2).toLong()
        }
        return out
    }

    private fun averageDecodeMillis(words: List<Pair<String, Long>>, input: List<TypedKey>, runs: Int = 20): Double {
        val decoder = decoderOf(words)
        repeat(2) { decoder.decode(input, intArrayOf(), 12) }
        val start = System.nanoTime()
        repeat(runs) { decoder.decode(input, intArrayOf(), 12) }
        return (System.nanoTime() - start) / runs / 1_000_000.0
    }

    /** A unigram model with a couple of context boosts: just enough to prove context can rerank. */    private class BoostedModel(
        private val base: UnigramModel,
        private val boosts: Map<Pair<Int, Int>, Float>,
    ) : LanguageModel {
        override val vocabulary: Vocabulary get() = base.vocabulary

        override fun logProb(word: Int, context: IntArray): Float {
            val previous = context.lastOrNull() ?: Vocabulary.UNKNOWN
            return base.logProb(word, context) + (boosts[previous to word] ?: 0f)
        }

        override fun unigramLogProb(word: Int): Float = base.unigramLogProb(word)

        override fun predict(context: IntArray, max: Int): List<ScoredWord> = base.predict(context, max)
    }
}
