package com.wisperlow.mobile.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun upgradeMissingOverlayReopensSetupOnlyOnce() {
        val pending = booleanPreferencesKey("bubble_setup_pending")
        val prefs = mutablePreferencesOf(booleanPreferencesKey("onboarding_completed") to true)
        SettingsRepository.migrateBubbleSetup(prefs, overlayGranted = false)
        assertEquals(true, prefs[pending])
        assertEquals(true, prefs[booleanPreferencesKey("bubble_setup_migrated")])
        assertEquals(true, prefs[booleanPreferencesKey("onboarding_completed")])

        // Dismissing the guide survives another launch with permission still denied.
        prefs[pending] = false
        SettingsRepository.migrateBubbleSetup(prefs, overlayGranted = false)
        assertEquals(false, prefs[pending])
    }

    @Test
    fun newInstallsAndUpgradesWithOverlayKeepTheirExistingOnboarding() {
        val pending = booleanPreferencesKey("bubble_setup_pending")
        val fresh = mutablePreferencesOf()
        SettingsRepository.migrateBubbleSetup(fresh, overlayGranted = false)
        assertEquals(false, fresh[pending])

        val granted = mutablePreferencesOf(booleanPreferencesKey("onboarding_completed") to true)
        SettingsRepository.migrateBubbleSetup(granted, overlayGranted = true)
        assertEquals(false, granted[pending])
        SettingsRepository.migrateBubbleSetup(granted, overlayGranted = false)
        assertEquals(false, granted[pending])
    }

    @Test
    fun defaultsFavorFastInsertion() {
        val defaults = WisperlowSettings()

        assertFalse(defaults.reviewBeforeInsert)
        assertTrue(defaults.livePreview)
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
