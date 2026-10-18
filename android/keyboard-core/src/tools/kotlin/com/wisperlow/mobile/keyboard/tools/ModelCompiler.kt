package com.wisperlow.mobile.keyboard.tools

import com.wisperlow.mobile.keyboard.lm.EstimatorConfig
import com.wisperlow.mobile.keyboard.lm.NgramCounts
import com.wisperlow.mobile.keyboard.lm.NgramEstimator
import com.wisperlow.mobile.keyboard.lm.NgramModel
import com.wisperlow.mobile.keyboard.lm.NgramModelWriter
import com.wisperlow.mobile.keyboard.lm.NgramTables
import com.wisperlow.mobile.keyboard.lm.TablesModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.system.exitProcess

private const val USAGE = """usage: ModelCompiler --counts DIR --out FILE --vocab N [--min1 C] [--min2 C] [--min3 C]

Reads unigrams.tsv / bigrams.tsv / trigrams.tsv (count_ngrams.py output) from DIR,
fits interpolated modified Kneser-Ney tables capped at N known words, writes them
as the compact WLM1 binary to FILE (atomically), reloads the file and verifies it
against the unquantized reference model on a deterministic sample of uni/bi/trigram
contexts, both listed and backed off, including unknown-word ids. Prints a report;
exits non-zero when anything is wrong rather than shipping an unverifiable model.
"""

/** Fixed seed: identical counts and flags give an identical file, sample, SHA-256 and report. */
private const val SAMPLE_SEED = 20261017L

/**
 * Gate bound on the sample's max |delta logprob|: the audited en-chat-v1 artifact measures
 * 0.0527 nats, so 0.1 allows the measured quantization error while rejecting larger drift.
 * A model over the bound is not published.
 */
private const val FAIL_MAX_QUANTIZATION_NATS = 0.1f

/** Contexts whose top-5 prediction lists are compared against the reference model. */
private const val DISPLAY_CONTEXTS = 12

/** How many representative prediction lines the report prints. */
private const val REPRESENTATIVE_PREDICTIONS = 4

/** Wrong caller input: the usage text goes next to this message and the exit code is 2. */
class CompilerUsageException(message: String) : Exception(message)

/** A concrete pipeline failure: exit 1; the report never claims a success it cannot show. */
class CompilerException(message: String) : Exception(message)

/** Flags validated at parse time, so estimation and writing never see junk. */
private data class Options(
    val countsDir: File,
    val outFile: File,
    val maxVocabulary: Int,
    val minUnigram: Int,
    val minBigram: Int,
    val minTrigram: Int,
) {
    fun floors(): EstimatorConfig = EstimatorConfig(minUnigram, minBigram, minTrigram)
}

/** What verification measured over the deterministic sample. */
private data class Verification(
    val contexts: Int,
    val pairs: Int,
    val maxAbsErrorNats: Float,
    val meanAbsErrorNats: Float,
    val top5ExactAgreements: Int,
    val predictContexts: Int,
    val referenceUnigramMass: Double,
    val loadedUnigramMass: Double,
    val predictionLines: List<String>,
)

fun main(args: Array<String>) {
    val started = System.nanoTime()
    try {
        run(args, started)
    } catch (e: CompilerUsageException) {
        System.err.println("error: ${e.message}")
        System.err.println(USAGE)
        exitProcess(2)
    } catch (e: CompilerException) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("error: ${e::class.simpleName}: ${e.message?.take(500)}")
        exitProcess(1)
    }
}

private fun progress(message: String) {
    System.err.println("[compile-model] $message")
}

private fun secs(nanos: Long): String = String.format(Locale.ROOT, "%.1f s", nanos / 1e9)

private fun f(x: Double): String = String.format(Locale.ROOT, "%.4f", x)

private fun run(argv: Array<String>, started: Long) {
    val options = parse(argv)
    progress("reading counts from ${options.countsDir}")
    val counts = NgramCounts.read(options.countsDir, options.maxVocabulary)
    if (counts.vocabulary.size < 2) {
        throw CompilerException("counts hold only ${counts.vocabulary.size} word(s); nothing to estimate")
    }
    progress("counts: ${counts.vocabulary.size} words, ${counts.bigramCounts.size} bigrams, ${counts.trigramCounts.size} trigrams")
    val afterRead = System.nanoTime()

    progress("estimating interpolated modified Kneser-Ney tables")
    val tables = NgramEstimator.estimate(counts, options.floors()) { order ->
        progress("WARNING: order $order discount fit is undefined or nonpositive/out-of-range; using D1=0.5 D2=1 D3+=1.5")
    }
    checkTables(tables, options)
    val afterEstimate = System.nanoTime()

    // The candidate bytes are written but NOT published until verification and the gate
    // pass, so a failed compile never overwrites the previous model and a crash never
    // leaves a half-written output.
    progress("writing candidate for ${options.outFile}")
    val candidate = writeCandidate(options.outFile, tables)
    val afterWrite = System.nanoTime()
    try {
        progress("verifying the candidate against the reference tables")
        val verification = verify(candidate, tables)
        if (verification.maxAbsErrorNats > FAIL_MAX_QUANTIZATION_NATS) {
            throw CompilerException(
                "quantization error ${f(verification.maxAbsErrorNats.toDouble())} nats exceeds the failure threshold " +
                    "$FAIL_MAX_QUANTIZATION_NATS; candidate discarded, any previous --out is left untouched",
            )
        }
        // The SHA is taken from the candidate bytes before the rename; publish renames
        // without rewriting, so verified bytes are the published bytes.
        val sha256Hex = sha256(candidate)
        publish(options.outFile, candidate)
        val afterVerify = System.nanoTime()
        printReport(
            options, counts, tables, verification, started, afterRead, afterEstimate,
            afterWrite, afterVerify, sha256Hex,
        )
    } finally {
        if (candidate.exists()) candidate.delete()
    }
}

/** Walks the strict `--flag value` pairs; unknown flags and bare flags fail at exit code 2. */
private fun parse(argv: Array<String>): Options {
    var countsDir: File? = null
    var outFile: File? = null
    var maxVocabulary = -1
    var minUnigram = 1
    var minBigram = 1
    var minTrigram = 1

    var i = 0
    fun value(flag: String): String {
        val raw = argv.getOrNull(i + 1) ?: throw CompilerUsageException("$flag expects a value")
        i += 2
        return raw
    }

    while (i < argv.size) {
        when (val flag = argv[i]) {
            "--counts" -> countsDir = File(value(flag)).absoluteFile
            "--out" -> outFile = File(value(flag)).absoluteFile
            "--vocab" -> maxVocabulary = parseNumber(flag, value(flag))
            "--min1" -> minUnigram = parseNumber(flag, value(flag))
            "--min2" -> minBigram = parseNumber(flag, value(flag))
            "--min3" -> minTrigram = parseNumber(flag, value(flag))
            "--help", "-h" -> {
                println(USAGE)
                exitProcess(0)
            }
            else -> throw CompilerUsageException("unknown argument '$flag'")
        }
    }

    if (countsDir == null) throw CompilerUsageException("--counts DIR is required")
    if (!countsDir.isDirectory) throw CompilerUsageException("--counts must be a directory: '${countsDir.absolutePath}'")
    if (outFile == null) throw CompilerUsageException("--out FILE is required")
    if (outFile.isDirectory) throw CompilerUsageException("--out must be a file, not a directory: '${outFile.absolutePath}'")
    if (maxVocabulary < 0) throw CompilerUsageException("--vocab N is required")
    if (maxVocabulary !in 2..0xFFFF) {
        throw CompilerUsageException("--vocab must be 2..65535 (u16 ids), got $maxVocabulary")
    }
    for (floor in listOf("--min1" to minUnigram, "--min2" to minBigram, "--min3" to minTrigram)) {
        if (floor.second < 1) throw CompilerUsageException("${floor.first} must be >= 1 (1 keeps every observed n-gram)")
    }
    return Options(countsDir, outFile, maxVocabulary, minUnigram, minBigram, minTrigram)
}

private fun parseNumber(flag: String, raw: String): Int =
    raw.toIntOrNull() ?: throw CompilerUsageException("$flag expects an integer, got '$raw'")

/** Fails before a single byte is written when the estimate holds an unusable value. */
private fun checkTables(tables: NgramTables, options: Options) {
    if (tables.vocabulary.none { it == Vocabulary.SENTENCE_START }) {
        throw CompilerException("sentence-start token is missing from the estimated vocabulary")
    }
    fun finite(values: FloatArray, name: String) {
        for (v in values) if (!v.isFinite()) throw CompilerException("non-finite value in $name")
    }
    finite(tables.unigramLogProb, "unigramLogProb")
    finite(tables.unigramBackoff, "unigramBackoff")
    finite(tables.bigramLogProb, "bigramLogProb")
    finite(tables.bigramBackoff, "bigramBackoff")
    finite(tables.trigramLogProb, "trigramLogProb")

    // Probabilities and backoff weights are log shares of context mass; a positive one
    // claims more than the whole context, which no legitimate estimate produces.
    for (v in tables.unigramLogProb) if (v > 0f) throw CompilerException("unigram logprob > 0 ($v)")
    for (v in tables.unigramBackoff) if (v > 1e-6f) throw CompilerException("unigram backoff > 0 ($v)")
    for (v in tables.bigramLogProb) if (v > 1e-6f) throw CompilerException("bigram logprob > 0 ($v)")
    for (v in tables.bigramBackoff) if (v > 1e-6f) throw CompilerException("bigram backoff > 0 ($v)")
    for (v in tables.trigramLogProb) if (v > 1e-6f) throw CompilerException("trigram logprob > 0 ($v)")

    checkBackoffCollapse(tables.unigramBackoff, "unigram backoff")
    checkBackoffCollapse(tables.bigramBackoff, "bigram backoff")

    if (tables.bigramCount == 0) {
        progress("WARNING: floors pruned every bigram (--min2 ${options.minBigram}); the model is unigram-only")
    }
}

/** Rejects the v1 failure signature without prescribing an evaluation-tuned lambda. */
internal fun checkBackoffCollapse(values: FloatArray, name: String) {
    if (values.isEmpty()) return
    val collapsed = values.count { it <= ln(1e-6).toFloat() + 1e-5f }
    if (collapsed * 2 >= values.size) {
        throw CompilerException(
            "$name collapses at or below lambda=1e-6 in $collapsed/${values.size} contexts; " +
                "preserve count-1/count-2 evidence and inspect discount estimation",
        )
    }
}

private fun backoffReport(values: FloatArray): String {
    if (values.isEmpty()) return "no contexts"
    val sorted = values.sortedArray()
    fun lambda(q: Double) = exp(sorted[((sorted.size - 1) * q).toInt()].toDouble())
    return "lambda min=${lambda(0.0)} p50=${lambda(0.5)} p95=${lambda(0.95)} " +
        "max=${lambda(1.0)} at/below-1e-6=${values.count { it <= ln(1e-6).toFloat() + 1e-5f }}/${values.size}"
}

/**
 * Writes an unpublished candidate next to [out] and forces it to disk; the caller verifies
 * against these exact bytes and publishes only on success, so --out never holds a model
 * that was not, byte for byte, the one that passed verification.
 */
private fun writeCandidate(out: File, tables: NgramTables): File {
    val parent = out.parentFile ?: throw CompilerException("--out has no parent directory to write into")
    parent.mkdirs()
    val tmp = File(parent, "${out.name}.tmp-${ProcessHandle.current().pid()}")
    try {
        FileOutputStream(tmp).use { fos ->
            // The writer goes through one write call, so no buffering is needed.
            NgramModelWriter.write(tables, fos)
            fos.fd.sync()
        }
        return tmp
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}

/** Renames the verified candidate onto [out] atomically; candidate and output match bytes. */
private fun publish(out: File, candidate: File) {
    try {
        Files.move(candidate.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(candidate.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Evenly spread deterministic row indexes over a non-empty table, deduplicated. */
private fun evenRows(count: Int, take: Int): List<Int> =
    if (count > 0) (0 until take).map { it * (count - 1) / (take - 1).coerceAtLeast(1) }.distinct() else emptyList()

/**
 * Verifies the candidate binary: header, id-to-surface mapping, unigram normalization of
 * both the reference tables and the loaded artifact, and a deterministic sample of log
 * probabilities against [TablesModel] over listed and backed-off uni/bi/trigram
 * contexts, with [Vocabulary.UNKNOWN] ids included (they must never crash a lookup).
 * Mirrors what the runtime does: it loads from the file like a device would.
 */
private fun verify(candidate: File, tables: NgramTables): Verification {
    val model = NgramModel.read(candidate) // throws on bad magic, version or truncation
    val ref = TablesModel(tables)

    // The id-to-surface mapping must be identical or every other check is meaningless.
    val size = model.vocabulary.size
    if (size != tables.vocabulary.size) {
        throw CompilerException("loaded vocabulary size $size != ${tables.vocabulary.size}")
    }
    for (id in 0 until size) {
        if (model.vocabulary.surface(id) != tables.vocabulary[id]) {
            throw CompilerException(
                "loaded vocabulary differs at id $id: '${model.vocabulary.surface(id)}' != '${tables.vocabulary[id]}'",
            )
        }
    }
    val sentenceStart = model.vocabulary.id(Vocabulary.SENTENCE_START)
    if (sentenceStart < 0) throw CompilerException("sentence-start token missing from the loaded model")

    // Normalization sanity: the unigram base is the model's full marginal over the whole
    // vocabulary. Both masses are measured and reported with explicit labels, because
    // they differ by construction: the reference sums to ~1.0 (float32 estimate drift),
    // the loaded artifact's mass additionally carries quantization (measured ~0.9987 on
    // the en-chat-v1 audit).
    var referenceMass = 0.0
    for (p in tables.unigramLogProb) referenceMass += exp(p.toDouble())
    var loadedMass = 0.0
    for (id in 0 until size) loadedMass += exp(model.unigramLogProb(id).toDouble())
    if (abs(referenceMass - 1.0) > 0.02 || abs(loadedMass - 1.0) > 0.02) {
        throw CompilerException(
            "unigram mass off: reference ${f(referenceMass)}, loaded ${f(loadedMass)}, expected ~1.0",
        )
    }

    val rng = Random(SAMPLE_SEED)
    val contexts = sampleContexts(tables, sentenceStart, rng)

    var pairs = 0
    var maxError = 0f
    var errorSum = 0.0
    fun compare(context: IntArray, word: Int) {
        val listed = quoted(ref.vocabulary, context)
        val expected = ref.logProb(word, context)
        val actual = model.logProb(word, context)
        if (!expected.isFinite() || !actual.isFinite()) {
            throw CompilerException(
                "non-finite logProb for '${wordName(ref.vocabulary, word)}' in $listed: ref $expected, loaded $actual",
            )
        }
        val delta = abs(actual - expected)
        if (delta > maxError) maxError = delta
        errorSum += delta
        pairs++
    }

    for (context in contexts) {
        for (word in sampledWords(sentenceStart, size, rng, model, context)) {
            compare(context, word)
        }
    }
    if (contexts.size < 50 || pairs < 1000) {
        throw CompilerException("verification sample too small: $contexts contexts, $pairs pairs")
    }

    // Top-5 prediction agreement on a few contexts: informational, since near-ties may
    // reorder when two words' probabilities differ by less than the step between
    // codebook entries.
    var agreements = 0
    var predictContexts = 0
    val predictionLines = ArrayList<String>()
    val displayContexts = contexts.take(DISPLAY_CONTEXTS)
    for (context in displayContexts) {
        predictContexts++
        val quantized = model.predict(context, 5).map { it.id }
        val reference = ref.predict(context, 5).map { it.id }
        if (quantized == reference) agreements++
        if (predictionLines.size < REPRESENTATIVE_PREDICTIONS) {
            val tokens = quoted(ref.vocabulary, context)
            val words = quantized.filter { it != sentenceStart }.take(5)
                .joinToString(" ") { "${ref.vocabulary.surface(it)} (${f(model.logProb(it, context).toDouble())})" }
            predictionLines.add("$tokens -> ${words.ifEmpty { "(nothing)" }}")
        }
    }

    return Verification(
        contexts = contexts.size,
        pairs = pairs,
        maxAbsErrorNats = maxError,
        meanAbsErrorNats = (errorSum / pairs).toFloat(),
        top5ExactAgreements = agreements,
        predictContexts = predictContexts,
        referenceUnigramMass = referenceMass,
        loadedUnigramMass = loadedMass,
        predictionLines = predictionLines,
    )
}

/**
 * Contexts sampling: the fixed seed makes the sample (and hence max error and the
 * prediction lines) identical across runs of identical inputs. Covers the three orders
 * with both listed rows (full high-order path) and contexts that only exist in the
 * lower orders (backoff path), plus unknown ids in every position.
 */
private fun sampleContexts(tables: NgramTables, sentenceStart: Int, rng: Random): List<IntArray> {
    val size = tables.vocabulary.size
    val contexts = ArrayList<IntArray>()
    contexts.add(intArrayOf())
    if (sentenceStart >= 0) contexts.add(intArrayOf(sentenceStart))
    contexts.add(intArrayOf(Vocabulary.UNKNOWN))

    // Ranked unigram contexts: ids follow count order, so low ids are frequent words and
    // high ids the long tail, exercising bigram hits and misses both.
    for (rank in intArrayOf(1, 2, 5, 10, 50, 100, 1000, 5000, 20000, size / 2, size - 1)) {
        if (rank in 0 until size && rank != sentenceStart) contexts.add(intArrayOf(rank))
    }

    // Listed bigram contexts (full bigram path), evenly spread plus random rows.
    if (tables.bigramCount > 0) {
        for (index in evenRows(tables.bigramCount, 16) + List(8) { rng.nextInt(tables.bigramCount) }) {
            contexts.add(intArrayOf(tables.bigramFirst[index], tables.bigramSecond[index]))
        }
    }

    // Listed trigram contexts (full trigram path), evenly spread plus random rows.
    if (tables.trigramCount > 0) {
        for (index in evenRows(tables.trigramCount, 24) + List(8) { rng.nextInt(tables.trigramCount) }) {
            contexts.add(intArrayOf(tables.trigramFirst[index], tables.trigramSecond[index]))
        }
    }

    // Unknown ids inside two-word contexts, both positions.
    repeat(12) { x ->
        contexts.add(intArrayOf(x, Vocabulary.UNKNOWN))
        contexts.add(intArrayOf(Vocabulary.UNKNOWN, x))
    }
    contexts.add(intArrayOf(Vocabulary.UNKNOWN, Vocabulary.UNKNOWN))

    // Random unlisted pairs and triples: overwhelmingly the backoff path.
    repeat(60) { contexts.add(intArrayOf(rng.nextInt(size), rng.nextInt(size))) }
    repeat(60) { contexts.add(intArrayOf(rng.nextInt(size), rng.nextInt(size), rng.nextInt(size))) }

    return contexts
}

/**
 * The words to check per context: unknown, sentence start, fixed near-ties at the
 * frequency ranks, a deterministic random spread, and the model's own top predictions
 * (which exercise the mass that must survive quantization).
 */
private fun sampledWords(sentenceStart: Int, size: Int, rng: Random, model: NgramModel, context: IntArray): List<Int> =
    buildList {
        add(Vocabulary.UNKNOWN)
        add(sentenceStart)
        add(1)
        add(2)
        add(1000)
        repeat(24) { add(rng.nextInt(size)) }
        addAll(model.predict(context, 5).map { it.id })
    }.filter { it == Vocabulary.UNKNOWN || it in 0 until size }.distinct()

/** Sentence tokens for reports, unknown ids named, sentence start kept visible. */
private fun quoted(vocabulary: Vocabulary, context: IntArray): String =
    context.joinToString(" ") { wordName(vocabulary, it) }

/** Names one visible word or the unknown marker; unknown ids must never crash lookup. */
private fun wordName(vocabulary: Vocabulary, id: Int): String =
    if (id == Vocabulary.UNKNOWN) "<unk>" else vocabulary.surface(id)

private fun printReport(
    options: Options,
    counts: NgramCounts,
    tables: NgramTables,
    verification: Verification,
    started: Long,
    afterRead: Long,
    afterEstimate: Long,
    afterWrite: Long,
    afterVerify: Long,
    sha256Hex: String,
) {
    fun span(from: Long, to: Long) = secs(to - from)
    println("== compile-model report ==")
    println("counts:      ${options.countsDir}")
    println("output:      ${options.outFile} (${options.outFile.length()} bytes)")
    println("sha256:      $sha256Hex (verified bytes are the published bytes)")
    println("vocab cap:   ${options.maxVocabulary}, floors: unigram ${options.minUnigram}, bigram ${options.minBigram}, trigram ${options.minTrigram}")
    println("timings:     read ${span(started, afterRead)}, estimate ${span(afterRead, afterEstimate)}, " +
        "write ${span(afterEstimate, afterWrite)}, verify ${span(afterWrite, afterVerify)}, " +
        "total ${secs(System.nanoTime() - started)}")
    println("counts rows: ${counts.vocabulary.size} words, ${counts.bigramCounts.size} bigrams, ${counts.trigramCounts.size} trigrams")
    println("tables:      ${tables.vocabulary.size} words, ${tables.bigramCount} bigrams, ${tables.trigramCount} trigrams")
    println("unigram backoff: ${backoffReport(tables.unigramBackoff)}")
    println("bigram backoff:  ${backoffReport(tables.bigramBackoff)}")
    println("verification: ${verification.contexts} contexts, ${verification.pairs} (context, word) pairs")
    println(
        "quantization: max |delta logprob| ${f(verification.maxAbsErrorNats.toDouble())} nats, " +
            "mean ${f(verification.meanAbsErrorNats.toDouble())} nats (fail threshold $FAIL_MAX_QUANTIZATION_NATS nats)",
    )
    println("unigram mass reference: ${f(verification.referenceUnigramMass)} (estimate, expected ~1.0)")
    println("unigram mass loaded:    ${f(verification.loadedUnigramMass)} (published artifact, includes quantization)")
    println(
        "predict top-5 exact agreement: ${verification.top5ExactAgreements}/${verification.predictContexts} contexts " +
            "(near-ties may reorder; informational)",
    )
    println("sample predictions (loaded model):")
    verification.predictionLines.forEach { println("  $it") }
    println("quality note: tables are normalized and quantization is measured; no perplexity or typing evaluation was run here.")
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
