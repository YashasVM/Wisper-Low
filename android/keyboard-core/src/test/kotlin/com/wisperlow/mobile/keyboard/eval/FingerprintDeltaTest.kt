package com.wisperlow.mobile.keyboard.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reproducibility guard: deltas are only meaningful when the prior report was made
 * from the same ordered data, seeds, preset noise, engine, model and word lists. This
 * pins the refusal behaviour down with literal reasons instead of trusting a print.
 */
class FingerprintDeltaTest {

    private fun report(sentencesSha: String, accuracy: Double): TypingEval.Report {
        val engine = "baseline"
        val model = "lexicon:en.txt+en_modern.txt"
        return TypingEval.Report(
            engine = engine,
            model = model,
            fingerprint = RunFingerprint(
                sentencesSha = sentencesSha,
                pairsSha = "pairs",
                sentences = 40,
                pairs = 416,
                seeds = 1,
                presets = listOf("normal(sigma=0.25,omit=0.01,ins=0.005,swap=0.01,misspell=0.05)"),
                engine = engine,
                model = model,
                assetsSha = "assets",
            ),
            sentences = 40,
            words = 480,
            misspellings = 416,
            seeds = 1,
            spellingFixRate = 0.9,
            spellingLeftAloneRate = 1.0,
            presets = listOf(
                TypingEval.PresetReport(
                    id = "normal",
                    words = 480,
                    accuracy = accuracy,
                    accuracyExact = accuracy,
                    falseCorrections = 0.1,
                    recovery = 0.5,
                    typedWrong = 0.2,
                    touchMiss = 0.1,
                    savings = 0.3,
                    stripAccuracy = 0.9,
                    nextTop1 = 0.0,
                    nextTop3 = 0.0,
                    latencyMeanUs = 1.0,
                    latencyP95Us = 1.5,
                ),
            ),
        )
    }

    @Test
    fun `matching fingerprints allow a literal delta`() {
        val prior = TypingEval.jsonOf(report("same", 0.80))
        val delta = TypingEval.deltaReport(prior, report("same", 0.90))
        assertTrue(delta.allowed)
        assertNull(delta.reason)
        assertEquals(
            listOf("delta normal no-strip word accuracy: 80.0% -> 90.0% (10.0 pts)"),
            delta.lines.filter { it.contains("no-strip word accuracy:") },
        )
    }

    @Test
    fun `a changed dataset suppresses deltas with a reason`() {
        val prior = TypingEval.jsonOf(report("old-sample-40-sha", 0.80))
        val delta = TypingEval.deltaReport(prior, report("current-full-corpus-sha", 0.90))
        assertFalse(delta.allowed)
        assertTrue("reason was: ${delta.reason}", delta.reason!!.contains("input or config changed"))
        assertTrue("reason was: ${delta.reason}", delta.reason!!.contains("sentences"))
        assertTrue(delta.lines.isEmpty())
    }

    @Test
    fun `a prior report without a fingerprint suppresses deltas`() {
        val legacy = linkedMapOf<String, Any?>("engine" to "baseline", "presets" to emptyMap<String, Any?>())
        val delta = TypingEval.deltaReport(legacy, report("same", 0.9))
        assertFalse(delta.allowed)
        assertTrue(delta.reason!!.contains("no fingerprint"))
    }

    @Test
    fun `fingerprint mismatch names every differing field`() {
        val a = RunFingerprint("s1", "p1", 10, 20, 1, listOf("normal(x)"), "baseline", "m", "a")
        val b = RunFingerprint("s2", "p2", 10, 25, 3, listOf("sloppy(y)"), "smart", "m2", "a2")
        val text = a.mismatch(b)!!
        listOf("sentences", "misspellings", "seeds", "presets", "engine", "model", "assets").forEach {
            assertTrue("missing $it in \"$text\"", text.contains(it))
        }
    }
}
