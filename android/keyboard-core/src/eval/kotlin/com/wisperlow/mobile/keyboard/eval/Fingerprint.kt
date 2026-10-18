package com.wisperlow.mobile.keyboard.eval

import java.io.File
import java.security.MessageDigest

/**
 * A fingerprint of everything that changes what a run's numbers mean: the ordered
 * sentences actually used, the ordered misspelling pairs, the seeds, the exact preset
 * parameters (noise is not a label, it is these five numbers), the engine, the model
 * and the word-list assets. Two reports may only be diffed when their fingerprints
 * match; otherwise the runner refuses and says so, instead of printing deltas across
 * different data.
 */
data class RunFingerprint(
    val sentencesSha: String,
    val pairsSha: String,
    val sentences: Int,
    val pairs: Int,
    val seeds: Int,
    val presets: List<String>,
    val engine: String,
    val model: String,
    val assetsSha: String,
) {
    fun sameAs(other: RunFingerprint): Boolean = this == other

    /** One line naming the mismatching fields, or null when the fingerprints agree. */
    fun mismatch(other: RunFingerprint): String? {
        if (sameAs(other)) return null
        val differing = ArrayList<String>()
        if (sentencesSha != other.sentencesSha) differing += "sentences(${other.sentences}->$sentences)"
        if (pairsSha != other.pairsSha) differing += "misspellings(${other.pairs}->$pairs)"
        if (seeds != other.seeds) differing += "seeds(${other.seeds}->$seeds)"
        if (presets != other.presets) differing += "presets"
        if (engine != other.engine) differing += "engine(${other.engine}->$engine)"
        if (model != other.model) differing += "model"
        if (assetsSha != other.assetsSha) differing += "assets"
        return differing.joinToString(", ").ifEmpty { "unknown" }
    }

    fun asJson(): Map<String, Any?> = linkedMapOf(
        "sentencesSha256" to sentencesSha,
        "misspellingsSha256" to pairsSha,
        "sentences" to sentences,
        "misspellings" to pairs,
        "seeds" to seeds,
        "presets" to presets,
        "engine" to engine,
        "model" to model,
        "assetsSha256" to assetsSha,
    )

    companion object {
        /** Reads a fingerprint back out of a report's "fingerprint" object, or null if absent/old. */
        @Suppress("UNCHECKED_CAST")
        fun fromJson(value: Any?): RunFingerprint? {
            val m = value as? Map<String, Any?> ?: return null
            fun str(key: String): String? = m[key] as? String
            val sentencesSha = str("sentencesSha256") ?: return null
            val pairsSha = str("misspellingsSha256") ?: return null
            val presets = (m["presets"] as? List<*>)?.map { it.toString() } ?: return null
            val engine = str("engine") ?: return null
            val model = str("model") ?: return null
            val assetsSha = str("assetsSha256") ?: return null
            return RunFingerprint(
                sentencesSha = sentencesSha,
                pairsSha = pairsSha,
                sentences = (m["sentences"] as? Number)?.toInt() ?: 0,
                pairs = (m["misspellings"] as? Number)?.toInt() ?: 0,
                seeds = (m["seeds"] as? Number)?.toInt() ?: 0,
                presets = presets,
                engine = engine,
                model = model,
                assetsSha = assetsSha,
            )
        }

        /** Fingerprints the ordered sentences and pairs actually used by one run. */
        fun of(
            sentences: List<String>,
            pairs: List<Pair<String, String>>,
            seeds: Int,
            presets: List<TypistPreset>,
            engine: String,
            model: String,
            assets: File,
        ): RunFingerprint = RunFingerprint(
            sentencesSha = sha256(sentences.joinToString("\n")),
            pairsSha = sha256(pairs.joinToString("\n") { "${it.first}\t${it.second}" }),
            sentences = sentences.size,
            pairs = pairs.size,
            seeds = seeds,
            presets = presets.map(::signature),
            engine = engine,
            model = model,
            assetsSha = sha256Assets(assets),
        )

        /** The preset's full configuration, so two "normal"s with different noise never match. */
        fun signature(p: TypistPreset): String =
            "${p.id}(sigma=${p.sigma},omit=${p.omission},ins=${p.insertion},swap=${p.transposition},misspell=${p.misspelling})"

        fun sha256(text: String): String = digest { it.update(text.toByteArray(Charsets.UTF_8)) }

        fun sha256File(file: File): String = digest { it.update(file.readBytes()) }

        private fun sha256Assets(assets: File): String {
            val files = listOf(File(assets, "dict/en.txt"), File(assets, "dict/en_modern.txt"))
            return digest { md ->
                files.forEach { f ->
                    md.update(f.name.toByteArray(Charsets.UTF_8))
                    md.update(if (f.isFile) sha256File(f).toByteArray(Charsets.UTF_8) else "missing".toByteArray())
                }
            }
        }

        private inline fun digest(block: (MessageDigest) -> Unit): String {
            val md = MessageDigest.getInstance("SHA-256")
            block(md)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
