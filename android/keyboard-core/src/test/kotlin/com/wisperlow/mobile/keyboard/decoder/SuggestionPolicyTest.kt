package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.Suggestions
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestionPolicyTest {
    private val policy = SuggestionPolicy()

    private fun lit(word: String, score: Float, lm: Float = score) =
        Candidate(Vocabulary.UNKNOWN, word, score, 0f, lm, CandidateKind.LITERAL, 0)

    private fun exact(word: String, score: Float, wordId: Int = 101, lm: Float = score) =
        Candidate(wordId, word, score, 0f, lm, CandidateKind.EXACT, 0)

    /** A slip fix, as the decoder emits one after spatial and edit penalties. */
    private fun fix(word: String, score: Float, wordId: Int = 102, lm: Float = score) =
        Candidate(wordId, word, score, 0f, lm, CandidateKind.CORRECTION, 1)

    private fun comp(word: String, score: Float, wordId: Int = 103, lm: Float = score) =
        Candidate(wordId, word, score, 0f, lm, CandidateKind.COMPLETION, 1)

    private fun facts(
        known: Boolean,
        trusted: Boolean = false,
        strength: CorrectionStrength = CorrectionStrength.MODEST,
        logOdds: Float = 0f,
    ) = TypedFacts(known, trusted, strength, logOdds)

    private val teh = listOf(
        lit("teh", -20f),
        fix("the", -5f),
        comp("they", -9f),
    )

    private val form = listOf(
        lit("form", -6f),
        fix("from", -0.5f),
        comp("forms", -8f),
    )

    @Test
    fun `a clear slip on an unknown literal is corrected`() {
        val out = policy.decide("teh", teh, facts(known = false))
        assertEquals("the", out.autoCorrect)
        assertEquals(listOf("teh", "the", "they"), out.words)
    }

    @Test
    fun `a known real word keeps its slot when the margin is modest`() {
        val out = policy.decide("form", form, facts(known = true))
        assertNull(out.autoCorrect)
        // Literal known and unfixed: the best suggestion leads, the typed word sits in the middle.
        assertEquals(listOf("from", "form", "forms"), out.words)
    }

    @Test
    fun `aggressive mode accepts a smaller margin on known words`() {
        val out = policy.decide("form", form, facts(known = true, strength = CorrectionStrength.AGGRESSIVE))
        assertEquals("from", out.autoCorrect)
        assertEquals(listOf("form", "from", "forms"), out.words)
    }

    @Test
    fun `an exact match with an apostrophe is the target`() {
        val out = policy.decide(
            "dont",
            listOf(lit("dont", -20f), exact("don't", -8f), fix("font", -18f)),
            facts(known = false),
        )
        assertEquals("don't", out.autoCorrect)
        assertEquals(listOf("dont", "don't", "font"), out.words)
    }

    @Test
    fun `the typed title case carries to the correction`() {
        val out = policy.decide("Teh", teh, facts(known = false))
        assertEquals("The", out.autoCorrect)
        assertEquals(listOf("Teh", "The", "They"), out.words)
    }

    @Test
    fun `the typed shouting carries to the correction`() {
        val out = policy.decide("TEH", teh, facts(known = false))
        assertEquals("THE", out.autoCorrect)
        assertEquals(listOf("TEH", "THE", "THEY"), out.words)
    }

    @Test
    fun `inner capitals are never replaced`() {
        val out = policy.decide(
            "iPhone",
            listOf(lit("iPhone", -10f), fix("iphone", -0.5f), comp("iPhones", -9f)),
            facts(known = false, strength = CorrectionStrength.AGGRESSIVE),
        )
        assertNull(out.autoCorrect)
        // The lowercase-surface fix is the same word case-insensitively, so only the completion remains.
        assertEquals(listOf("iPhone", "iPhones"), out.words)
    }

    @Test
    fun `digits are never replaced`() {
        val out = policy.decide(
            "2day",
            listOf(lit("2day", -24f), fix("today", -4f)),
            facts(known = false),
        )
        assertNull(out.autoCorrect)
        assertEquals(listOf("2day", "today"), out.words)
    }

    @Test
    fun `a weak short fix is held back`() {
        val out = policy.decide(
            "ot",
            listOf(lit("ot", -8f), fix("of", -6f)),
            facts(known = false),
        )
        assertNull(out.autoCorrect)
        assertEquals(listOf("ot", "of"), out.words)
    }

    @Test
    fun `a taught-style short fix applies`() {
        val out = policy.decide(
            "ot",
            listOf(lit("ot", -26f), fix("of", -6f)),
            facts(known = false),
        )
        assertEquals("of", out.autoCorrect)
        assertEquals(listOf("ot", "of"), out.words)
    }

    @Test
    fun `the pronoun i is capitalised`() {
        val out = policy.decide(
            "i",
            listOf(lit("i", -23f), exact("I", -3f), comp("in", -10f)),
            facts(known = false),
        )
        assertEquals("I", out.autoCorrect)
        assertEquals(listOf("i", "I", "in"), out.words)
    }

    @Test
    fun `a rival as good as the target blocks the fix`() {
        val out = policy.decide(
            "teh",
            teh + fix("tech", -5.3f),
            facts(known = false),
        )
        assertNull(out.autoCorrect)
        assertEquals(listOf("teh", "the", "tech"), out.words)
    }

    @Test
    fun `an emerging completion blocks the fix`() {
        val out = policy.decide(
            "hel",
            listOf(lit("hel", -25f), fix("hell", -4f), comp("help", -1f)),
            facts(known = false),
        )
        assertNull(out.autoCorrect)
        assertEquals(listOf("hel", "help", "hell"), out.words)
    }

    @Test
    fun `autocorrect off keeps every word a suggestion`() {
        val out = policy.decide("teh", teh, facts(known = false, strength = CorrectionStrength.OFF))
        assertNull(out.autoCorrect)
        assertEquals(listOf("teh", "the", "they"), out.words)
    }

    @Test
    fun `a word the user owns is never corrected`() {
        val out = policy.decide("teh", teh, facts(known = false, trusted = true))
        assertNull(out.autoCorrect)
        assertEquals(listOf("teh", "the", "they"), out.words)
    }

    @Test
    fun `three letters demand extra margin`() {
        val candidates = listOf(lit("hte", -20f), fix("the", -17.9f))
        val modest = policy.decide("hte", candidates, facts(known = false))
        assertNull(modest.autoCorrect)
        val aggressive = policy.decide("hte", candidates, facts(known = false, strength = CorrectionStrength.AGGRESSIVE))
        assertEquals("the", aggressive.autoCorrect)
        assertEquals(listOf("hte", "the"), aggressive.words)
    }

    @Test
    fun `the extra three-letter margin does not reach four letters`() {
        val out = policy.decide("thne", listOf(lit("thne", -20f), fix("then", -18.2f)), facts(known = false))
        assertEquals("then", out.autoCorrect)
        assertEquals(listOf("thne", "then"), out.words)
    }

    @Test
    fun `duplicates differing only in case share one slot`() {
        val out = policy.decide(
            "vat",
            listOf(lit("vat", -20f), fix("cat", -3f), fix("Cat", -4f), comp("vats", -9f)),
            facts(known = false),
        )
        assertEquals("cat", out.autoCorrect)
        assertEquals(listOf("vat", "cat", "vats"), out.words)
    }

    @Test
    fun `the strip truncates to the requested slot count`() {
        val out = policy.decide("teh", teh, facts(known = false), slots = 2)
        assertEquals(listOf("teh", "the"), out.words)
        assertEquals("the", out.autoCorrect)
    }

    @Test
    fun `no slots means no suggestions and no correction`() {
        val out = policy.decide("teh", teh, facts(known = false), slots = 0)
        assertEquals(Suggestions.None, out)
        assertNull(out.autoCorrect)
    }

    @Test
    fun `a common word keeps the middle slot`() {
        val out = policy.decide("form", form, facts(known = true, logOdds = -6f))
        assertNull(out.autoCorrect)
        assertEquals(listOf("from", "form", "forms"), out.words)
    }

    @Test
    fun `a common word is not replaced when a rival is merely more frequent`() {
        // "from" outranks "form" by 5 nats, far past the unknown-literal margin of 1.5,
        // but the literal is a common word, so frequency alone may not replace it.
        val out = policy.decide(
            "form",
            listOf(lit("form", -5f), fix("from", 0f)),
            facts(known = true, logOdds = -5f),
        )
        assertNull(out.autoCorrect)
        assertEquals("form", out.words[1])
    }

    @Test
    fun `a rare dictionary word is corrected on a strong log-probability ratio`() {
        // Log-odds of -11 against the model's best word mark "helo" as a rare entry, and
        // the correction beats it by 7 nats (about 1000x): replace it.
        val out = policy.decide(
            "helo",
            listOf(lit("helo", -12f), fix("hello", -5f)),
            facts(known = true, logOdds = -11f),
        )
        assertEquals("hello", out.autoCorrect)
    }

    @Test
    fun `a rare dictionary word is kept when the correction's ratio is weak`() {
        // Same rare literal, but the correction leads by only 3 nats (about 20x): hold back.
        val out = policy.decide(
            "helo",
            listOf(lit("helo", -12f), fix("hello", -9f)),
            facts(known = true, logOdds = -11f),
        )
        assertNull(out.autoCorrect)
    }
}
