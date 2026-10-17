package com.wisperlow.mobile.keyboard.eval

import com.wisperlow.mobile.keyboard.Lexicon
import java.io.File
import kotlin.math.round

/**
 * The deterministic typing simulator's entry point: runs the engine under test over
 * the evaluation sentences with seeded typist presets, prints a Markdown table, and
 * writes the numbers as JSON. Gradle task :keyboard-core:typingEval.
 *
 * Flags: --quick (normal and sloppy presets, at most 3 seeds), --engine=baseline|smart,
 * --model=FILE (a WLM1 n-gram for the smart engine), --seeds=N, --out=file.json (also
 * writes baseline.md beside it and prints deltas when the prior report's fingerprint
 * matches), --data=dir, --assets=dir, --limit=N, --trace=file.json (evaluation-only
 * per-token raw -> composing -> committed diagnostics; changes no metric).
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
        val model: File?,
        /** Where to write the evaluation-only per-token diagnostic trace, when asked. */
        val trace: File?,
    )

    data class PresetReport(
        val id: String,
        val words: Int,
        val accuracy: Double,
        val accuracyExact: Double,
        val falseCorrections: Double,
        val recovery: Double,
        val typedWrong: Double,
        val touchMiss: Double,
        val savings: Double,
        val stripAccuracy: Double,
        val nextTop1: Double,
        val nextTop3: Double,
        val latencyMeanUs: Double,
        val latencyP95Us: Double,
    )

    data class Report(
        val engine: String,
        val model: String,
        val fingerprint: RunFingerprint,
        val sentences: Int,
        val words: Int,
        val misspellings: Int,
        val seeds: Int,
        val spellingFixRate: Double,
        val spellingLeftAloneRate: Double,
        val presets: List<PresetReport>,
    )

    fun run(cfg: Config): Report {
        require(cfg.engine == "baseline" || cfg.engine == "smart") {
            "unknown engine \"${cfg.engine}\"; use --engine=baseline or --engine=smart"
        }
        val all = EvalData.sentences(cfg.data)
        require(all.isNotEmpty()) { "no sentences under ${cfg.data}" }
        val sentences = if (cfg.limit == null) all else all.take(cfg.limit)
        val pairs = EvalData.misspellings(cfg.data)
        val misspellings = pairs.associate { (wrong, right) -> Lexicon.key(right) to wrong }
        val wordCount = sentences.sumOf { s -> EvalData.tokens(s).count { t -> t.hasWord } }
        val presets = if (cfg.quick) listOf(TypistPreset.NORMAL, TypistPreset.SLOPPY) else TypistPreset.entries.toList()
        val seeds = if (cfg.quick) cfg.seeds.coerceAtMost(3) else cfg.seeds
        val model = modelLabel(cfg)
        val fingerprint = RunFingerprint.of(sentences, pairs, seeds, presets, cfg.engine, model, cfg.assets)

        // Spelling behaviour does not depend on the noise preset, so it runs once per seed.
        val spelling = SpellingStats()
        repeat(seeds) {
            runSpelling(cfg, pairs, spelling)
        }

        val traces = ArrayList<TokenTrace>()
        val presetsOut = presets.map { preset ->
            val plain = PresetStats(stripUser = false)
            val strip = PresetStats(stripUser = true)
            val latencies = ArrayList<Long>()
            repeat(seeds) { seed ->
                val plainEngine = newEngine(cfg)
                val stripEngine = newEngine(cfg)
                sentences.forEachIndexed { i, sentence ->
                    val intended = EvalData.tokens(sentence)
                    val plan = Typist(preset).plan(intended, misspellings, seed * 1_000L + i)
                    val base = intended.sumOf(::keystrokesFor)
                    plain.add(
                        Simulator.run(
                            plainEngine, plan, intended, stripUser = false, latencies = null,
                            trace = traces, preset = preset.id, seed = seed * 1_000L + i, sentence = i,
                        ),
                        base,
                    )
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
                typedWrong = plain.typedWrongRate(),
                touchMiss = plain.touchMissRate(),
                savings = strip.savings(),
                stripAccuracy = strip.accuracy(),
                nextTop1 = strip.nextTop1(),
                nextTop3 = strip.nextTop3(),
                latencyMeanUs = mean,
                latencyP95Us = p95,
            )
        }
        // Diagnostic only: the per-token raw -> composing -> committed chain, when asked.
        cfg.trace?.let { file ->
            file.parentFile?.mkdirs()
            file.writeText(Json.encode(traces.map { it.asJson() }))
        }
        return Report(
            engine = cfg.engine,
            model = model,
            fingerprint = fingerprint,
            sentences = sentences.size,
            words = wordCount,
            misspellings = pairs.size,
            seeds = seeds,
            spellingFixRate = spelling.fixRate(),
            spellingLeftAloneRate = spelling.leftAloneRate(),
            presets = presetsOut,
        )
    }

    private fun newEngine(cfg: Config): EngineUnderTest = when (cfg.engine) {
        "smart" -> SmartEngine.create(cfg.assets, cfg.model)
        else -> BaselineEngine(cfg.assets)
    }

    private fun modelLabel(cfg: Config): String = when (cfg.engine) {
        "smart" -> cfg.model?.let { "ngram:${it.name}" } ?: "unigram:en.txt+en_modern.txt"
        else -> "lexicon:en.txt+en_modern.txt"
    }

    /** Types the misspelling pairs and the right words exactly, as the service sees them. */
    private fun runSpelling(cfg: Config, pairs: List<Pair<String, String>>, stats: SpellingStats) {
        val fix = newEngine(cfg)
        for ((wrong, right) in pairs) {
            fix.reset()
            wrong.forEach { fix.type(it.toString()) }
            fix.space()
            stats.pairs++
            if (lastWord(fix) == right.lowercase()) stats.fixed++
        }
        val clean = newEngine(cfg)
        for ((_, right) in pairs) {
            clean.reset()
            right.forEach { clean.type(it.toString()) }
            clean.space()
            stats.cleanChecks++
            if (lastWord(clean) == right.lowercase()) stats.cleanLeftAlone++
        }
    }

    private fun lastWord(engine: EngineUnderTest): String =
        engine.text().trim().split(Regex("\\s+")).lastOrNull()?.let { normExact(it) } ?: ""

    // ---- output ----

    fun jsonOf(r: Report): Map<String, Any?> = linkedMapOf(
        "engine" to r.engine,
        "model" to r.model,
        "fingerprint" to r.fingerprint.asJson(),
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
                "typedWrongRate" to p.typedWrong,
                "touchMissRate" to p.touchMiss,
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
        appendLine("**Model:** ${r.model}  ")
        appendLine("**Data:** ${r.sentences} sentences, ${r.words} words, ${r.misspellings} misspelling pairs, ${r.seeds} seed(s)  ")
        appendLine("**Fingerprint:** sentences `${short(r.fingerprint.sentencesSha)}`, " +
            "misspellings `${short(r.fingerprint.pairsSha)}`, assets `${short(r.fingerprint.assetsSha)}`")
        appendLine()
        appendLine("| metric |" + r.presets.joinToString("") { " ${it.id} |" })
        appendLine("| --- |" + r.presets.joinToString("") { " --- |" })
        fun row(label: String, value: (PresetReport) -> String) =
            appendLine("| $label |" + r.presets.joinToString("") { " ${value(it)} |" })
        row("no-strip word accuracy", { pct(it.accuracy) })
        row("no-strip word accuracy (exact)", { pct(it.accuracyExact) })
        row("false autocorrections (typed correctly, changed)", { pct(it.falseCorrections) })
        row("recovery on mistyped words", { pct(it.recovery) })
        row("raw typed-wrong word rate", { pct(it.typedWrong) })
        row("  of which touch mislandings", { pct(it.touchMiss) })
        row("keystroke savings, ideal strip user", { pct(it.savings) })
        row("ideal strip user word accuracy", { pct(it.stripAccuracy) })
        row("next-word top-1 / top-3", { "${pct(it.nextTop1)} / ${pct(it.nextTop3)}" })
        row("strip latency mean / p95 (us)", { "${us(it.latencyMeanUs)} / ${us(it.latencyP95Us)}" })
        row("misspellings typed exactly, fixed", { pct(r.spellingFixRate) })
        row("clean words left alone", { pct(r.spellingLeftAloneRate) })
        appendLine()
        appendLine("Latency is a single-machine measurement and is not comparable across runs or machines. " +
            "Metrics come from this corpus subset only; no engine-vs-engine parity is claimed.")
    }

    private fun short(sha: String): String = sha.take(12)

    private fun pct(d: Double): String = "${(d * 100).round(1)}%"
    private fun us(d: Double): String = "${d.round(1)} us"

    private fun Double.round(digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return round(this * f) / f
    }

    /** Whether deltas may be shown, why not, and the lines when they may. */
    data class DeltaReport(val allowed: Boolean, val reason: String?, val lines: List<String>)

    /**
     * Deltas against a previously written report, only when that report's fingerprint
     * matches this run's. A missing fingerprint or a changed fingerprint suppresses
     * every delta with a reason; latency is never diffed because it varies with the
     * machine, not with the engine.
     */
    fun deltaReport(prior: Map<*, *>?, now: Report): DeltaReport {
        if (prior == null) return DeltaReport(false, "no prior report to compare against", emptyList())
        val old = RunFingerprint.fromJson(prior["fingerprint"])
            ?: return DeltaReport(false, "prior report has no fingerprint (written before reproducibility checks)", emptyList())
        val mismatch = now.fingerprint.mismatch(old)
        if (mismatch != null) return DeltaReport(false, "input or config changed ($mismatch)", emptyList())
        val presets = prior["presets"] as? Map<*, *> ?: return DeltaReport(false, "prior report has no presets", emptyList())
        val lines = ArrayList<String>()
        for (p in now.presets) {
            val before = presets[p.id] as? Map<*, *> ?: continue
            for ((key, label) in deltaLabels) {
                val was = (before[key] as? Number)?.toDouble() ?: continue
                val isNow = metric(p, key) ?: continue
                val d = isNow - was
                if (d.round(4) != 0.0) {
                    lines += "delta ${p.id} $label: ${pct(was)} -> ${pct(isNow)} (${(d * 100).round(1)} pts)"
                }
            }
        }
        return DeltaReport(true, null, lines)
    }

    private fun metric(p: PresetReport, key: String): Double? = when (key) {
        "accuracyNoStrip" -> p.accuracy
        "accuracyNoStripExact" -> p.accuracyExact
        "falseCorrectionRate" -> p.falseCorrections
        "recoveryRate" -> p.recovery
        "typedWrongRate" -> p.typedWrong
        "touchMissRate" -> p.touchMiss
        "keystrokeSavings" -> p.savings
        "accuracyStrip" -> p.stripAccuracy
        "nextWordTop1" -> p.nextTop1
        "nextWordTop3" -> p.nextTop3
        else -> null
    }

    private val deltaLabels = linkedMapOf(
        "accuracyNoStrip" to "no-strip word accuracy",
        "accuracyNoStripExact" to "no-strip word accuracy (exact)",
        "falseCorrectionRate" to "false autocorrections",
        "recoveryRate" to "recovery",
        "typedWrongRate" to "raw typed-wrong rate",
        "touchMissRate" to "touch mislanding rate",
        "keystrokeSavings" to "keystroke savings",
        "accuracyStrip" to "strip word accuracy",
        "nextWordTop1" to "next-word top-1",
        "nextWordTop3" to "next-word top-3",
    )

    fun parse(args: Array<String>): Config {
        var quick = false
        var engine = "baseline"
        var seeds = 5
        var out: File? = null
        var data = File("src/eval/resources/en")
        var assets: File? = null
        var limit: Int? = null
        var model: File? = null
        var trace: File? = null
        for (a in args) {
            when {
                a == "--quick" -> quick = true
                a.startsWith("--engine=") -> engine = a.substringAfter('=')
                a.startsWith("--model=") -> model = File(a.substringAfter('='))
                a.startsWith("--seeds=") -> seeds = a.substringAfter('=').toInt()
                a.startsWith("--out=") -> out = File(a.substringAfter('='))
                a.startsWith("--data=") -> data = File(a.substringAfter('='))
                a.startsWith("--assets=") -> assets = File(a.substringAfter('='))
                a.startsWith("--limit=") -> limit = a.substringAfter('=').toInt()
                a.startsWith("--trace=") -> trace = File(a.substringAfter('='))
                else -> throw IllegalArgumentException("unknown argument \"$a\"")
            }
        }
        val assetsDir = assets
            ?: System.getProperty("wisperlow.assets")?.let(::File)
            ?: File("../app/src/main/assets")
        return Config(quick, engine, seeds, out, data, assetsDir, limit, model, trace)
    }
}

fun main(args: Array<String>) {
    val cfg = TypingEval.parse(args)
    val report = TypingEval.run(cfg)
    println(TypingEval.markdown(report))
    // Deltas compare against the committed report, or the file --out is about to overwrite,
    // and only when the fingerprint matches.
    val priorText = cfg.out?.takeIf { it.exists() }?.readText()
        ?: File("eval/baseline.json").takeIf { it.exists() }?.readText()
    val prior = priorText?.let { runCatching { Json.parse(it) }.getOrNull() as? Map<*, *> }
    val delta = TypingEval.deltaReport(prior, report)
    if (delta.allowed) {
        delta.lines.forEach(::println)
    } else {
        println("deltas suppressed: ${delta.reason}")
    }
    cfg.out?.let { out ->
        out.parentFile?.mkdirs()
        out.writeText(TypingEval.jsonOf(report).let(Json::encode))
        val md = File(out.parentFile, out.nameWithoutExtension + ".md")
        md.writeText(TypingEval.markdown(report))
        println("Wrote ${out.canonicalPath} and ${md.canonicalPath}")
    }
}
