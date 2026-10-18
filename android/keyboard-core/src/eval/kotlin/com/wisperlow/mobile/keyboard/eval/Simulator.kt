package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.KeyKind

/**
 * Executes one planned sentence against an engine, in one of two user modes. Both
 * modes count keystrokes the same way (one per tap, typed popup character or pick);
 * only the strip user consults the suggestion strip, and it picks the intended word
 * whenever the strip shows it while the word is being composed, or the typed word's
 * revert slot right after a wrong autocorrection. Predictions (the strip before a
 * word starts) are measured for metric (c) but never picked, so strip-completion
 * savings and next-word quality stay separate numbers.
 *
 * Scoring is per intended token, not by parsing the finished field position by
 * position. A tap that misses to a neighbouring key can change how many words reach
 * the field, and positional comparison silently shifts every word after that; here
 * each token records exactly what it added to the field when it was committed. A
 * word that never reaches the field counts as a failure, and the raw literal the
 * user typed is recovered with a dictionary-free control (see [rawLiteral]).
 */
object Simulator {

    /**
     * One sentence's outcome. Word outcomes line up with the intended token list:
     * [cleanWords] + [noisyWords] == [intendedWords], and [cleanFalseCorrections]
     * counts only words the user typed correctly that the engine changed anyway.
     */
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
        val touchMissWords: Int,
        val planWrongWords: Int,
        val predictionsSeen: Int,
        val predictionTop1: Int,
        val predictionTop3: Int,
    )

    /**
     * Runs the sentence; [latencies], when given, collects every strip computation's
     * duration in nanoseconds. The strip is only consulted in strip-user mode: the
     * no-strip user is defined by ignoring it.
     *
     * [trace], when given, collects one [TokenTrace] per intended token, tagged with
     * [preset]/[seed]/[sentence]. It is a diagnostic sink only: it adds no metric and
     * changes no behaviour, because every observation it records is already taken to
     * score the token.
     */
    fun run(
        engine: EngineUnderTest,
        planned: List<PlannedToken>,
        intended: List<Tok>,
        stripUser: Boolean,
        latencies: MutableList<Long>?,
        trace: MutableList<TokenTrace>? = null,
        preset: String = "",
        seed: Long = 0L,
        sentence: Int = -1,
    ): SentenceRun {
        require(planned.size == intended.size) { "plan and intended tokens must line up" }
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
        var touchMissWords = 0
        var planWrongWords = 0
        var predictionsSeen = 0
        var predictionTop1 = 0
        var predictionTop3 = 0

        // The field text before each token; the diff after it names this token's word.
        var before = engine.text()
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

            // The dictionary-free literal the user's events actually produce. A Gaussian
            // mislanding changes this, so the word is classified by the touch, not by the
            // plan's intent, and later only the engine's own commit is judged.
            val raw = StringBuilder()
            val steps = if (trace != null) ArrayList<String>() else null
            var picked = false
            for (event in p.events) {
                val rawCh = rawChar(event)
                raw.append(rawCh)
                val beforeComposing = if (steps != null) engine.composing else ""
                dispatch(engine, event)
                if (steps != null) {
                    val afterComposing = engine.composing
                    val delta = if (afterComposing.startsWith(beforeComposing)) {
                        afterComposing.substring(beforeComposing.length)
                    } else {
                        afterComposing
                    }
                    steps += if (delta.isEmpty()) rawCh.toString() else "$rawCh>$delta"
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
            val composingBefore = engine.composing
            val stripBefore = if (trace != null) engine.strip() else emptyList()
            if (!picked) {
                // Punctuation and the word-ending space, exactly as the typist aims them.
                for (event in p.punctEvents) {
                    dispatch(engine, event)
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
            val revertibleAfter = if (trace != null) engine.revertible else null

            // Exactly what this token added to the field, so extra or missing words from
            // earlier taps never shift this word's comparison.
            val after = engine.text()
            val committed = addedWord(before, after)
            before = after

            val clean = normKey(raw.toString()) == tok.key
            val correct = normKey(committed) == tok.key
            val exact = normExact(committed) == normExact(tok.word)
            trace?.add(
                TokenTrace(
                    preset, seed, sentence, tok.word, p.typed, raw.toString(),
                    steps ?: emptyList(), composingBefore, stripBefore, revertibleAfter,
                    committed, TokenTrace.classify(clean, correct, exact),
                ),
            )
            if (correct) intendedCorrect++
            if (exact) intendedExact++
            if (clean) {
                cleanWords++
                if (!correct) cleanFalseCorrections++
            } else {
                noisyWords++
                if (correct) noisyRecovered++
                if (normKey(raw.toString()) != normKey(p.typed)) touchMissWords++
                if (p.noisy) planWrongWords++
            }
        }
        return SentenceRun(
            keystrokes, picks, intendedWords, intendedCorrect, intendedExact,
            cleanWords, cleanFalseCorrections, noisyWords, noisyRecovered,
            touchMissWords, planWrongWords,
            predictionsSeen, predictionTop1, predictionTop3,
        )
    }

    private fun dispatch(engine: EngineUnderTest, event: KeyEvent) {
        when (event) {
            is KeyEvent.Tap -> engine.tap(event.x, event.y)
            is KeyEvent.Typed -> engine.type(event.text)
        }
    }

    /**
     * The literal one planned event puts into the word, with no dictionary involved:
     * precise text as typed, and a tap as the character of the key the board's own hit
     * test gives it. This is the clean typed-input control probe — it is how a wrong
     * key press is seen as a wrong key press instead of as a false autocorrection.
     */
    fun rawChar(event: KeyEvent): String = when (event) {
        is KeyEvent.Typed -> event.text
        is KeyEvent.Tap -> EvalBoard.hit(event.x, event.y)?.takeIf { it.kind == KeyKind.CHAR }?.text ?: ""
    }

    /**
     * The word [after] added over [before]: normally the field only grows, so the delta
     * is the word this token typed. Punctuation and a trailing space are trimmed the way
     * [normKey] trims the intended word. An empty result means the token never reached
     * the field (an omission) and is scored as a failure by the caller.
     */
    private fun addedWord(before: String, after: String): String {
        val delta = if (after.startsWith(before)) after.substring(before.length) else after
        val trimmed = delta.trimEnd()
        if (trimmed.isEmpty()) return ""
        return trimmed.split(Regex("\\s+")).last()
    }
}
