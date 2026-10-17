package com.wisperlow.mobile.keyboard.eval

/**
 * Executes one planned sentence against an engine, in one of two user modes. Both
 * modes count keystrokes the same way (one per tap, typed popup character or pick);
 * only the strip user consults the suggestion strip, and it picks the intended word
 * whenever the strip shows it while the word is being composed, or the typed word's
 * revert slot right after a wrong autocorrection. Predictions (the strip before a
 * word starts) are measured for metric (c) but never picked, so strip-completion
 * savings and next-word quality stay separate numbers.
 */
object Simulator {

    /** One sentence's outcome. Word outcomes line up with the intended token list. */
    class SentenceRun(
        val keystrokes: Int,
        val picks: Int,
        val intendedWords: Int,
        val intendedCorrect: Int,
        val intendedExact: Int,
        val cleanWords: Int,
        val cleanFalseCorrections: Int,
        val noisyWords: Int,
        val noisyRecovered: Int,
        val predictionsSeen: Int,
        val predictionTop1: Int,
        val predictionTop3: Int,
    )

    /**
     * Runs the sentence; [latencies], when given, collects every strip computation's
     * duration in nanoseconds. The strip is only consulted in strip-user mode: the
     * no-strip user is defined by ignoring it.
     */
    fun run(
        engine: BaselineEngine,
        planned: List<PlannedToken>,
        intended: List<Tok>,
        stripUser: Boolean,
        latencies: MutableList<Long>?,
    ): SentenceRun {
        engine.reset()
        var keystrokes = 0
        var picks = 0
        var intendedWords = 0
        var intendedCorrect = 0
        var intendedExact = 0
        var cleanWords = 0
        var cleanFalseCorrections = 0
        var noisyWords = 0
        var noisyRecovered = 0
        var predictionsSeen = 0
        var predictionTop1 = 0
        var predictionTop3 = 0

        planned.forEachIndexed { i, p ->
            val tok = intended[i]
            if (!tok.hasWord) return@forEachIndexed
            intendedWords++

            // The strip's prediction of this word before typing starts.
            if (stripUser && i > 0) {
                val predicted = engine.strip()
                latencies?.add(engine.lastStripNanos)
                if (engine.composing.isEmpty() && engine.revertible == null) {
                    val keys = predicted.take(3).map(::normKey)
                    predictionsSeen++
                    if (keys.firstOrNull() == tok.key) predictionTop1++
                    if (tok.key in keys) predictionTop3++
                }
            }

            var picked = false
            for (event in p.events) {
                when (event) {
                    is KeyEvent.Tap -> engine.tap(event.x, event.y)
                    is KeyEvent.Typed -> engine.type(event.text)
                }
                keystrokes++
                if (stripUser && !picked && engine.composing.isNotEmpty()) {
                    val strip = engine.strip()
                    latencies?.add(engine.lastStripNanos)
                    val at = strip.indexOfFirst { normKey(it) == normKey(p.intended) }
                    if (at >= 0) {
                        // Picking the target ends the word: the strip user stops typing it.
                        engine.pick(at)
                        keystrokes++
                        picks++
                        picked = true
                        break
                    }
                }
            }
            if (!picked) {
                // Punctuation and the word-ending space, exactly as the typist aims them.
                for (event in p.punctEvents) {
                    when (event) {
                        is KeyEvent.Tap -> engine.tap(event.x, event.y)
                        is KeyEvent.Typed -> engine.type(event.text)
                    }
                    keystrokes++
                }
            }
            // Space ends the word unless a pick just did (it commits its own trailing space);
            // punctuation typed into an empty field still needs the separator after it.
            if (engine.composing.isNotEmpty() || p.punct.isNotEmpty()) {
                engine.space()
                keystrokes++
                // A wrong autocorrection puts what was typed back in the strip; a
                // perfect user taps it instead of retyping. Never pick a plain
                // prediction here: with no undo pending, picking inserts a duplicate.
                if (stripUser) {
                    val strip = engine.strip()
                    latencies?.add(engine.lastStripNanos)
                    val revert = engine.revertible
                    if (revert != null && strip.firstOrNull() == revert && normKey(revert) == normKey(p.intended)) {
                        engine.pick(0)
                        keystrokes++
                        picks++
                    }
                }
            }
        }

        // Score the finished field against the intended tokens, position by position.
        val out = engine.text().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        planned.forEachIndexed { i, p ->
            val tok = intended[i]
            if (!tok.hasWord) return@forEachIndexed
            val committed = out.getOrNull(i) ?: return@forEachIndexed
            if (normKey(committed) == normKey(tok.word)) intendedCorrect++
            if (normExact(committed) == normExact(tok.word)) intendedExact++
            if (!p.noisy) {
                cleanWords++
                if (normKey(committed) != normKey(tok.word)) cleanFalseCorrections++
            } else {
                noisyWords++
                if (normKey(committed) == normKey(tok.word)) noisyRecovered++
            }
        }
        return SentenceRun(
            keystrokes, picks, intendedWords, intendedCorrect, intendedExact,
            cleanWords, cleanFalseCorrections, noisyWords, noisyRecovered,
            predictionsSeen, predictionTop1, predictionTop3,
        )
    }
}
