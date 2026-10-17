package com.wisperlow.mobile.keyboard

import android.content.Context
import android.util.Log
import com.wisperlow.mobile.keyboard.indic.IndicWordEngine
import com.wisperlow.mobile.keyboard.indic.RomanizationIndex
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "LexiconRepository"

/** Builds the engine for a language. A seam so tests can control when a load finishes. */
internal fun interface EngineLoader {
    suspend fun load(language: KeyboardLanguage): WordEngine?
}

/** Reads and writes a language's learned words. A seam so tests can avoid the file system. */
internal interface LearnedStore {
    fun read(language: KeyboardLanguage): Lexicon.LearnedData?
    fun write(language: KeyboardLanguage, data: Lexicon.LearnedData)
}

/**
 * Loads the selected input language into a [WordEngine] and keeps what the keyboard learns on
 * disk. English reads the word lists; the romanized Hindi and Kannada modes read their pair
 * assets. Learned words stay in the app's private storage and are never sent anywhere, and each
 * language keeps its own learned file so one mode's words never leak into another.
 *
 * Everything that decides *what* to load and *when* to publish happens under [lock], which is
 * never held while assets are parsed or the disk is touched. Builds run on one IO scope, and
 * writes are keyed by language and coalesced, so a rapid switch saves each mode's learning
 * exactly once and a load that the user has already moved past can never be published.
 */
@Singleton
class LexiconRepository internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val loader: EngineLoader,
    private val store: LearnedStore,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        Dispatchers.IO,
        AssetEngineLoader(context),
        FileLearnedStore(context),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** The engine answering keystrokes, and the language it belongs to. */
    private class Publication(val language: KeyboardLanguage, val engine: WordEngine)

    /** Guards every field below; never held across asset parsing or disk IO. */
    private val lock = Any()
    private var selected = KeyboardLanguage.ENGLISH

    /** Rises on every selection, so an older load can tell it has been superseded. */
    private var generation = 0

    /** The language of the build currently requested or in flight, so duplicates are not launched. */
    private var opening: KeyboardLanguage? = null
    private var publication: Publication? = null

    /** The last write scheduled per language, so an open can wait for it before reading. */
    private val writes = HashMap<KeyboardLanguage, Deferred<Unit>>()

    /** Serializes disk access, so no two writes, or a read and a write, overlap. */
    private val io = Mutex()

    private val _engine = MutableStateFlow<WordEngine?>(null)
    /** The engine for the selected language, or null until it has loaded. */
    val engine: StateFlow<WordEngine?> = _engine.asStateFlow()

    private val _language = MutableStateFlow(KeyboardLanguage.ENGLISH)
    /** The language the keyboard should type in; [engine] follows a moment later. */
    val language: StateFlow<KeyboardLanguage> = _language.asStateFlow()

    /** Starts loading the selected language if needed; safe to call on every keyboard show. */
    fun load() {
        val language: KeyboardLanguage
        val generation: Int
        synchronized(lock) {
            language = selected
            if (publication?.language == language) return
            if (opening == language) return
            opening = language
            generation = ++this.generation
        }
        scope.launch { open(language, generation) }
    }

    /**
     * Switches modes. The new selection and the invalidation of the old engine happen together,
     * so a load already in flight for the previous language can never publish afterwards. The
     * outgoing engine's learning is queued independently of that load, so a rapid switch never
     * drops what the user taught.
     */
    fun selectLanguage(language: KeyboardLanguage) {
        val outgoing: Publication?
        val generation: Int
        synchronized(lock) {
            if (selected == language) return
            selected = language
            generation = ++this.generation
            opening = language
            outgoing = publication
            publication = null
        }
        _language.value = language
        _engine.value = null
        outgoing?.let { write(it, delayMs = 0L) }
        scope.launch { open(language, generation) }
    }

    /** Saves learned words a moment after the last change, so typing never waits on disk. */
    fun scheduleSave() {
        val current: Publication
        // Capture the language and its engine as one unit: a switch cannot split them apart.
        synchronized(lock) { current = publication ?: return }
        write(current, delayMs = SAVE_DELAY_MS)
    }

    /** Builds [language], imports its learning, and publishes only if it is still the selection. */
    private suspend fun open(language: KeyboardLanguage, generation: Int) {
        val started = System.nanoTime()
        // A write already queued for this language must land first, so the read sees it.
        awaitWrites(language)
        val engine = runCatching { loader.load(language) }.getOrNull()
        if (engine == null) {
            finishOpen(language)
            return
        }
        awaitWrites(language)
        val learned = io.withLock {
            runCatching { store.read(language) }
                .onFailure { Log.w(TAG, "Learned words unreadable; starting fresh", it) }
                .getOrNull()
        }
        learned?.let(engine::importLearned)
        val published = synchronized(lock) {
            if (this.generation != generation) return@synchronized false
            publication = Publication(language, engine)
            opening = null
            true
        }
        if (published) {
            _engine.value = engine
            Log.i(TAG, "Loaded ${language.code} in ${(System.nanoTime() - started) / 1_000_000} ms")
        } else {
            finishOpen(language)
        }
    }

    /** Clears a stale [opening] marker so a later [load] can retry, but never the live one. */
    private fun finishOpen(language: KeyboardLanguage) {
        synchronized(lock) { if (opening == language && publication?.language != language) opening = null }
    }

    /** Waits for the latest scheduled write for [language], including one queued while waiting. */
    private suspend fun awaitWrites(language: KeyboardLanguage) {
        while (true) {
            val pending = synchronized(lock) { writes[language] } ?: return
            pending.join()
            if (synchronized(lock) { writes[language] } === pending) return
        }
    }

    /** Schedules a write, coalescing it with any earlier one for the same language. */
    private fun write(publication: Publication, delayMs: Long) {
        val language = publication.language
        val engine = publication.engine
        val job = scope.async {
            if (delayMs > 0) delay(delayMs)
            io.withLock {
                runCatching { store.write(language, engine.exportLearned()) }
                    .onFailure { Log.w(TAG, "Saving ${language.code} learning failed", it) }
                Unit
            }
        }
        synchronized(lock) {
            writes[language]?.cancel()
            writes[language] = job
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 3_000L
    }
}

/** The real loader: reads the word lists for English and the romanization pairs for the rest. */
private class AssetEngineLoader(private val context: Context) : EngineLoader {
    override suspend fun load(language: KeyboardLanguage): WordEngine? = try {
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
        // A missing Indian asset leaves an engine over an empty index, so Latin is kept as
        // typed rather than silently falling back to English corrections.
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

    private companion object {
        const val DICTIONARY = "dict/en.txt"
        const val MODERN = "dict/en_modern.txt"
        const val HINDI = "indic/hi.tsv"
        const val KANNADA = "indic/kn.tsv"

        /** Arbitrary dimensions for laying out the canonical board; only their ratio matters. */
        const val BOARD_WIDTH = 1080f
        const val ROW_HEIGHT = 150f
        const val SIDE = 10f
    }
}

/** The real store: one learned file per language, under the app's private storage. */
private class FileLearnedStore(private val context: Context) : LearnedStore {
    override fun read(language: KeyboardLanguage): Lexicon.LearnedData? = LearnedFile.read(fileFor(language))

    override fun write(language: KeyboardLanguage, data: Lexicon.LearnedData) = LearnedFile.write(fileFor(language), data)

    private fun fileFor(language: KeyboardLanguage) = File(
        context.filesDir,
        when (language) {
            KeyboardLanguage.ENGLISH -> "keyboard/learned.tsv"
            KeyboardLanguage.HINDI -> "keyboard/learned_hi.tsv"
            KeyboardLanguage.KANNADA -> "keyboard/learned_kn.tsv"
        },
    )
}
