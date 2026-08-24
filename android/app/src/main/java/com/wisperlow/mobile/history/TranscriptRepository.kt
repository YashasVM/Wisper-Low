package com.wisperlow.mobile.history

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.OutputStreamWriter
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class TranscriptEntry(
    val id: String,
    val timestampMillis: Long,
    val text: String,
    val wordCount: Int = text.wordCount(),
)

@Singleton
class TranscriptRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = AtomicFile(File(context.filesDir, "transcripts.txt"))
    private val lock = Any()
    private var loaded = false
    private val _entries = MutableStateFlow<List<TranscriptEntry>>(emptyList())

    val entries: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    suspend fun load() {
        withContext(Dispatchers.IO) {
            synchronized(lock) { loadLocked() }
        }
    }

    suspend fun add(text: String, timestampMillis: Long = System.currentTimeMillis()): TranscriptEntry =
        withContext(Dispatchers.IO) {
            val entry = TranscriptEntry(UUID.randomUUID().toString(), timestampMillis, text)
            synchronized(lock) {
                loadLocked()
                val updated = TranscriptHistory.bound(listOf(entry) + _entries.value)
                writeEntries(updated)
                _entries.value = updated
            }
            entry
        }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                loadLocked()
                val updated = _entries.value.filterNot { it.id == id }
                if (updated.size != _entries.value.size) {
                    writeEntries(updated)
                    _entries.value = updated
                }
            }
        }
    }

    private fun loadLocked() {
        if (loaded) return
        _entries.value = TranscriptHistory.bound(readEntries())
        loaded = true
    }

    private fun readEntries(): List<TranscriptEntry> = runCatching {
        file.openRead().bufferedReader().useLines { lines ->
            lines.mapNotNull(TranscriptLineCodec::decode).toList()
        }
    }.getOrDefault(emptyList())

    private fun writeEntries(entries: List<TranscriptEntry>) {
        val output = file.startWrite()
        try {
            OutputStreamWriter(output, Charsets.UTF_8).apply {
                write(entries.joinToString("\n", transform = TranscriptLineCodec::encode))
                flush()
            }
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}

internal object TranscriptHistory {
    const val MAX_ENTRIES = 100

    fun bound(entries: List<TranscriptEntry>): List<TranscriptEntry> =
        entries.sortedByDescending(TranscriptEntry::timestampMillis).take(MAX_ENTRIES)
}

internal object TranscriptLineCodec {
    fun encode(entry: TranscriptEntry): String = listOf(
        entry.id,
        entry.timestampMillis.toString(),
        Base64.getEncoder().encodeToString(entry.text.toByteArray(Charsets.UTF_8)),
    ).joinToString("\t")

    fun decode(line: String): TranscriptEntry? {
        val parts = line.split('\t')
        if (parts.size != 3 || parts[0].isBlank()) return null
        return runCatching {
            TranscriptEntry(
                id = parts[0],
                timestampMillis = parts[1].toLong(),
                text = String(Base64.getDecoder().decode(parts[2]), Charsets.UTF_8),
            )
        }.getOrNull()
    }
}

private fun String.wordCount(): Int = trim().split(Regex("\\s+")).count { it.isNotEmpty() }
