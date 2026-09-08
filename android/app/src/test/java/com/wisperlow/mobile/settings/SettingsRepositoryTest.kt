package com.wisperlow.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsRepositoryTest {
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
}
