package com.wisperlow.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun defaultsFavorFastInsertion() {
        val defaults = WisperlowSettings()

        assertFalse(defaults.reviewBeforeInsert)
        assertEquals(AutoStop.NORMAL, defaults.autoStop)
        assertTrue(defaults.historyEnabled)
        assertTrue(defaults.bubbleOnlyWhenTyping)
        assertFalse(defaults.onboardingCompleted)
    }

    @Test
    fun autoStopParserUsesSafeDefaultForUnknownValues() {
        assertEquals(AutoStop.RELAXED, SettingsRepository.parseAutoStop(" relaxed "))
        assertEquals(AutoStop.MANUAL, SettingsRepository.parseAutoStop("MANUAL"))
        assertEquals(AutoStop.NORMAL, SettingsRepository.parseAutoStop("%$#"))
        assertEquals(AutoStop.NORMAL, SettingsRepository.parseAutoStop(null))
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
    }

    @Test
    fun dictionaryRoundTripsWithoutBreakingOnSeparators() {
        val dict = mapOf("wisper low" to "Wisperlow", "a=b" to "line\nbreak")
        val parsed = SettingsRepository.parseDictionary(SettingsRepository.serializeDictionary(dict))

        assertEquals("Wisperlow", parsed["wisper low"])
        assertEquals("line break", parsed["a b"])
    }
}
