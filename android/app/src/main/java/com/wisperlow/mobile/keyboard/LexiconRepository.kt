package com.wisperlow.mobile.keyboard

import android.content.Context
import android.util.Log
import com.wisperlow.mobile.keyboard.indic.IndicWordEngine
import com.wisperlow.mobile.keyboard.indic.RomanizationIndex
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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
 * Loads the selected input language into a [WordEngine] and keeps what the keyboard learns on
 * disk. English reads the word lists; the romanized Hindi and Kannada modes read their pair
 * assets. Learned words stay in the app's private storage and are never sent anywhere, and each
 * language keeps its own learned file so one mode's words never leak into another.
 */
@Singleton
class LexiconRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var loadJob: Job? = null

    /** Rises on every load, so a slow load cannot overwrite a newer selection. */
    private val generation = AtomicInteger(0)

    private val _engine = MutableStateFlow<WordEngine?>(null)
    /** The engine for the selected language, or null until it has loaded. */
    val engine: StateFlow<WordEngine?> = _engine.asStateFlow()

    private val _language = MutableStateFlow(KeyboardLanguage.ENGLISH)
    /** The language the keyboard should type in; [engine] follows a moment later. */
    val language: StateFlow<KeyboardLanguage> = _language.asStateFlow()

    /** The language of the engine currently in [engine]; null while a load is in flight. */
    @Volatile
    private var loaded: KeyboardLanguage? = null

    /** Starts loading the selected language if needed; safe to call on every keyboard show. */
    @Synchronized
    fun load() {
        if (loaded == _language.value && _engine.value != null) return
        if (loadJob?.isActive == true) return
        loadJob = scope.launch { open(_language.value, generation.incrementAndGet(), null, null) }
    }

    /**
     * Switches modes. The old mode's learning is saved under its own file first, and the engine
     * is cleared until the new one is ready, so a stale language can never answer a keystroke.
     */
    @Synchronized
    fun selectLanguage(language: KeyboardLanguage) {
        if (language == _language.value) return
        _language.value = language
        val previous = loaded
        val previousEngine = _engine.value
        loadJob?.cancel()
        saveJob?.cancel()
        _engine.value = null
        loaded = null
        loadJob = scope.launch { open(language, generation.incrementAndGet(), previous, previousEngine) }
    }

    /** Builds an engine, importing its learned file; gives up if a newer selection arrived. */
    private fun open(
        language: KeyboardLanguage,
        generation: Int,
        previous: KeyboardLanguage?,
        previousEngine: WordEngine?,
    ) {
        val started = System.nanoTime()
        if (previous != null && previousEngine != null) {
            runCatching { LearnedFile.write(fileFor(previous), previousEngine.exportLearned()) }
                .onFailure { Log.w(TAG, "Saving ${previous.code} learning failed", it) }
        }
        val engine = build(language) ?: return
        if (this.generation.get() != generation) return
        runCatching { LearnedFile.read(fileFor(language))?.let(engine::importLearned) }
            .onFailure { Log.w(TAG, "Learned words unreadable; starting fresh", it) }
        if (this.generation.get() != generation) return
        _engine.value = engine
        loaded = language
        Log.i(TAG, "Loaded ${language.code} in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /**
     * The engine for [language]. A missing Indian asset leaves an engine over an empty index, so
     * the user's Latin is kept as typed rather than silently falling back to English corrections.
     */
    private fun build(language: KeyboardLanguage): WordEngine? = try {
        when (language) {
            KeyboardLanguage.ENGLISH -> {
                val base = readList(DICTIONARY) { Log.e(TAG, "Word list unavailable", it) }
                // Subtitles miss how people text and code today; the modern list fills that in.
                val modern = readList(MODERN) { Log.w(TAG, "Modern word list unavailable; using the base list", it) }
                val entries = if (modern.isEmpty()) base else Lexicon.merge(base, modern)
                KeyboardEngine.fromWordList(entries, keyCenters())
            }
            KeyboardLanguage.HINDI -> IndicWordEngine(readIndex(HINDI))
            KeyboardLanguage.KANNADA -> IndicWordEngine(readIndex(KANNADA))
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Building the ${language.code} engine failed", t)
        if (language.isIndic) IndicWordEngine(RomanizationIndex(emptySequence())) else null
    }

    /** Reads "roman<TAB>native<TAB>count" rows; the index consumes them while the file is open. */
    private fun readIndex(asset: String): RomanizationIndex = try {
        context.assets.open(asset).bufferedReader().useLines { lines -> RomanizationIndex(lines) }
    } catch (t: Throwable) {
        Log.w(TAG, "$asset unavailable; native suggestions are off for now", t)
        RomanizationIndex(emptySequence())
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
        // Capture the language now: a later mode switch must not file these words elsewhere.
        val language = loaded ?: return
        val engine = _engine.value ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            runCatching { LearnedFile.write(fileFor(language), engine.exportLearned()) }
                .onFailure { Log.w(TAG, "Saving learned words failed", it) }
        }
    }

    private fun fileFor(language: KeyboardLanguage) = File(
        context.filesDir,
        when (language) {
            KeyboardLanguage.ENGLISH -> "keyboard/learned.tsv"
            KeyboardLanguage.HINDI -> "keyboard/learned_hi.tsv"
            KeyboardLanguage.KANNADA -> "keyboard/learned_kn.tsv"
        },
    )

    private companion object {
        const val TAG = "LexiconRepository"
        const val DICTIONARY = "dict/en.txt"
        const val MODERN = "dict/en_modern.txt"
        const val HINDI = "indic/hi.tsv"
        const val KANNADA = "indic/kn.tsv"
        const val SAVE_DELAY_MS = 3_000L

        /** Arbitrary dimensions for laying out the canonical board; only their ratio matters. */
        const val BOARD_WIDTH = 1080f
        const val ROW_HEIGHT = 150f
        const val SIDE = 10f
    }
}
