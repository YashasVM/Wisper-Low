package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.KeyboardEngine
import com.wisperlow.mobile.keyboard.lm.NgramModel
import java.io.File

/**
 * The new engine under test: the same [KeyboardEngine] the IME builds, driven through
 * the shared [SessionEngine] session. Where the legacy adapter types the key a tap
 * landed on, this one hands the engine the ORIGINAL touch on the same EvalBoard, so
 * [KeyboardEngine.resolveKey] gets to read a slip onto a neighbouring key before the
 * word is composed — the order WisperlowKeyboardService uses. The engine is built over
 * [EvalBoard.letterCenters], the canonical grid the resolver and spatial model share,
 * so a simulated touch is judged in the same coordinates as a real one.
 */
class SmartEngine(
    val engine: KeyboardEngine,
    /** How the model was obtained, for the report's fingerprint: word list or model file. */
    val modelLabel: String,
) : SessionEngine(engine) {

    override fun resolveTap(landed: Char, x: Float, y: Float): Char {
        val center = EvalBoard.centerByChar[landed.lowercaseChar()] ?: return landed
        // [x], [y] are the original board pixels; units.touch is the same canonical
        // conversion the hit test used, so the resolver sees where the thumb really fell.
        return engine.resolveKey(EvalBoard.units.touch(x, y), landed, center, composing, session.previousWord())
    }

    companion object {
        /** A model-free engine over the merged word list, matching LexiconRepository's build. */
        fun fromWordList(assets: File): SmartEngine = SmartEngine(
            KeyboardEngine.fromWordList(EvalLexicon.entries(assets), EvalBoard.letterCenters),
            "unigram:en.txt+en_modern.txt",
        )

        /** An engine over a compiled WLM1 n-gram model file, given by --model. */
        fun fromModel(file: File): SmartEngine = SmartEngine(
            KeyboardEngine(NgramModel.read(file), EvalBoard.letterCenters),
            "ngram:${file.name}",
        )

        /** The engine the CLI asks for: a model file when given, else the word list. */
        fun create(assets: File, model: File?): SmartEngine =
            if (model == null) fromWordList(assets) else fromModel(model)
    }
}
