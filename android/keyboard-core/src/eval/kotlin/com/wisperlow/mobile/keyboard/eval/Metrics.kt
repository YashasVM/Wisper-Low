package com.wisperlow.mobile.keyboard.eval

/**
 * Per-preset aggregates over all sentences and seeds of one engine.
 *
 * The input classification is engine-independent: the simulator hit-tests the planned
 * events through the board's own hit test *without a dictionary* to recover the literal
 * the user actually typed, including Gaussian tap mislandings. A word is [cleanWords]
 * only when that raw literal spells the intended word; [noisyWords] is everything else.
 * That is what keeps a wrong key press from being reported as the engine corrupting a
 * correctly typed word ([falseCorrections]) — the bug the old plan-level `noisy` flag
 * hid. [touchMissWords] isolates the runtime mislandings (the raw literal differs from
 * the planned one) from planned mistakes and misspelling substitutions.
 */
class PresetStats(val stripUser: Boolean) {
    var intendedWords = 0
    var intendedCorrect = 0
    var intendedExact = 0
    var cleanWords = 0
    var cleanFalseCorrections = 0
    var noisyWords = 0
    var noisyRecovered = 0
    var touchMissWords = 0
    var planWrongWords = 0
    var baseKeystrokes = 0
    var actualKeystrokes = 0
    var predictionsSeen = 0
    var predictionTop1 = 0
    var predictionTop3 = 0
    val latencies = ArrayList<Long>()

    /** Adds one sentence run; [base] is the ideal keystroke count for the sentence. */
    fun add(run: Simulator.SentenceRun, base: Int) {
        intendedWords += run.intendedWords
        intendedCorrect += run.intendedCorrect
        intendedExact += run.intendedExact
        cleanWords += run.cleanWords
        cleanFalseCorrections += run.cleanFalseCorrections
        noisyWords += run.noisyWords
        noisyRecovered += run.noisyRecovered
        touchMissWords += run.touchMissWords
        planWrongWords += run.planWrongWords
        predictionsSeen += run.predictionsSeen
        predictionTop1 += run.predictionTop1
        predictionTop3 += run.predictionTop3
        if (stripUser) {
            baseKeystrokes += base
            actualKeystrokes += run.keystrokes
        }
    }

    /** Ratio helpers; 0.0 when the denominator is 0. */
    fun ratio(hit: Int, total: Int): Double = if (total == 0) 0.0 else hit.toDouble() / total

    fun accuracy(): Double = ratio(intendedCorrect, intendedWords)
    fun accuracyExact(): Double = ratio(intendedExact, intendedWords)
    /** Of words the user typed correctly, the share the engine wrongly changed. */
    fun falseCorrections(): Double = ratio(cleanFalseCorrections, cleanWords)
    /** Of words whose raw literal was wrong, the share the engine still got right. */
    fun recovery(): Double = ratio(noisyRecovered, noisyWords)
    /** Raw tap mislandings among words the plan typed cleanly: the touch-miss word-error rate. */
    fun touchMissRate(): Double = ratio(touchMissWords, intendedWords)
    /** Every word whose raw literal differed from the intended one, whatever the cause. */
    fun typedWrongRate(): Double = ratio(noisyWords, intendedWords)
    fun savings(): Double = if (baseKeystrokes == 0) 0.0 else (baseKeystrokes - actualKeystrokes).toDouble() / baseKeystrokes
    fun nextTop1(): Double = ratio(predictionTop1, predictionsSeen)
    fun nextTop3(): Double = ratio(predictionTop3, predictionsSeen)

    /** Strip latency in microseconds: mean, then 95th percentile. */
    fun latency(): Pair<Double, Double> {
        if (latencies.isEmpty()) return 0.0 to 0.0
        val sorted = latencies.sorted()
        val mean = sorted.average() / 1_000.0
        val p95 = sorted[((sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1))] / 1_000.0
        return mean to p95
    }
}

/** The spelling-corpus results, engine-level and so independent of the noise preset. */
class SpellingStats {
    var pairs = 0
    var fixed = 0
    var cleanChecks = 0
    var cleanLeftAlone = 0

    fun fixRate(): Double = if (pairs == 0) 0.0 else fixed.toDouble() / pairs
    fun leftAloneRate(): Double = if (cleanChecks == 0) 0.0 else cleanLeftAlone.toDouble() / cleanChecks
}
