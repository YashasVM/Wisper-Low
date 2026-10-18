package com.wisperlow.mobile.keyboard

import com.wisperlow.mobile.keyboard.indic.LatinRegionalEngine
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LatinRegionalAssetsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun loader(assets: Map<String, List<String>>): AssetEngineLoader =
        AssetEngineLoader { name -> assets[name] ?: throw java.io.FileNotFoundException(name) }

    @Test
    fun `regional modes load Latin words English mixing and contextual predictions`() = runTest {
        val assets = mapOf(
            "dict/kn_latn.tsv" to listOf("# Kannada in English letters", "namma\t20", "ooru\t10"),
            "dict/kn_latn_bigrams.tsv" to listOf("# previous next count", "namma\tooru\t8"),
            "dict/en.txt" to listOf("hello\t100"),
            "dict/en_modern.txt" to listOf("API\t50"),
        )
        val engine = loader(assets).load(KeyboardLanguage.KANNADA)!!

        assertTrue(engine is LatinRegionalEngine)
        assertTrue(engine.isKnown("namma"))
        assertTrue(engine.isKnown("hello"))
        assertTrue(engine.isKnown("API"))
        assertTrue(engine.predict("namma").contains("ooru"))
        assertNull(engine.suggest("namma").autoCorrect)
        assertTrue(engine.suggest("namma").words.contains("namma"))
    }

    @Test
    fun `English keeps its spatial engine and existing word lists`() = runTest {
        val requested = ArrayList<String>()
        val loader = AssetEngineLoader { name ->
            requested += name
            when (name) {
                "dict/en.txt" -> listOf("hello\t100", "world\t80")
                "dict/en_modern.txt" -> listOf("API\t50")
                else -> throw java.io.FileNotFoundException(name)
            }
        }
        val engine = loader.load(KeyboardLanguage.ENGLISH)!!

        assertTrue(engine is KeyboardEngine)
        assertTrue(engine.isKnown("hello"))
        assertTrue(engine.isKnown("API"))
        assertEquals(listOf("dict/en.txt", "dict/en_modern.txt"), requested)
    }

    @Test
    fun `Hindi uses its Latin tables without native conversion`() = runTest {
        val assets = mapOf(
            "dict/hi_latn.tsv" to listOf("kal\t20", "milenge\t10"),
            "dict/hi_latn_bigrams.tsv" to listOf("kal\tmilenge\t7"),
        )
        val engine = loader(assets).load(KeyboardLanguage.HINDI)!!

        assertTrue(engine.isKnown("kal"))
        assertNull(engine.suggest("kal").autoCorrect)
        assertTrue(engine.predict("kal").contains("milenge"))
        assertTrue(engine.suggest("kal").words.all { word -> word.all { it.code < 128 } })
    }

    @Test
    fun `a missing regional asset preserves text without English corrections`() = runTest {
        val requested = ArrayList<String>()
        val loader = AssetEngineLoader { name ->
            requested += name
            if (name == "dict/en.txt") listOf("member\t1000000") else throw java.io.FileNotFoundException(name)
        }
        val engine = loader.load(KeyboardLanguage.KANNADA)!!

        assertNull(engine.suggest("namma").autoCorrect)
        assertTrue(engine.suggest("namma").words.contains("namma"))
        assertFalse(requested.contains("dict/en.txt"))
        assertFalse(requested.any { it.startsWith("indic/") })
    }

    @Test
    fun `missing bigrams keep regional dictionary available and invalid rows are ignored`() = runTest {
        val assets = mapOf(
            "dict/kn_latn.tsv" to listOf(
                "namma\t20", "ಊರು\t100", "zero\t0", "negative\t-1", "broken\tnan", "extra\t1\t2",
            ),
        )
        val engine = loader(assets).load(KeyboardLanguage.KANNADA)!!

        assertTrue(engine.isKnown("namma"))
        listOf("ಊರು", "zero", "negative", "broken", "extra").forEach { assertFalse(engine.isKnown(it)) }
        assertNull(engine.suggest("namma").autoCorrect)
    }

    @Test
    fun `invalid contextual rows cannot supply suggestions`() = runTest {
        val assets = mapOf(
            "dict/hi_latn.tsv" to listOf("kal\t20", "milenge\t10"),
            "dict/hi_latn_bigrams.tsv" to listOf(
                "kal\tmilenge\t7", "kal\tनमस्ते\t100000", "kal\tzero\t0", "kal\tbroken\tnan",
            ),
        )
        val engine = loader(assets).load(KeyboardLanguage.HINDI)!!

        assertTrue(engine.predict("kal").contains("milenge"))
        assertFalse(engine.predict("kal", 20).any { it in setOf("नमस्ते", "zero", "broken") })
    }

    @Test
    fun `Latin learning uses new files and leaves native conversion learning untouched`() {
        val directory = temporary.newFolder()
        val nativeHi = File(directory, "keyboard/learned_hi.tsv")
        val nativeKn = File(directory, "keyboard/learned_kn.tsv")
        nativeHi.parentFile!!.mkdirs()
        nativeHi.writeText("w\tनमस्ते\t3\n")
        nativeKn.writeText("w\tನಮಸ್ಕಾರ\t4\n")
        val store = FileLearnedStore(directory)
        assertNull(store.read(KeyboardLanguage.HINDI))
        assertNull(store.read(KeyboardLanguage.KANNADA))

        val hindi = Lexicon.LearnedData(listOf("milenge" to 2), emptyList())
        val kannada = Lexicon.LearnedData(listOf("namma" to 3), emptyList())
        store.write(KeyboardLanguage.HINDI, hindi)
        store.write(KeyboardLanguage.KANNADA, kannada)

        assertEquals(hindi, store.read(KeyboardLanguage.HINDI))
        assertEquals(kannada, store.read(KeyboardLanguage.KANNADA))
        assertTrue(File(directory, "keyboard/learned_hi_latn.tsv").isFile)
        assertTrue(File(directory, "keyboard/learned_kn_latn.tsv").isFile)
        assertEquals("w\tनमस्ते\t3\n", nativeHi.readText())
        assertEquals("w\tನಮಸ್ಕಾರ\t4\n", nativeKn.readText())
    }

    @Test
    fun `English learning continues to use its existing file`() {
        val directory = temporary.newFolder()
        val existing = File(directory, "keyboard/learned.tsv")
        existing.parentFile!!.mkdirs()
        existing.writeText("w\thello\t5\n")
        val store = FileLearnedStore(directory)

        assertEquals(listOf("hello" to 5), store.read(KeyboardLanguage.ENGLISH)!!.words)
        val updated = Lexicon.LearnedData(listOf("hello" to 6), emptyList())
        store.write(KeyboardLanguage.ENGLISH, updated)
        assertEquals(updated, LearnedFile.read(existing))
    }

    @Test
    fun `shipped Latin assets preserve colloquial text and reload only personal learning`() = runTest {
        val assets = File("src/main/assets")
        assertTrue("App test must read the shipped assets directory", assets.isDirectory)
        val realLoader = AssetEngineLoader { name -> File(assets, name).readLines() }
        for (language in listOf(KeyboardLanguage.HINDI, KeyboardLanguage.KANNADA)) {
            val engine = realLoader.load(language)!!
            assertTrue(engine is LatinRegionalEngine)
            for (phrase in listOf("macha", "henge edhiya", "baro le")) {
                for (raw in phrase.split(' ')) {
                    for (typed in listOf(raw, raw.uppercase(), raw.replaceFirstChar { it.uppercaseChar() })) {
                        val suggestions = engine.suggest(typed, touches = typed.map { Qwerty.touch(it, 0f, 0f) })
                        assertEquals(typed, suggestions.words.first())
                        assertNull(suggestions.autoCorrect)
                        assertTrue(suggestions.words.all { word -> word.all { it.code < 128 } })
                    }
                }
            }
            assertTrue(engine.isKnown("hello"))
            assertEquals("hello", engine.suggest("hello").words.first())
            assertNull(engine.suggest("hello").autoCorrect)
            listOf(null, "henge", "baro", "hello").forEach { previous ->
                assertTrue(engine.predict(previous).all { word -> word.all { it.code < 128 } })
            }
            // Corpus words and pairs have not become the user's learned state.
            assertEquals(Lexicon.LearnedData(emptyList(), emptyList()), engine.exportLearned())
            engine.learnFix("zzcustomtypo", "zzcustomword")
            engine.learn("zzcustomword", "hello")
            val stored = File(temporary.newFolder(), "learned.tsv")
            LearnedFile.write(stored, engine.exportLearned())
            val restored = realLoader.load(language)!!
            restored.importLearned(LearnedFile.read(stored)!!)
            assertEquals("zzcustomword", restored.suggest("zzcustomtypo").autoCorrect)
            assertEquals("zzcustomword", restored.predict("hello").first())
            restored.trust("zzcustomtypo")
            assertNull(restored.suggest("zzcustomtypo").autoCorrect)
            restored.forget("zzcustomword")
            assertFalse("zzcustomword" in restored.predict("hello"))
        }
    }
}
