package com.wisperlow.mobile.keyboard

import com.wisperlow.mobile.keyboard.eval.EvalField
import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.ScoredWord
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new engine, checked against the real word list and against the behaviour
 * [LexiconTest] already pins. Cases the port cannot reproduce are left failing on
 * purpose rather than weakened; see the report that ships with this change.
 */
class KeyboardEngineTest {
    private val assets = File(System.getProperty("wisperlow.assets") ?: "../app/src/main/assets")

    private val merged by lazy {
        val base = Lexicon.parse(File(assets, "dict/en.txt").readLines().asSequence())
        val modern = Lexicon.parse(File(assets, "dict/en_modern.txt").readLines().asSequence())
        Lexicon.merge(base, modern)
    }

    private val centers by lazy {
        val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, numberRow = false)
        val placed = KeyboardGeometry.layout(rows, width = 1080f, rowHeight = 150f, side = 10f)
        val units = KeyboardGeometry.units(placed, rowHeight = 150f, side = 10f)
        KeyboardGeometry.letterCenters(placed, units)
    }

    private val engine by lazy { KeyboardEngine.fromWordList(merged, centers) }

    private fun fix(typed: String, previous: String? = null) = engine.suggest(typed, previous).autoCorrect

    private fun modelOf(words: List<Pair<String, Long>>): UnigramModel {
        val distinct = LinkedHashMap<String, Long>()
        for ((word, count) in words) if (word.isNotBlank() && word !in distinct) distinct[word] = count
        val surfaces = listOf(Vocabulary.SENTENCE_START) + distinct.keys
        return UnigramModel(Vocabulary(surfaces), longArrayOf(1L) + distinct.values.toLongArray())
    }

    private fun engineOf(words: List<Pair<String, Long>>) = KeyboardEngine(modelOf(words), centers)

    private fun centerOf(c: Char): KeyCenter = centers.first { it.char == c }

    /** Checks every expected fix, so one test reports every word that went wrong. */
    private fun assertFixes(expected: Map<String, String>) {
        val failures = expected.mapNotNull { (typed, want) ->
            val got = fix(typed)
            if (got != want) "$typed: got ${got ?: "null"}, want $want" else null
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    /** Checks that none of [words] is replaced. */
    private fun assertUnchanged(words: List<String>) {
        val failures = words.mapNotNull { word -> fix(word)?.let { "$word -> $it" } }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    // --- the two new behaviours ------------------------------------------------------------

    @Test
    fun `a tap landing on w near e in thw corrects to the`() {
        val touches = listOf(Qwerty.touch('t', 0f, 0f)!!, Qwerty.touch('h', 0f, 0f)!!, Qwerty.touch('w', 0.45f, 0f)!!)
        assertEquals("the", engine.suggest("thw", touches = touches).autoCorrect)
    }

    @Test
    fun `resolveKey after he between t and y picks y when the model favours hey`() {
        val boosted = BoostedModel(modelOf(merged), mapOf("hey" to 10f))
        val boostedEngine = KeyboardEngine(boosted, centers)
        val t = centerOf('t')
        val y = centerOf('y')
        val touch = Touch((t.x + y.x) / 2f, (t.y + y.y) / 2f)
        assertEquals('y', boostedEngine.resolveKey(touch, 't', t, "he", null))
    }

    /** Types the same rectangle-hit keys the IME resolves before its session sees them. */
    private fun resolvedWord(e: KeyboardEngine, raw: String, offsets: Map<Int, Pair<Float, Float>> = emptyMap()): Pair<String, List<Touch?>> {
        var composing = ""
        val touches = raw.mapIndexed { i, c ->
            val (dx, dy) = offsets[i] ?: (0f to 0f)
            val touch = Qwerty.touch(c, dx, dy)!!
            val landed = centerOf(c.lowercaseChar())
            composing += e.resolveKey(touch, c, landed, composing, null)
            touch
        }
        return composing to touches
    }

    @Test
    fun `word-level policy restores a known word after a greedy edge tap`() {
        val (composing, touches) = resolvedWord(engine, "dinner", mapOf(1 to (0.49f to 0f)))
        assertEquals("donner", composing)
        assertEquals("dinner", engine.suggest(composing, touches = touches).autoCorrect)
    }

    @Test
    fun `raw title case survives a greedy key prediction`() {
        val e = engineOf(listOf("Linda" to 100L, "out" to 1_000_000L, "ounda" to 1L))
        val (composing, touches) = resolvedWord(e, "Linda", mapOf(0 to (0f to -0.49f)))
        assertEquals("Oinda", composing)
        assertEquals("Linda", e.suggest(composing, touches = touches).autoCorrect)
    }

    @Test
    fun `trusted raw spelling survives a greedy key prediction`() {
        val e = engineOf(listOf("do" to 1_000_000L, "donner" to 1000L))
        e.trust("dinner")
        val (composing, touches) = resolvedWord(e, "dinner", mapOf(1 to (0.49f to 0f)))
        assertEquals("donner", composing)
        assertEquals("dinner", e.suggest(composing, touches = touches).autoCorrect)
    }

    @Test
    fun `helpful resolver recovery of an unknown raw word stays available`() {
        val e = engineOf(listOf("around" to 1_000_000L, "about" to 10L))
        val (composing, touches) = resolvedWord(e, "arounx", mapOf(5 to (0f to -0.49f)))
        assertEquals("around", composing)
        val suggestion = e.suggest(composing, touches = touches)
        assertEquals("around", suggestion.autoCorrect ?: composing)
    }

    @Test
    fun `word reconciliation still lets the calibrated policy fix a rare raw word`() {
        val e = engineOf(listOf("fir" to 1L, "for" to 1_000_000L))
        val (composing, touches) = resolvedWord(e, "fir", mapOf(1 to (0.49f to 0f)))
        assertEquals("for", composing)
        val suggestion = e.suggest(composing, touches = touches)
        assertEquals("for", suggestion.autoCorrect ?: composing)
    }

    @Test
    fun `undo of word reconciliation restores visible composing without teaching a reverse fix`() {
        val e = engineOf(listOf("dinner" to 100_000L, "do" to 1_000_000L, "donner" to 1000L))
        val (composing, touches) = resolvedWord(e, "dinner", mapOf(1 to (0.49f to 0f)))
        assertEquals("donner", composing)
        val field = EvalField()
        val session = TypingSession(field, { e })
        composing.forEachIndexed { i, c -> session.type(c.toString(), touches[i]) }
        assertEquals("donner", field.toString())

        session.space()
        assertEquals("dinner ", field.toString())
        session.backspace()
        assertEquals("donner", field.toString())
        assertTrue(e.exportLearned().fixes.isEmpty())
        session.space()
        assertEquals("donner ", field.toString())
        assertTrue(e.exportLearned().fixes.isEmpty())
    }

    // --- LexiconTest cases, ported ---------------------------------------------------------

    @Test
    fun `ported fixes common typos`() {
        assertFixes(
            mapOf(
                "teh" to "the",
                "becuase" to "because",
                "woudl" to "would",
                "helo" to "hello",
                "peopel" to "people",
                "definately" to "definitely",
                "tommorow" to "tomorrow",
                "untill" to "until",
                "realy" to "really",
            ),
        )
    }

    @Test
    fun `ported adds apostrophes and capitals`() {
        assertFixes(
            mapOf(
                "dont" to "don't",
                "im" to "I'm",
                "cant" to "can't",
                "i" to "I",
                "monday" to "Monday",
                "Dont" to "Don't",
            ),
        )
    }

    @Test
    fun `ported leaves real words and ambiguous ones alone`() {
        assertUnchanged(listOf("its", "well", "the", "The", "cat", "form", "x"))
    }

    @Test
    fun `ported does not correct a word that is still being typed`() {
        assertNull(fix("tomor"))
        assertTrue(engine.suggest("tomor").words.toString(), engine.suggest("tomor").words.contains("tomorrow"))
    }

    @Test
    fun `ported keeps typed capitals on corrections`() {
        assertFixes(mapOf("Teh" to "The", "TEH" to "THE"))
    }

    @Test
    fun `ported suggests completions with the typed word first`() {
        val s = engine.suggest("hap")
        assertTrue(s.words.toString(), s.words.contains("hap"))
        assertTrue(s.words.toString(), s.words.any { it == "happy" || it == "happen" || it == "happened" })
    }

    @Test
    fun `ported learned words stop being corrected and get suggested`() {
        val e = engineOf(listOf("the" to 1000L, "then" to 500L))
        e.trust("thw")
        assertNull(e.suggest("thw").autoCorrect)
        e.learn("Wisperlow", null)
        e.learn("Wisperlow", null)
        assertTrue(e.suggest("wisp").words.toString(), e.suggest("wisp").words.contains("Wisperlow"))
    }

    @Test
    fun `a word the user merely learned never replaces a real word`() {
        // Ambient typing fills the user lexicon; "you"/"day" then rival "your"/"dad" on the
        // user-ordering scale. They may be suggested, but must not autocorrect a real word.
        val e = engineOf(listOf("your" to 500L, "you" to 5000L, "dad" to 500L, "day" to 5000L))
        repeat(3) { e.learn("you", null) }
        repeat(3) { e.learn("day", null) }
        e.learn("your", null) // typed before, as in the failing session
        e.learn("dad", null)
        assertNull(e.suggest("your").autoCorrect)
        assertNull(e.suggest("dad").autoCorrect)
    }

    @Test
    fun `a title-cased dictionary word keeps its spelling`() {
        // The word list holds "ben"; the user tapped the capital of a person's name. That is
        // the same dictionary word, so it must not be treated as an unknown literal to fix.
        val e = engineOf(listOf("ben" to 500L, "been" to 500L, "be" to 5000L))
        assertNull(e.suggest("Ben").autoCorrect)
    }

    @Test
    fun `ported predicts next words from what the user typed`() {
        val e = engineOf(listOf("good" to 10L, "morning" to 10L))
        e.learn("good", null)
        e.learn("morning", "good")
        assertEquals(listOf("morning"), e.predict("good"))
    }

    @Test
    fun `ported short words are not mangled`() {
        assertNull(fix("epp"))
    }

    @Test
    fun `ported completions take the middle slot while a word is unfinished`() {
        val words = engine.suggest("hap").words
        assertTrue(words.toString(), words[1] == "happy" || words[1] == "happen" || words[1] == "happened")
    }

    @Test
    fun `ported leaves slang alone`() {
        assertUnchanged(listOf("lowkey", "ngl", "lmao", "idc", "wyd", "rizz", "sus", "bruh", "tryna", "delulu", "fr", "istg", "bestie", "ok"))
    }

    @Test
    fun `ported knows developer words and their spelling`() {
        assertFixes(mapOf("github" to "GitHub", "api" to "API", "typescript" to "TypeScript", "Github" to "GitHub", "refacotr" to "refactor"))
        assertUnchanged(listOf("repo", "localhost", "cursor"))
    }

    @Test
    fun `ported stretched words and laughter are not typos`() {
        assertUnchanged(listOf("sooo", "yesss", "noooo", "hahaha", "lmaooo", "omggg", "hiii"))
        assertTrue(engine.suggest("sooo").words.toString(), engine.suggest("sooo").words.contains("so"))
    }

    @Test
    fun `ported capitals inside a word are deliberate`() {
        assertUnchanged(listOf("useEffect", "myVar"))
    }

    @Test
    fun `ported where the key was touched breaks ties`() {
        val e = engineOf(listOf("ten" to 1_000_000L, "tan" to 1_000_000L))
        assertNull(e.suggest("tsn").autoCorrect)
        val nearE = listOf(null, Qwerty.touch('s', 0.35f, -0.45f), null)
        assertEquals("ten", e.suggest("tsn", touches = nearE).autoCorrect)
        val nearA = listOf(null, Qwerty.touch('s', -0.45f, 0f), null)
        assertEquals("tan", e.suggest("tsn", touches = nearA).autoCorrect)
    }

    @Test
    fun `ported fixes the user taught are applied`() {
        val e = engineOf(listOf("the" to 1000L, "was" to 5000L))
        e.learnFix("wsp", "wassup")
        assertEquals("wassup", e.suggest("wsp").autoCorrect)
        assertEquals("Wassup", e.suggest("Wsp").autoCorrect)
        assertTrue(e.suggest("wass").words.toString(), e.suggest("wass").words.contains("wassup"))
    }

    @Test
    fun `ported a fix that turns one real word into another needs teaching twice`() {
        val e = engineOf(listOf("form" to 5000L, "from" to 50000L))
        e.learnFix("form", "from")
        assertNull(e.suggest("form").autoCorrect)
        e.learnFix("form", "from")
        assertEquals("from", e.suggest("form").autoCorrect)
    }

    @Test
    fun `ported undoing a taught fix drops it`() {
        val e = engineOf(listOf("the" to 1000L))
        e.learnFix("wsp", "wassup")
        e.trust("wsp")
        assertNull(e.suggest("wsp").autoCorrect)
    }

    @Test
    fun `ported one learned word can be forgotten`() {
        val e = engineOf(listOf("the" to 1000L))
        e.learn("skrrt", null)
        e.learn("skrrt", null)
        assertTrue(e.isLearned("skrrt"))
        assertTrue(e.suggest("skr").words.contains("skrrt"))
        e.forget("skrrt")
        assertTrue(!e.isLearned("skrrt"))
        assertTrue(!e.suggest("skr").words.contains("skrrt"))
        assertTrue(!e.isLearned("the"))
    }

    @Test
    fun `ported taught fixes survive saving`() {
        val e = engineOf(listOf("the" to 1000L))
        e.learnFix("wsp", "wassup")
        val copy = engineOf(listOf("the" to 1000L))
        copy.importLearned(e.exportLearned())
        assertEquals("wassup", copy.suggest("wsp").autoCorrect)
    }

    @Test
    fun `ported brands that are everyday words keep the typed spelling`() {
        assertUnchanged(listOf("python", "discord", "twitch", "docker", "google"))
        assertTrue(engine.suggest("python").words.toString(), engine.suggest("python").words.contains("Python"))
    }

    @Test
    fun `ported forgetting a word drops its next-word predictions too`() {
        val e = engineOf(listOf("the" to 1000L))
        e.learn("private", null)
        e.learn("secret", "private")
        e.forget("private")
        assertTrue(e.predict("private").isEmpty())
        assertTrue(e.exportLearned().pairs.isEmpty())
    }

    @Test
    fun `ported learned words, pairs and fixes survive the file`() {
        val e = engineOf(listOf("the" to 1000L))
        e.learn("skrrt", null)
        e.learn("skrrt", "the")
        e.learnFix("wsp", "wassup")
        val file = File.createTempFile("learned", ".tsv").apply { deleteOnExit() }
        LearnedFile.write(file, e.exportLearned())
        val copy = engineOf(listOf("the" to 1000L))
        copy.importLearned(LearnedFile.read(file)!!)
        assertEquals("wassup", copy.suggest("wsp").autoCorrect)
        assertEquals(listOf("skrrt"), copy.predict("the"))
        assertTrue(copy.isLearned("skrrt"))
    }

    // --- rarity, centre slot and scaling (the d681e49 regressions) ------------------------

    @Test
    fun `a common word keeps the middle slot when a rival is more frequent`() {
        // "from" outranks "form" 100 to 1. A real word must not be silently replaced for
        // frequency alone, and it keeps the middle slot over any alternative.
        val e = engineOf(listOf("form" to 20_000L, "from" to 2_000_000L))
        val s = e.suggest("form")
        assertNull(s.autoCorrect)
        assertEquals("form", s.words[1])
        assertTrue(s.words.toString(), s.words.contains("from"))
    }

    @Test
    fun `a rare dictionary misspelling still fixes to the common word`() {
        // "helo" is in the word list, but far rarer than the "hello" a doubled letter away.
        val e = engineOf(listOf("helo" to 5L, "hello" to 1_000_000L))
        assertEquals("hello", e.suggest("helo").autoCorrect)
    }

    @Test
    fun `scaling every count does not change the suggestions`() {
        val words = listOf(
            "teh" to 4L, "the" to 400L, "cat" to 20L,
            "form" to 30L, "from" to 3_000L, "helo" to 2L, "hello" to 1_000L,
        )
        val base = engineOf(words)
        val scaled = engineOf(words.map { (word, count) -> word to count * 1_000L })
        for (typed in listOf("teh", "cat", "form", "helo")) {
            assertEquals("$typed words", base.suggest(typed).words, scaled.suggest(typed).words)
            assertEquals("$typed fix", base.suggest(typed).autoCorrect, scaled.suggest(typed).autoCorrect)
        }
    }

    @Test
    fun `a non-positive slot count yields nothing at all`() {
        assertEquals(Suggestions.None, engine.suggest("teh", max = 0))
        assertEquals(Suggestions.None, engine.suggest("teh", max = -1))
    }

    /** A model with some words made more likely, so the keyboard prefers them in context. */
    private class BoostedModel(private val base: LanguageModel, boosts: Map<String, Float>) : LanguageModel {
        override val vocabulary = base.vocabulary
        private val ids = boosts.mapKeys { (word, _) -> base.vocabulary.id(word) }

        override fun logProb(word: Int, context: IntArray): Float = base.logProb(word, context) + (ids[word] ?: 0f)

        override fun unigramLogProb(word: Int): Float = base.unigramLogProb(word) + (ids[word] ?: 0f)

        override fun predict(context: IntArray, max: Int): List<ScoredWord> = base.predict(context, max)
            .map { ScoredWord(it.id, it.logProb + (ids[it.id] ?: 0f)) }
            .sortedByDescending { it.logProb }
    }
}
