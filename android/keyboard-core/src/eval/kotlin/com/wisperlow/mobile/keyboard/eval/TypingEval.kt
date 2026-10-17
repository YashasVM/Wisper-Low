package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import java.io.File
import kotlin.math.round

/**
 * The deterministic typing simulator's entry point: runs the engine under test over
 * the evaluation sentences with seeded typist presets, prints a Markdown table, and
 * writes the numbers as JSON. Gradle task :keyboard-core:typingEval.
 *
 * Flags: --quick (normal and sloppy presets, at most 3 seeds), --engine=baseline,
 * --seeds=N, --out=file.json (also writes baseline.md beside it and prints deltas
 * against that file's previous contents), --data=dir, --assets=dir, --limit=N.
 */
object TypingEval {
    data class Config(
        val quick: Boolean,
        val engine: String,
        val seeds: Int,
        val out: File?,
        val data: File,
        val assets: File,
        val limit: Int?,
    )

    data class PresetReport(
        val id: String,
        val words: Int,
        val accuracy: Double,
        val accuracyExact: Double,
        val falseCorrections: Double,
        val recovery: Double,
        val savings: Double,
        val stripAccuracy: Double,
        val nextTop1: Double,
        val nextTop3: Double,
        val latencyMeanUs: Double,
        val latencyP95Us: Double,
    )

    data class Report(
        val engine: String,
        val sentences: Int,
        val words: Int,
        val misspellings: Int,
        val seeds: Int,
        val spellingFixRate: Double,
        val spellingLeftAloneRate: Double,
        val presets: List<PresetReport>,
    )

    fun run(cfg: Config): Report {
        require(cfg.engine == "baseline") { "unknown engine \"${cfg.engine}\"; today only \"baseline\" exists" }
        val all = EvalData.sentences(cfg.data)
        require(all.isNotEmpty()) { "no sentences under ${cfg.data}" }
        val sentences = if (cfg.limit == null) all else all.take(cfg.limit)
        val pairs = EvalData.misspellings(cfg.data)
        val misspellings = pairs.associate { (wrong, right) -> Lexicon.key(right) to wrong }
        val wordCount = sentences.sumOf { s -> EvalData.tokens(s).count { t -> t.hasWord } }
        val presets = if (cfg.quick) listOf(TypistPreset.NORMAL, TypistPreset.SLOPPY) else TypistPreset.entries.toList()
        val seeds = if (cfg.quick) cfg.seeds.coerceAtMost(3) else cfg.seeds

        // Spelling behaviour does not depend on the noise preset, so it runs once per seed.
        val spelling = SpellingStats()
        repeat(seeds) {
            runSpelling(cfg, pairs, spelling)
        }

        val presetsOut = presets.map { preset ->
            val plain = PresetStats(stripUser = false)
            val strip = PresetStats(stripUser = true)
            val latencies = ArrayList<Long>()
            repeat(seeds) { seed ->
                val plainEngine = BaselineEngine(cfg.assets)
                val stripEngine = BaselineEngine(cfg.assets)
                sentences.forEachIndexed { i, sentence ->
                    val intended = EvalData.tokens(sentence)
                    val plan = Typist(preset).plan(intended, misspellings, seed * 1_000L + i)
                    val base = intended.sumOf(::keystrokesFor)
                    plain.add(Simulator.run(plainEngine, plan, intended, stripUser = false, latencies = null), base)
                    strip.add(Simulator.run(stripEngine, plan, intended, stripUser = true, latencies = latencies), base)
                }
            }
            strip.latencies.addAll(latencies)
            val (mean, p95) = strip.latency()
            PresetReport(
                id = preset.id,
                words = plain.intendedWords,
                accuracy = plain.accuracy(),
                accuracyExact = plain.accuracyExact(),
                falseCorrections = plain.falseCorrections(),
                recovery = plain.recovery(),
                savings = strip.savings(),
                stripAccuracy = strip.accuracy(),
                nextTop1 = strip.nextTop1(),
                nextTop3 = strip.nextTop3(),
                latencyMeanUs = mean,
                latencyP95Us = p95,
            )
        }
        return Report(
            engine = cfg.engine,
            sentences = sentences.size,
            words = wordCount,
            misspellings = pairs.size,
            seeds = seeds,
            spellingFixRate = spelling.fixRate(),
            spellingLeftAloneRate = spelling.leftAloneRate(),
            presets = presetsOut,
        )
    }

    /** Types the misspelling pairs and the right words exactly, as the service sees them. */
    private fun runSpelling(cfg: Config, pairs: List<Pair<String, String>>, stats: SpellingStats) {
        val fix = BaselineEngine(cfg.assets)
        for ((wrong, right) in pairs) {
            fix.reset()
            wrong.forEach { fix.type(it.toString()) }
            fix.space()
            stats.pairs++
            if (lastWord(fix) == right.lowercase()) stats.fixed++
        }
        val clean = BaselineEngine(cfg.assets)
        for ((_, right) in pairs) {
            clean.reset()
            right.forEach { clean.type(it.toString()) }
            clean.space()
            stats.cleanChecks++
            if (lastWord(clean) == right.lowercase()) stats.cleanLeftAlone++
        }
    }

    private fun lastWord(engine: BaselineEngine): String =
        engine.text().trim().split(Regex("\\s+")).lastOrNull()?.let { normExact(it) } ?: ""

    // ---- output ----

    fun jsonOf(r: Report): Map<String, Any?> = linkedMapOf(
        "engine" to r.engine,
        "corpus" to linkedMapOf(
            "sentences" to r.sentences,
            "words" to r.words,
            "misspellings" to r.misspellings,
            "seeds" to r.seeds,
        ),
        "spelling" to linkedMapOf(
            "misspellingFixRate" to r.spellingFixRate,
            "cleanLeftAloneRate" to r.spellingLeftAloneRate,
        ),
        "presets" to r.presets.associate { p ->
            p.id to linkedMapOf(
                "words" to p.words,
                "accuracyNoStrip" to p.accuracy,
                "accuracyNoStripExact" to p.accuracyExact,
                "falseCorrectionRate" to p.falseCorrections,
                "recoveryRate" to p.recovery,
                "keystrokeSavings" to p.savings,
                "accuracyStrip" to p.stripAccuracy,
                "nextWordTop1" to p.nextTop1,
                "nextWordTop3" to p.nextTop3,
                "stripLatencyMeanUs" to p.latencyMeanUs,
                "stripLatencyP95Us" to p.latencyP95Us,
            )
        },
    )

    fun markdown(r: Report): String = buildString {
        appendLine("# Typing eval: ${r.engine}")
        appendLine()
        appendLine("${r.sentences} sentences, ${r.words} words, ${r.misspellings} misspelling pairs, ${r.seeds} seed(s).")
        appendLine()
        appendLine("| metric |" + r.presets.joinToString("") { " ${it.id} |" })
        appendLine("| --- |" + r.presets.joinToString("") { " --- |" })
        fun row(label: String, value: (PresetReport) -> String) =
            appendLine("| $label |" + r.presets.joinToString("") { " ${value(it)} |" })
        row("no-strip word accuracy", { pct(it.accuracy) })
        row("no-strip word accuracy (exact)", { pct(it.accuracyExact) })
        row("false corrections on clean words", { pct(it.falseCorrections) })
        row("recovery on noisy words", { pct(it.recovery) })
        row("keystroke savings, ideal strip user", { pct(it.savings) })
        row("ideal strip user word accuracy", { pct(it.stripAccuracy) })
        row("next-word top-1 / top-3", { "${pct(it.nextTop1)} / ${pct(it.nextTop3)}" })
        row("strip latency mean / p95 (us)", { "${us(it.latencyMeanUs)} / ${us(it.latencyP95Us)}" })
        row("misspellings typed exactly, fixed", { pct(r.spellingFixRate) })
        row("clean words left alone", { pct(r.spellingLeftAloneRate) })
    }

    private fun pct(d: Double): String = "${(d * 100).round(1)}%"
    private fun us(d: Double): String = "${d.round(1)} us"

    private fun Double.round(digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return round(this * f) / f
    }

    /** Deltas against a previously committed baseline, when one is around. */
    fun printDeltas(prior: Map<*, *>?, now: Report) {
        val old = prior?.get("presets") as? Map<*, *> ?: return
        for (p in now.presets) {
            val before = old[p.id] as? Map<*, *> ?: continue
            for ((key, label) in deltaLabels) {
                val was = (before[key] as? Number)?.toDouble() ?: continue
                val isNow = when (key) {
                    "accuracyNoStrip" -> p.accuracy
                    "accuracyNoStripExact" -> p.accuracyExact
                    "falseCorrectionRate" -> p.falseCorrections
                    "recoveryRate" -> p.recovery
                    "keystrokeSavings" -> p.savings
                    "accuracyStrip" -> p.stripAccuracy
                    "nextWordTop1" -> p.nextTop1
                    "nextWordTop3" -> p.nextTop3
                    "stripLatencyMeanUs" -> p.latencyMeanUs
                    else -> p.latencyP95Us
                }
                val d = isNow - was
                if (d.round(4) != 0.0) {
                    val unit = if (key.endsWith("Us")) "${us(was)} -> ${us(isNow)}" else "${pct(was)} -> ${pct(isNow)}"
                    val delta = if (key.endsWith("Us")) "${d.round(1)} us" else "${(d * 100).round(1)} pts"
                    println("delta ${p.id} $label: $unit ($delta)")
                }
            }
        }
    }

    private val deltaLabels = linkedMapOf(
        "accuracyNoStrip" to "no-strip word accuracy",
        "accuracyNoStripExact" to "no-strip word accuracy (exact)",
        "falseCorrectionRate" to "false corrections",
        "recoveryRate" to "recovery",
        "keystrokeSavings" to "keystroke savings",
        "accuracyStrip" to "strip word accuracy",
        "nextWordTop1" to "next-word top-1",
        "nextWordTop3" to "next-word top-3",
        "stripLatencyMeanUs" to "strip latency mean",
        "stripLatencyP95Us" to "strip latency p95",
    )

    fun parse(args: Array<String>): Config {
        var quick = false
        var engine = "baseline"
        var seeds = 5
        var out: File? = null
        var data = File("src/eval/resources/en")
        var assets: File? = null
        var limit: Int? = null
        for (a in args) {
            when {
                a == "--quick" -> quick = true
                a.startsWith("--engine=") -> engine = a.substringAfter('=')
                a.startsWith("--seeds=") -> seeds = a.substringAfter('=').toInt()
                a.startsWith("--out=") -> out = File(a.substringAfter('='))
                a.startsWith("--data=") -> data = File(a.substringAfter('='))
                a.startsWith("--assets=") -> assets = File(a.substringAfter('='))
                a.startsWith("--limit=") -> limit = a.substringAfter('=').toInt()
                else -> throw IllegalArgumentException("unknown argument \"$a\"")
            }
        }
        val assetsDir = assets
            ?: System.getProperty("wisperlow.assets")?.let(::File)
            ?: File("../app/src/main/assets")
        return Config(quick, engine, seeds, out, data, assetsDir, limit)
    }
}

fun main(args: Array<String>) {
    val cfg = TypingEval.parse(args)
    val report = TypingEval.run(cfg)
    val md = TypingEval.markdown(report)
    println(md)
    // Deltas compare against the committed baseline, or the file --out is about to overwrite.
    val prior = cfg.out?.takeIf { it.exists() }?.readText() ?: File("eval/baseline.json").takeIf { it.exists() }?.readText()
    TypingEval.printDeltas(prior?.let { runCatching { Json.parse(it) }.getOrNull() as? Map<*, *> }, report)
    cfg.out?.let { out ->
        out.parentFile?.mkdirs()
        out.writeText(TypingEval.jsonOf(report).let(Json::encode))
        File(out.parentFile, "baseline.md").writeText(md)
        println("Wrote ${out.canonicalPath} and ${File(out.parentFile, "baseline.md").canonicalPath}")
    }
}
