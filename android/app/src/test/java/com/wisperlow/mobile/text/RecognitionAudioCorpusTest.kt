package com.wisperlow.mobile.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionAudioCorpusTest {

    @Test
    fun deterministicSuiteDoesNotSilentlyRunARealModel() {
        val consentedCases = emptyList<RecognitionAudioCase>()
        val observations = RecognitionAudioCorpusRunner(transcribe = { error("not called") })
            .run(consentedCases)

        assertTrue(
            "No consented recordings are checked into this repository; provide an external manifest for real-model evaluation",
            observations.isEmpty(),
        )
    }

    @Test
    fun externalManifestAndRunnerRecordTranscriptAndTiming() {
        val directory = createTempDir(prefix = "wisperlow-corpus-")
        try {
            val audio = File(directory, "sample.wav").apply { writeBytes(byteArrayOf(1)) }
            val manifest = File(directory, "audio.tsv").apply {
                writeText("sample\t${audio.absolutePath}\thello world\tconsent-2026-01\n")
            }
            var clock = 0L
            val cases = RecognitionAudioManifest.read(manifest)
            val observations = RecognitionAudioCorpusRunner(
                transcribe = { "hello world" },
                nanoTime = { clock += 50L; clock },
            ).run(cases)

            assertEquals(1, observations.size)
            assertEquals("hello world", observations.single().actualTranscript)
            assertEquals(50L, observations.single().elapsedNanos)
            assertEquals("consent-2026-01", observations.single().case.consentReference)
        } finally {
            directory.deleteRecursively()
        }
    }
}
