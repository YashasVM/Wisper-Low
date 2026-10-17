package com.wisperlow.mobile.keyboard

import android.content.Context
import android.util.Log
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
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
 * Loads the word list once per process into a [KeyboardEngine] and keeps what the
 * keyboard learns on disk. Learned words stay in the app's private storage and are
 * never sent anywhere.
 */
@Singleton
class LexiconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file get() = File(context.filesDir, "keyboard/learned.tsv")
    private var saveJob: Job? = null

    private val _engine = MutableStateFlow<KeyboardEngine?>(null)
    /** Null until the word list has loaded, which takes a fraction of a second. */
    val engine: StateFlow<KeyboardEngine?> = _engine.asStateFlow()

    private var loading: Job? = null

    /** Starts loading if needed; safe to call on every keyboard show. */
    @Synchronized
    fun load() {
        if (_engine.value != null || loading?.isActive == true) return
        loading = scope.launch {
            val started = System.nanoTime()
            val base = readList(DICTIONARY) { Log.e(TAG, "Word list unavailable", it) }
            // Subtitles miss how people text and code today; the modern list fills that in.
            val modern = readList(MODERN) { Log.w(TAG, "Modern word list unavailable; using the base list", it) }
            val entries = if (modern.isEmpty()) base else Lexicon.merge(base, modern)
            val engine = try {
                KeyboardEngine.fromWordList(entries, keyCenters())
            } catch (t: Throwable) {
                Log.e(TAG, "Building the word engine failed", t)
                null
            }
            if (engine != null) {
                runCatching { LearnedFile.read(file)?.let(engine::importLearned) }
                    .onFailure { Log.w(TAG, "Learned words unreadable; starting fresh", it) }
                // The engine itself has no count to read; the word list it was built from does.
                val words = entries.distinctBy { it.first }.size
                Log.i(TAG, "Loaded $words words in ${(System.nanoTime() - started) / 1_000_000} ms")
                _engine.value = engine
            }
        }
    }

    /** Reads a word list from assets, logging and continuing when the file is missing. */
    private fun readList(name: String, onFailure: (Throwable) -> Unit): List<Pair<String, Long>> =
        try {
            context.assets.open(name).bufferedReader().useLines { Lexicon.parse(it) }
        } catch (t: Throwable) {
            onFailure(t)
            emptyList()
        }

    /**
     * Canonical QWERTY key centres, in key units: q at (0.5, 0.5), a at (1.0, 1.5),
     * z at (2.0, 2.5). The spatial model and the touch resolver judge touches against
     * these, so every engine is built over the same grid no matter the screen.
     */
    private fun keyCenters(): List<KeyCenter> {
        val rows = KeyboardLayout.rows(Page.LETTERS, FieldKind.TEXT, numberRow = false)
        val placed = KeyboardGeometry.layout(rows, BOARD_WIDTH, ROW_HEIGHT, SIDE)
        val units = KeyboardGeometry.units(placed, ROW_HEIGHT, SIDE)
        return KeyboardGeometry.letterCenters(placed, units)
    }

    /** Saves learned words a moment after the last change, so typing never waits on disk. */
    fun scheduleSave() {
        val engine = _engine.value ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            runCatching { LearnedFile.write(file, engine.exportLearned()) }.onFailure { Log.w(TAG, "Saving learned words failed", it) }
        }
    }

    private companion object {
        const val TAG = "LexiconRepository"
        const val DICTIONARY = "dict/en.txt"
        const val MODERN = "dict/en_modern.txt"
        const val SAVE_DELAY_MS = 3_000L

        /** Arbitrary dimensions for laying out the canonical board; only their ratio matters. */
        const val BOARD_WIDTH = 1080f
        const val ROW_HEIGHT = 150f
        const val SIDE = 10f
    }
}
