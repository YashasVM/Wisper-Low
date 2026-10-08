package com.wisperlow.mobile.keyboard

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads the word list once per process and keeps what the keyboard learns on disk.
 * Learned words stay in the app's private storage and are never sent anywhere.
 */
@Singleton
class LexiconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file get() = File(context.filesDir, "keyboard/learned.tsv")
    private var saveJob: Job? = null

    private val _lexicon = MutableStateFlow<Lexicon?>(null)
    /** Null until the word list has loaded, which takes a fraction of a second. */
    val lexicon: StateFlow<Lexicon?> = _lexicon.asStateFlow()

    private var loading: Job? = null

    /** Starts loading if needed; safe to call on every keyboard show. */
    @Synchronized
    fun load() {
        if (_lexicon.value != null || loading?.isActive == true) return
        loading = scope.launch {
            val started = System.nanoTime()
            val lexicon = try {
                val entries = context.assets.open(DICTIONARY).bufferedReader().useLines { Lexicon.parse(it) }
                Lexicon(entries)
            } catch (t: Throwable) {
                Log.e(TAG, "Word list unavailable", t)
                Lexicon(emptyList())
            }
            runCatching { readLearned()?.let(lexicon::importLearned) }
                .onFailure { Log.w(TAG, "Learned words unreadable; starting fresh", it) }
            Log.i(TAG, "Loaded ${lexicon.size} words in ${(System.nanoTime() - started) / 1_000_000} ms")
            _lexicon.value = lexicon
        }
    }

    /** Saves learned words a moment after the last change, so typing never waits on disk. */
    fun scheduleSave() {
        val lexicon = _lexicon.value ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            runCatching { write(lexicon.exportLearned()) }.onFailure { Log.w(TAG, "Saving learned words failed", it) }
        }
    }

    fun forgetLearned() {
        _lexicon.value?.forgetLearned()
        saveJob?.cancel()
        scope.launch { file.delete() }
    }

    private fun readLearned(): Lexicon.LearnedData? {
        if (!file.isFile) return null
        val words = ArrayList<Pair<String, Int>>()
        val pairs = ArrayList<Triple<String, String, Int>>()
        file.forEachLine { line ->
            val parts = line.split('\t')
            when {
                parts.size == 3 && parts[0] == "w" -> parts[2].toIntOrNull()?.let { words += parts[1] to it }
                parts.size == 4 && parts[0] == "p" -> parts[3].toIntOrNull()?.let { pairs += Triple(parts[1], parts[2], it) }
            }
        }
        return Lexicon.LearnedData(words, pairs)
    }

    private fun write(data: Lexicon.LearnedData) {
        val target = file
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.bufferedWriter().use { out ->
            data.words.forEach { (w, c) -> out.write("w\t$w\t$c\n") }
            // Keep the most used pairs so the file stays small.
            data.pairs.sortedByDescending { it.third }.take(MAX_SAVED_PAIRS).forEach { (a, b, c) -> out.write("p\t$a\t$b\t$c\n") }
        }
        if (!temp.renameTo(target)) temp.delete()
    }

    private companion object {
        const val TAG = "LexiconRepository"
        const val DICTIONARY = "dict/en.txt"
        const val SAVE_DELAY_MS = 3_000L
        const val MAX_SAVED_PAIRS = 30_000
    }
}
