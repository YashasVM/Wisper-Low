package com.wisperlow.mobile.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationCorpusTest {

    @Test
    fun corpusHasCoverageAcrossAllRequiredSpeechPhenomena() {
        assertTrue("Expected at least 40 cases", DictationCorpus.cases.size >= 40)
        assertEquals(
            "Every category in the manifest should have a case",
            CorpusCategory.entries.toSet(),
            DictationCorpus.categories,
        )
        assertEquals(
            "Corpus IDs must be unique",
            DictationCorpus.cases.size,
            DictationCorpus.cases.map { it.id }.toSet().size,
        )
        assertTrue(DictationCorpus.cases.all { it.recognizedText.isNotBlank() })
        assertTrue(DictationCorpus.cases.all { it.expectedPolishedText.isNotBlank() })
    }

    @Test
    fun baselineRunnerRecordsLosslessCleanerOutputAndTiming() {
        var clock = 0L
        val report = DictationCorpusRunner(
            engineName = "TextCleaner baseline",
            transform = TextCleaner::clean,
            nanoTime = { clock += 100L; clock },
        ).run()

        assertEquals(DictationCorpus.cases.size, report.caseCount)
        assertTrue(report.observations.all { it.actualOutput == TextCleaner.clean(it.case.recognizedText) })
        assertTrue(report.observations.all { it.elapsedNanos == 100L })
        assertTrue(
            report.observations.all {
                it.meaningCheck == AutomatedMeaningCheck.PROTECTED_SPANS_PRESENT
            },
        )
        assertTrue(
            "Baseline must preserve every protected source span: ${report.protectedTermFailures}",
            report.protectedTermFailures.isEmpty(),
        )

        val tsv = report.toTsv()
        assertTrue(tsv.startsWith("engine\tcase_id\tcategory\t"))
        assertEquals(report.caseCount + 1, tsv.lineSequence().count())
        assertTrue(tsv.contains("grammar_more_better"))
        assertTrue(tsv.contains("PRESERVE_AMBIGUOUS_ALTERNATIVES"))
    }

    @Test
    fun baselineDoesNotPretendToProducePolishedTargets() {
        val report = DictationCorpusRunner(
            engineName = "TextCleaner baseline",
            transform = TextCleaner::clean,
        ).run()
        val grammarCase = report.observations.single { it.case.id == "grammar_more_better" }

        assertEquals("I I want uh the onboarding to be more better", grammarCase.actualOutput)
        assertEquals("I want the onboarding to be better.", grammarCase.case.expectedPolishedText)
        assertFalse(grammarCase.actualOutput == grammarCase.case.expectedPolishedText)
    }

    @Test
    fun corpusKeepsProtectedTermsAvailableForAutomatedChecks() {
        DictationCorpus.cases
            .filter { it.protectedTerms.isNotEmpty() }
            .forEach { case ->
                val output = TextCleaner.clean(case.recognizedText)
                case.protectedTerms.forEach { term ->
                    assertNotNull("Missing source term metadata for ${case.id}", term)
                    assertTrue(
                        "Baseline lost '$term' in ${case.id}",
                        output.contains(term, ignoreCase = true),
                    )
                }
            }
    }
}
