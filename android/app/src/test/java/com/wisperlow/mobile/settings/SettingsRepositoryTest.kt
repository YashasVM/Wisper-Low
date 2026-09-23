package com.wisperlow.mobile.settings

import com.wisperlow.mobile.text.PolishMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun defaultsPreserveExistingInstallBehavior() {
        val defaults = WisperlowSettings()

        assertEquals(PolishMode.ORIGINAL, defaults.polishMode)
        assertEquals(InsertionPreference.REVIEW_FIRST, defaults.insertionPreference)
        assertTrue(defaults.historyEnabled)
        assertFalse(defaults.onboardingCompleted)
        assertFalse(defaults.practiceCompleted)
        assertTrue(defaults.vocabularyHints.isEmpty())
    }

    @Test
    fun enumParsersUseSafeDefaultsForUnknownAndCorruptValues() {
        assertEquals(PolishMode.POLISHED, SettingsRepository.parsePolishMode(" polished "))
        assertEquals(PolishMode.ORIGINAL, SettingsRepository.parsePolishMode("not-a-mode"))
        assertEquals(PolishMode.ORIGINAL, SettingsRepository.parsePolishMode("%$#"))
        assertEquals(
            InsertionPreference.QUICK_INSERT,
            SettingsRepository.parseInsertionPreference("quick_insert"),
        )
        assertEquals(
            InsertionPreference.REVIEW_FIRST,
            SettingsRepository.parseInsertionPreference("missing"),
        )
        assertEquals(
            InsertionPreference.REVIEW_FIRST,
            SettingsRepository.parseInsertionPreference(null),
        )
    }

    @Test
    fun parseDictionary_trimsKeysValues_andKeepsEqualsInValue() {
        val parsed = SettingsRepository.parseDictionary(
            "\n  NASA = National Aeronautics = Space Administration  \n" +
                "empty=\n" +
                "not a rule\n" +
                "  =missing-key\n",
        )

        assertEquals(
            mapOf("nasa" to "National Aeronautics = Space Administration"),
            parsed,
        )
    }

    @Test
    fun parseDictionary_lastDuplicateWins() {
        val parsed = SettingsRepository.parseDictionary("hello=first\nHELLO=second")

        assertEquals(1, parsed.size)
        assertEquals("second", parsed["hello"])
        assertFalse(parsed.containsKey("HELLO"))
    }

    @Test
    fun vocabularyHintsHaveSeparateContextAndIgnoreCorruptRecords() {
        val hints = listOf(
            VocabularyHint("GPT-5.6 Sol", "the orchestration model"),
            VocabularyHint("Wisperlow", "the Android app"),
        )

        val serialized = SettingsRepository.serializeVocabularyHints(hints)
        val parsed = SettingsRepository.parseVocabularyHints(
            "$serialized\nnot-base64\tstill-not-enough-fields\n",
        )

        assertEquals(hints, parsed)
        assertEquals("the orchestration model", parsed.first().context)
        assertFalse(SettingsRepository.parseDictionary("soul=Sol").containsKey("GPT-5.6 Sol"))
    }

    @Test
    fun vocabularyHintsNormalizeLineBreaksAndDuplicateEntries() {
        val serialized = SettingsRepository.serializeVocabularyHints(
            listOf(
                VocabularyHint("  Acme  ", "first\ncontext"),
                VocabularyHint("Acme", "first context"),
                VocabularyHint("", "ignored"),
            ),
        )

        assertEquals(
            listOf(VocabularyHint("Acme", "first context")),
            SettingsRepository.parseVocabularyHints(serialized),
        )
    }
}
