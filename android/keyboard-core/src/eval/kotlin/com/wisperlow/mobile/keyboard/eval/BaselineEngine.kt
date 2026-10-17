package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import java.io.File

/**
 * The word list every adapter is built from, merged exactly as the app's
 * LexiconRepository merges it: the base list with the modern one laid over it. One
 * loader here keeps the baseline and the new engine on the same dictionary, so an
 * old-vs-new comparison never differs by its input words.
 */
object EvalLexicon {
    fun entries(assets: File): List<Pair<String, Long>> {
        val base = File(assets, "dict/en.txt")
        val modern = File(assets, "dict/en_modern.txt")
        require(base.isFile && modern.isFile) { "word lists missing under $assets" }
        val entries = base.bufferedReader().useLines { Lexicon.parse(it) }
        return Lexicon.merge(entries, modern.bufferedReader().useLines { Lexicon.parse(it) })
    }
}

/**
 * Today's engine, wired the way the IME runs it: a [Lexicon] over the merged word list,
 * driven through the shared [SessionEngine] session, hit test and suggestion flow. It
 * types the key the tap landed on; the decoder-style touch reading lives in
 * [SmartEngine], so this stands in for the behaviour the app shipped before.
 */
class BaselineEngine(assets: File) : SessionEngine(Lexicon(EvalLexicon.entries(assets)))
