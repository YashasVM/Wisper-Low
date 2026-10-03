package com.wisperlow.mobile.history

import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptRepositoryTest {

    private class FakeStore(var content: String? = null, var failReads: Boolean = false) : TranscriptStore {
        var writes = 0
        override fun read(): String? {
            if (failReads) throw IOException("disk error")
            return content ?: throw FileNotFoundException()
        }

        override fun write(text: String) {
            writes++
            content = text
        }
    }

    @Test
    fun unreadableHistoryIsNotOverwrittenByANewEntry() = runBlocking {
        val saved = TranscriptLineCodec.encode(TranscriptEntry("old", 1L, "keep me"))
        val store = FakeStore(content = saved, failReads = true)
        val repo = TranscriptRepository(store)

        repo.load()
        val failed = runCatching { repo.add("new") }
        assertTrue(failed.isFailure)
        assertEquals(0, store.writes)
        assertEquals(saved, store.content)

        store.failReads = false
        repo.add("new")
        assertEquals(listOf("new", "keep me"), repo.entries.value.map { it.text })
    }

    @Test
    fun missingFileStartsEmptyAndSaves() = runBlocking {
        val store = FakeStore()
        val repo = TranscriptRepository(store)
        repo.add("hello")
        assertEquals(listOf("hello"), repo.entries.value.map { it.text })
        assertEquals(1, store.writes)
    }
}
