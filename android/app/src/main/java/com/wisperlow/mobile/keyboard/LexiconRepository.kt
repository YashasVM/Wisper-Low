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
                // Subtitles miss how people text and code today; the modern list fills that in.
                val modern = context.assets.open(MODERN).bufferedReader().useLines { Lexicon.parse(it) }
                Lexicon(Lexicon.merge(entries, modern))
            } catch (t: Throwable) {
                Log.e(TAG, "Word list unavailable", t)
                Lexicon(emptyList())
            }
            runCatching { LearnedFile.read(file)?.let(lexicon::importLearned) }
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
            runCatching { LearnedFile.write(file, lexicon.exportLearned()) }.onFailure { Log.w(TAG, "Saving learned words failed", it) }
        }
    }

    private companion object {
        const val TAG = "LexiconRepository"
        const val DICTIONARY = "dict/en.txt"
        const val MODERN = "dict/en_modern.txt"
        const val SAVE_DELAY_MS = 3_000L
    }
}
