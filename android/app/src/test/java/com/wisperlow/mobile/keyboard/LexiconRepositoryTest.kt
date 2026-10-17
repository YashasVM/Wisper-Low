package com.wisperlow.mobile.keyboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory learned store that keeps the latest data per language, like the files do. */
private class RecordingStore : LearnedStore {
    val writes = HashMap<KeyboardLanguage, MutableList<Lexicon.LearnedData>>()
    private val latest = HashMap<KeyboardLanguage, Lexicon.LearnedData>()

    override fun read(language: KeyboardLanguage): Lexicon.LearnedData? = latest[language]

    override fun write(language: KeyboardLanguage, data: Lexicon.LearnedData) {
        writes.getOrPut(language) { ArrayList() } += data
        latest[language] = data
    }

    fun written(language: KeyboardLanguage): List<Lexicon.LearnedData> = writes[language].orEmpty()

    fun savedWord(language: KeyboardLanguage, word: String): Boolean =
        written(language).any { data -> data.words.any { it.first == word } }
}

/**
 * The repository's lifecycle: which engine is published, and when each language's learning is
 * written. The loader and store are seams so load timing is controlled; the repository and its
 * switching are real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LexiconRepositoryTest {
    private fun repository(
        scheduler: TestCoroutineScheduler,
        loader: EngineLoader,
        store: LearnedStore = RecordingStore(),
    ) = LexiconRepository(StandardTestDispatcher(scheduler), loader, store)

    @Test
    fun `a superseded load never publishes`() = runTest {
        val hiGate = CompletableDeferred<Unit>()
        val loader = EngineLoader { language ->
            when (language) {
                KeyboardLanguage.HINDI -> { hiGate.await(); Lexicon(emptyList()) }
                KeyboardLanguage.KANNADA -> Lexicon(listOf("knword" to 5L))
                else -> null
            }
        }
        val repository = repository(testScheduler, loader)

        repository.selectLanguage(KeyboardLanguage.HINDI)
        advanceUntilIdle()
        // Hindi is still building, so nothing is published yet.
        assertNull(repository.engine.value)

        repository.selectLanguage(KeyboardLanguage.KANNADA)
        advanceUntilIdle()
        assertEquals(KeyboardLanguage.KANNADA, repository.language.value)
        assertEquals(1, (repository.engine.value as Lexicon).size)

        // The late Hindi build must not replace Kannada once it finally arrives.
        hiGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(KeyboardLanguage.KANNADA, repository.language.value)
        assertEquals(1, (repository.engine.value as Lexicon).size)
    }

    @Test
    fun `rapid English to Hindi to Kannada keeps the outgoing learning`() = runTest {
        val engines = KeyboardLanguage.entries.associateWith { Lexicon(emptyList()) }
        val store = RecordingStore()
        val repository = repository(testScheduler, { engines[it] }, store)

        repository.load()
        advanceUntilIdle()
        assertSame(engines.getValue(KeyboardLanguage.ENGLISH), repository.engine.value)
        repository.engine.value!!.learn("hello", null)

        repository.selectLanguage(KeyboardLanguage.HINDI)
        repository.selectLanguage(KeyboardLanguage.KANNADA)
        advanceUntilIdle()

        assertSame(engines.getValue(KeyboardLanguage.KANNADA), repository.engine.value)
        assertTrue(store.savedWord(KeyboardLanguage.ENGLISH, "hello"))
    }

    @Test
    fun `switching away and back restores the learned words`() = runTest {
        val store = RecordingStore()
        // A fresh engine each load, as a real process would rebuild it from disk.
        val repository = repository(testScheduler, { Lexicon(emptyList()) }, store)

        repository.load()
        advanceUntilIdle()
        repository.engine.value!!.learn("hello", null)

        repository.selectLanguage(KeyboardLanguage.HINDI)
        advanceUntilIdle()
        repository.selectLanguage(KeyboardLanguage.ENGLISH)
        advanceUntilIdle()

        assertEquals(KeyboardLanguage.ENGLISH, repository.language.value)
        assertTrue(repository.engine.value!!.isLearned("hello"))
        assertTrue(store.savedWord(KeyboardLanguage.ENGLISH, "hello"))
    }

    @Test
    fun `a debounced save still lands when the language switches`() = runTest {
        val engines = KeyboardLanguage.entries.associateWith { Lexicon(emptyList()) }
        val store = RecordingStore()
        val repository = repository(testScheduler, { engines[it] }, store)

        repository.load()
        advanceUntilIdle()
        repository.engine.value!!.learn("hello", null)

        repository.scheduleSave()
        repository.selectLanguage(KeyboardLanguage.HINDI)
        advanceUntilIdle()

        assertTrue(store.savedWord(KeyboardLanguage.ENGLISH, "hello"))
        assertSame(engines.getValue(KeyboardLanguage.HINDI), repository.engine.value)
    }

    @Test
    fun `reselection of the current language keeps the engine`() = runTest {
        val repository = repository(testScheduler, { Lexicon(emptyList()) })
        repository.load()
        advanceUntilIdle()
        val engine = repository.engine.value

        repository.selectLanguage(KeyboardLanguage.ENGLISH)
        advanceUntilIdle()

        assertSame(engine, repository.engine.value)
    }

    @Test
    fun `a selection is visible before its engine finishes loading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = EngineLoader { language ->
            if (language == KeyboardLanguage.HINDI) { gate.await(); Lexicon(emptyList()) } else null
        }
        val repository = repository(testScheduler, loader)

        repository.selectLanguage(KeyboardLanguage.HINDI)
        assertEquals(KeyboardLanguage.HINDI, repository.language.value)
        assertNull(repository.engine.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(KeyboardLanguage.HINDI, repository.language.value)
        assertTrue(repository.engine.value is Lexicon)
    }

    @Test
    fun `a failed load from an old switch does not clear the newer one`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var hindiLoads = 0
        val loader = EngineLoader { language ->
            if (language == KeyboardLanguage.HINDI) {
                when (++hindiLoads) {
                    // The superseded build fails; the newer build of the same language succeeds.
                    1 -> { firstGate.await(); null }
                    else -> { secondGate.await(); Lexicon(listOf("hi" to 1L)) }
                }
            } else {
                null
            }
        }
        val repository = repository(testScheduler, loader)

        repository.selectLanguage(KeyboardLanguage.HINDI)     // build 1
        advanceUntilIdle()
        repository.selectLanguage(KeyboardLanguage.KANNADA)
        repository.selectLanguage(KeyboardLanguage.HINDI)     // build 2, same language, newer
        advanceUntilIdle()
        assertEquals(2, hindiLoads)

        // Build 1 now fails. It must not retire build 2's in-flight marker for this language.
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertNull(repository.engine.value)

        // So a retry finds the marker and does not launch a third build.
        repository.load()
        advanceUntilIdle()
        assertEquals(2, hindiLoads)

        secondGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(KeyboardLanguage.HINDI, repository.language.value)
        assertEquals(1, (repository.engine.value as Lexicon).size)
        assertEquals(2, hindiLoads)
    }

    @Test
    fun `a listener switching on the emitted engine is not left holding it`() = runTest {
        val engines = KeyboardLanguage.entries.associateWith { Lexicon(emptyList()) }
        val repository = repository(testScheduler, { engines[it] })
        // Runs inline on the publishing thread, so the switch reenters the transition that is
        // publishing the engine: the guarded state must still end consistent.
        val listener = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).launch {
            repository.engine.collect { engine ->
                if (engine === engines.getValue(KeyboardLanguage.HINDI) &&
                    repository.language.value == KeyboardLanguage.HINDI
                ) {
                    repository.selectLanguage(KeyboardLanguage.KANNADA)
                }
            }
        }

        repository.load()
        advanceUntilIdle()
        repository.selectLanguage(KeyboardLanguage.HINDI)
        advanceUntilIdle()

        assertEquals(KeyboardLanguage.KANNADA, repository.language.value)
        assertSame(engines.getValue(KeyboardLanguage.KANNADA), repository.engine.value)
        listener.cancel()
    }
}
