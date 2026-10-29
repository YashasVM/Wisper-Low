package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.KeyboardEngine
import com.wisperlow.mobile.keyboard.lm.NgramModel
import java.io.File

/** The production keyboard engine driven with literal tap hits and original touch positions. */
class SmartEngine(
    val engine: KeyboardEngine,
    /** How the model was obtained, for the report's fingerprint: word list or model file. */
    val modelLabel: String,
) : SessionEngine(engine) {

    override fun resolveTap(landed: Char, x: Float, y: Float): Char = landed

    companion object {
        /** A model-free engine over the merged word list, matching LexiconRepository's build. */
        fun fromWordList(assets: File, nextModel: File? = null): SmartEngine = SmartEngine(
            KeyboardEngine.fromWordList(
                EvalLexicon.entries(assets), EvalBoard.letterCenters,
                predictionModel = nextModel?.let(NgramModel::read),
            ),
            "unigram:en.txt+en_modern.txt" + (nextModel?.let { "+next:${it.name}" } ?: ""),
        )

        /** An engine over a compiled WLM1 n-gram model file, given by --model. */
        fun fromModel(file: File): SmartEngine = SmartEngine(
            KeyboardEngine(NgramModel.read(file), EvalBoard.letterCenters),
            "ngram:${file.name}",
        )

        /** The engine the CLI asks for: a model file when given, else the word list. */
        fun create(assets: File, model: File?, nextModel: File? = null): SmartEngine =
            if (model == null) fromWordList(assets, nextModel) else fromModel(model)
    }
}
