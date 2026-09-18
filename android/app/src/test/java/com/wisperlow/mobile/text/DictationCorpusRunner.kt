package com.wisperlow.mobile.text

import java.io.File

enum class AutomatedMeaningCheck {
    PROTECTED_SPANS_PRESENT,
    PROTECTED_SPAN_MISSING,
}

/** One observed run of a deterministic or real cleanup function. */
data class CorpusObservation(
    val case: DictationCorpusCase,
    val actualOutput: String,
    val elapsedNanos: Long,
    val protectedTermsPresent: Boolean,
    val meaningCheck: AutomatedMeaningCheck,
)

data class CorpusRunReport(
    val engineName: String,
    val observations: List<CorpusObservation>,
) {
    val caseCount: Int get() = observations.size
    val protectedTermFailures: List<String>
        get() = observations.filterNot { it.protectedTermsPresent }.map { it.case.id }

    /** Stable, reviewable output suitable for a test artifact or a pasted issue comment. */
    fun toTsv(): String = buildString {
        appendLine(
            listOf(
                "engine",
                "case_id",
                "category",
                "recognized_text",
                "actual_output",
                "expected_polished_text",
                "meaning_expectation",
                "protected_terms_present",
                "automated_meaning_check",
                "elapsed_nanos",
                "review_note",
            ).joinToString("\t"),
        )
        observations.forEach { observation ->
            val case = observation.case
            appendLine(
                listOf(
                    engineName,
                    case.id,
                    case.category.name,
                    case.recognizedText,
                    observation.actualOutput,
                    case.expectedPolishedText,
                    case.meaningExpectation.name,
                    observation.protectedTermsPresent,
                    observation.meaningCheck.name,
                    observation.elapsedNanos,
                    case.reviewNote,
                ).joinToString("\t", transform = ::escapeTsv),
            )
        }
    }

    private fun escapeTsv(value: Any): String = value.toString()
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
}

/**
 * Runs a transcript corpus while keeping timing and output collection in one place.
 *
 * The default clock is monotonic. Tests can inject a clock to make timing assertions
 * deterministic. The transform is intentionally a function so a future cleanup trial
 * can use this runner without coupling corpus code to a model runtime.
 */
class DictationCorpusRunner(
    private val engineName: String,
    private val transform: (String) -> String,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(cases: List<DictationCorpusCase> = DictationCorpus.cases): CorpusRunReport {
        val observations = cases.map { case ->
            val started = nanoTime()
            val output = transform(case.recognizedText)
            val elapsed = (nanoTime() - started).coerceAtLeast(0L)
            CorpusObservation(
                case = case,
                actualOutput = output,
                elapsedNanos = elapsed,
                protectedTermsPresent = case.protectedTerms.all { term ->
                    output.contains(term, ignoreCase = true)
                },
                meaningCheck = if (case.protectedTerms.all { term ->
                        output.contains(term, ignoreCase = true)
                    }
                ) {
                    AutomatedMeaningCheck.PROTECTED_SPANS_PRESENT
                } else {
                    AutomatedMeaningCheck.PROTECTED_SPAN_MISSING
                },
            )
        }
        return CorpusRunReport(engineName = engineName, observations = observations)
    }
}

/** A user-supplied recording that may be used in a real-model recognition trial. */
data class RecognitionAudioCase(
    val id: String,
    val audioFile: File,
    val expectedTranscript: String,
    val consentReference: String,
)

data class RecognitionAudioObservation(
    val case: RecognitionAudioCase,
    val actualTranscript: String,
    val elapsedNanos: Long,
)

/**
 * Opt-in runner for recordings supplied by the user or an explicitly documented test
 * source. No recordings are bundled here because the repository has no consented
 * speaker set. This keeps real-model evaluation separate from deterministic tests.
 */
class RecognitionAudioCorpusRunner(
    private val transcribe: (File) -> String,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(cases: List<RecognitionAudioCase>): List<RecognitionAudioObservation> {
        cases.forEach { case ->
            require(case.audioFile.isFile) { "Missing audio fixture: ${case.audioFile}" }
            require(case.expectedTranscript.isNotBlank()) {
                "Expected transcript is required for ${case.id}"
            }
            require(case.consentReference.isNotBlank()) {
                "Consent reference is required for ${case.id}"
            }
        }
        return cases.map { case ->
            val started = nanoTime()
            val transcript = transcribe(case.audioFile)
            RecognitionAudioObservation(
                case = case,
                actualTranscript = transcript,
                elapsedNanos = (nanoTime() - started).coerceAtLeast(0L),
            )
        }
    }
}

/**
 * Loads a small external manifest without putting private audio in source control.
 * Each non-comment line is: id<TAB>audio path<TAB>expected transcript<TAB>consent ref.
 */
object RecognitionAudioManifest {
    fun read(file: File): List<RecognitionAudioCase> = file.readLines()
        .asSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 4) {
                "Audio manifest line ${index + 1} must have four tab-separated fields"
            }
            RecognitionAudioCase(
                id = fields[0].trim(),
                audioFile = File(fields[1].trim()),
                expectedTranscript = fields[2].trim(),
                consentReference = fields[3].trim(),
            )
        }
        .toList()
}
