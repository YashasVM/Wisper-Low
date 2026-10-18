package com.wisperlow.mobile.keyboard

import com.wisperlow.mobile.keyboard.emoji.EmojiIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How words route to the CLDR emoji dictionaries and what a broken dictionary costs.
 * The indexes themselves are covered in keyboard-core; only the selection is tested here.
 */
class EmojiRepositoryTest {
    private val english = EmojiIndex(
        sequenceOf(
            "pizza\t🍕 🍕",
            "happy\t😊 😄 😊 🥳", // a repeated emoji is kept once, then capped
            "hot\t🔥 🥵 ☀️ 🔥",
        ),
    )
    private val hindi = EmojiIndex(sequenceOf("प्यार\t💕 ❤️", "पिज़्ज़ा\t🍕"))
    private val kannada = EmojiIndex(sequenceOf("ಪಿಜ್ಜಾ\t🍕"))

    private fun repository(vararg loaded: Pair<String, EmojiIndex>): EmojiRepository {
        val byLanguage = mapOf(*loaded)
        return EmojiRepository { EmojiIndex(emptySequence()) }.apply {
            // Android assets never open here: the dictionaries the repository holds are seeded.
            available.value = byLanguage
        }
    }

    @Test
    fun `hints come from the dictionary of the word's script`() {
        val repo = repository("en" to english, "hi" to hindi, "kn" to kannada)
        assertEquals(listOf("🍕"), repo.hints("pizza"))
        assertEquals(listOf("💕", "❤️"), repo.hints("प्यार"))
        assertEquals(listOf("🍕"), repo.hints("ಪಿಜ್ಜಾ"))
    }

    @Test
    fun `mixed-script words route by their indic characters`() {
        assertEquals("hi", script("don't प्यार"))
        assertEquals("kn", script("pizza ಪಿ"))
        assertEquals("en", script("pizza"))
    }

    @Test
    fun `hints never exceed three, however many keywords offer more`() {
        val repo = repository("en" to english)
        assertEquals(3, repo.hints("happy").size)
        assertEquals(listOf("🔥", "🥵", "☀️"), repo.hints("hot"))
        assertEquals(listOf("🔥", "🥵"), repo.hints("hot", 2))
    }

    @Test
    fun `words without a matching script or keyword find nothing`() {
        val repo = repository("en" to english)
        // Only English is on the fake device here.
        assertTrue(repo.hints("प्यार").isEmpty())
        assertTrue(repo.hints("ಪಿಜ್ಜಾ").isEmpty())
        assertTrue(repo.hints("xyzzy").isEmpty())
        assertTrue(repo.hints("").isEmpty())
        // Nothing loaded at all is just as quiet.
        assertTrue(repository().hints("pizza").isEmpty())
    }

    /**
     * Real files, read the way the device reads assets: the repository's reader must
     * build each index inside the open stream, and a dictionary that never opens for
     * real costs only its own language.
     */
    @Test
    fun `asset-style reading keeps every dictionary a closed stream could lose`() = runTest {
        val dir = Files.createTempDirectory("wisperlow-emoji")
        try {
            write(dir, "en.tsv", "# from CLDR", "pizza\t🍕", "love\t❤️ 🥰")
            write(dir, "hi.tsv", "प्यार\t💕 ❤️")
            // kn.tsv is not on the device: its reader will hit a missing file.
            val repo = EmojiRepository { language ->
                dir.resolve("$language.tsv").toFile().bufferedReader().useLines { EmojiIndex(it) }
            }
            repo.missingDictionary = { _, _ -> }
            repo.refresh()
            assertEquals(setOf("en", "hi"), repo.available.value.keys)
            assertEquals(listOf("🍕"), repo.hints("pizza"))
            assertEquals(listOf("💕", "❤️"), repo.hints("प्यार"))
            assertEquals(emptyList<String>(), repo.hints("ಪಿಜ್ಜಾ"))
            // A second pass changes nothing: dictionaries load once per process.
            repo.refresh()
            assertEquals(setOf("en", "hi"), repo.available.value.keys)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun write(dir: Path, name: String, vararg lines: String) {
        Files.write(dir.resolve(name), lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }
}
