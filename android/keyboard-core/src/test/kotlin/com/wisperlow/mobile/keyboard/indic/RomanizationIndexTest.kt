package com.wisperlow.mobile.keyboard.indic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of [RomanizationIndex] on tiny, hand-written fixtures. Real Aksharantar/Dakshina
 * data is owned by the data pipeline, so the expected outputs here are literals.
 */
class RomanizationIndexTest {
    private val hindi = RomanizationIndex(
        sequenceOf(
            "namaste\tनमस्ते\t120",
            "namaste\tनमस्ते\t30", // the same pair again: counts sum to 150
            "namaskar\tनमस्कार\t60",
            "accha\tअच्छा\t200",
            "achha\tअच्छा\t90",
            "achchha\tअच्छा\t10",
            "kal\tकल\t60",
            "kal\tकल\t40", // sums to 100, so कल beats काल even though 60 < 90
            "kal\tकाल\t90",
            "kam\tकम\t70",
            "kamal\tकमल\t1000",
            "kya\tक्या\t500",
            "kya\tकिया\t20",
        ),
    )

    private val kannada = RomanizationIndex(
        sequenceOf(
            "naanu\tನಾನು\t900",
            "nanu\tನಾನು\t100",
            "naan\tನಾನು\t50",
            "namaskara\tನಮಸ್ಕಾರ\t500",
            "namaskaragalu\tನಮಸ್ಕಾರಗಳು\t20",
            "hegiddiya\tಹೇಗಿದ್ದೀಯ\t400",
            "hegiddira\tಹೇಗಿದ್ದೀರ\t300",
            "hegiddu\tಹೇಗಿದ್ದು\t50",
            "neenu\tನೀನು\t800",
            "ninu\tನೀನು\t120",
        ),
    )

    @Test
    fun `ambiguous exact romanizations rank by summed count`() {
        assertEquals(listOf("कल", "काल"), hindi.suggest("kal"))
        assertEquals(listOf("क्या", "किया"), hindi.suggest("kya"))
        assertEquals(100L, hindi.suggestScored("kal").first().count)
    }

    @Test
    fun `variant spellings map to the same native word`() {
        assertEquals(listOf("अच्छा"), hindi.suggest("accha"))
        assertEquals(listOf("अच्छा"), hindi.suggest("achha"))
        assertEquals(listOf("अच्छा"), hindi.suggest("achchha"))
        assertEquals(listOf("ನಾನು"), kannada.suggest("naanu"))
        assertEquals(listOf("ನಾನು"), kannada.suggest("nanu"))
    }

    @Test
    fun `duplicate rows are summed into one candidate`() {
        val scored = hindi.suggestScored("namaste")
        assertEquals(1, scored.size)
        assertEquals(150L, scored.first().count)
        assertEquals(RomanizationMatch.EXACT, scored.first().kind)
        assertEquals("namaste", scored.first().roman)
    }

    @Test
    fun `prefix completion ranks by count with a length penalty`() {
        assertEquals(listOf("नमस्ते", "नमस्कार"), hindi.suggest("nama"))
        assertEquals(listOf("ನಮಸ್ಕಾರ", "ನಮಸ್ಕಾರಗಳು"), kannada.suggest("namaska"))
        assertEquals(listOf("ಹೇಗಿದ್ದೀಯ", "ಹೇಗಿದ್ದೀರ"), kannada.suggest("hegiddi"))
        assertEquals("namaste", hindi.suggestScored("nama").first().roman)
    }

    @Test
    fun `an exact match outranks a more frequent completion`() {
        // कम has 70 rows but कमल has 1000; the exact romanization still comes first.
        assertEquals(listOf("कम", "कमल"), hindi.suggest("kam"))
        val scored = hindi.suggestScored("kam", 2)
        assertEquals(RomanizationMatch.EXACT, scored[0].kind)
        assertEquals(RomanizationMatch.COMPLETION, scored[1].kind)
        assertEquals("कमल", scored[1].native)
    }

    @Test
    fun `native output is deduplicated across completions`() {
        // naa completes both naanu and naan, which share the native ನಾನು.
        assertEquals(listOf("ನಾನು"), kannada.suggest("naa"))
        // ach completes both achha and achchha, which share अच्छा.
        assertEquals(listOf("अच्छा"), hindi.suggest("ach"))
    }

    @Test
    fun `queries are case insensitive`() {
        assertEquals(listOf("नमस्ते"), hindi.suggest("NAMASTE"))
        assertEquals(listOf("नमस्ते"), hindi.suggest("Namaste"))
        assertEquals(listOf("नमस्कार"), hindi.suggest("NAMASKAR"))
        assertEquals(listOf("ನಾನು"), kannada.suggest("NAAN"))
    }

    @Test
    fun `native rows and queries are normalised to NFC`() {
        val composedRoman = "caf\u00e9" // café
        val decomposedRoman = "cafe\u0301" // cafe + combining acute
        val composedNative = "\u00e9"
        val decomposedNative = "e\u0301"
        assertNotEquals(composedRoman, decomposedRoman)
        assertNotEquals(composedNative, decomposedNative)

        val index = RomanizationIndex(sequenceOf("$decomposedRoman\t$decomposedNative\t5"))
        assertEquals(listOf(composedNative), index.suggest(composedRoman))
        assertEquals(listOf(composedNative), index.suggest(decomposedRoman))
    }

    @Test
    fun `single-edit typos are corrected only with no prefix match`() {
        assertEquals(listOf("नमस्ते"), hindi.suggest("namste")) // missing letter
        assertEquals(listOf("नमस्ते"), hindi.suggest("namsate")) // transposition
        assertEquals(listOf("नमस्ते"), hindi.suggest("namwste")) // neighbouring key
        assertEquals(listOf("ಹೇಗಿದ್ದೀಯ"), kannada.suggest("hegidiya"))
        val scored = hindi.suggestScored("namste")
        assertEquals(RomanizationMatch.TYPO, scored.first().kind)
        assertEquals(150L, scored.first().count)
    }

    @Test
    fun `an exact prefix is never treated as a typo`() {
        // "nam" is a real prefix of namaste/namaskar, so completions win, not the edit walk.
        assertEquals(RomanizationMatch.COMPLETION, hindi.suggestScored("nam").first().kind)
    }

    @Test
    fun `unknown, non-Latin and punctuation input return nothing`() {
        assertTrue(hindi.suggest("zzzz").isEmpty())
        assertTrue(hindi.suggest("nam-aste").isEmpty())
        assertTrue(hindi.suggest("नमस्ते").isEmpty())
        assertTrue(hindi.suggest("").isEmpty())
        assertTrue(hindi.suggest("   ").isEmpty())
        assertTrue(kannada.suggest("qwerty").isEmpty())
    }

    @Test
    fun `max bounds the result`() {
        assertEquals(listOf("कल"), hindi.suggest("kal", 1))
        assertEquals(listOf("ಹೇಗಿದ್ದೀಯ"), kannada.suggest("hegiddi", 1))
        assertTrue(hindi.suggest("kal", 0).isEmpty())
        assertTrue(hindi.suggest("kal", -2).isEmpty())
    }

    @Test
    fun `Hindi and Kannada words transliterate to their literal scripts`() {
        assertEquals(listOf("नमस्ते"), hindi.suggest("namaste"))
        assertEquals(listOf("अच्छा"), hindi.suggest("accha"))
        assertEquals(listOf("नमस्कार"), hindi.suggest("namaskar"))
        assertEquals("ನಮಸ್ಕಾರ", kannada.suggest("namaskara").first())
        assertEquals(listOf("ಹೇಗಿದ್ದೀಯ"), kannada.suggest("hegiddiya"))
    }

    @Test
    fun `a many-key index answers without scanning the vocabulary`() {
        val count = 50_000
        val rows = sequence {
            for (i in 0 until count) yield("${roman(i, 6)}\tn$i\t${i % 100 + 1}")
            yield("namaste\tनमस्ते\t500")
        }
        val index = RomanizationIndex(rows)
        assertEquals(count + 1, index.size)

        val exact = roman(0, 6)
        val prefix = exact.substring(0, 2)
        val typo = exact + "a"
        assertTrue(index.suggest(exact).isNotEmpty())
        assertTrue(index.suggest(prefix).isNotEmpty())
        assertTrue(index.suggest(typo).isNotEmpty())
        assertEquals(listOf("नमस्ते"), index.suggest("namaste"))

        fun time(label: String, query: String): Double {
            repeat(200) { index.suggest(query) } // warm up the JIT
            val iterations = 5_000
            val start = System.nanoTime()
            repeat(iterations) { index.suggest(query) }
            val perCall = (System.nanoTime() - start) / iterations / 1_000_000.0
            println("romanization $label over $count keys: ${"%.4f".format(perCall)} ms/call")
            return perCall
        }

        // JVM-host numbers only; no claim about device latency. Ceilings catch a full scan.
        assertTrue("exact too slow", time("exact", exact) < 20.0)
        assertTrue("prefix too slow", time("prefix", prefix) < 20.0)
        assertTrue("typo too slow", time("typo", typo) < 50.0)
    }

    /** A deterministic six-letter roman key for [i], unique while i < 26^6. */
    private fun roman(i: Int, length: Int): String {
        var value = i
        return buildString(length) {
            repeat(length) {
                append('a' + value % 26)
                value /= 26
            }
        }
    }
}
