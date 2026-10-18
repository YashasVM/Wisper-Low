package com.wisperlow.mobile.keyboard

import android.content.Context
import android.util.Log
import com.wisperlow.mobile.keyboard.indic.LatinRegionalEngine
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * disk. English reads its word lists; Hindi and Kannada read regional Latin word and bigram
 * tables alongside English words for code mixing. Learned words stay in the app's private
 * storage and are never sent anywhere. Each language keeps its own learned file.
 *
 * An immutable selection under [lock] owns the language and engine. The public flows project
 * that selection: retiring the engine precedes announcing a new language, and a synchronous
 * listener can start another transition without an older emission overwriting it. Parsing and
 * disk IO happen outside [lock]. Pending writes are registered before any transition is emitted,
 * so a rapid switch back waits for the outgoing learning before restoring it.
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
        FileLearnedStore(context.filesDir),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Guards every field below and the state flows themselves; never held for parsing or IO. */
    private val lock = Any()

    private data class Selection(val language: KeyboardLanguage, val engine: WordEngine?)
    private var selection = Selection(KeyboardLanguage.ENGLISH, null)

    /** Rises on every selection, so an older load can tell it has been superseded. */
    private var generation = 0

    /** The language of the build currently requested or in flight, so duplicates are not launched. */
    private var opening: KeyboardLanguage? = null

    /** The last write scheduled per language, so an open can wait for it before reading. */
    private val writes = HashMap<KeyboardLanguage, Deferred<Unit>>()

    /** Serializes disk access, so no two writes, or a read and a write, overlap. */
    private val io = Mutex()

    /**
     * Observable projections of [selection]. Mutators read the authoritative selection rather
     * than these separate flows, because a flow listener may synchronously reenter a transition.
     */
    private val _language = MutableStateFlow(KeyboardLanguage.ENGLISH)
    val language: StateFlow<KeyboardLanguage> = _language.asStateFlow()

    private val _engine = MutableStateFlow<WordEngine?>(null)
    val engine: StateFlow<WordEngine?> = _engine.asStateFlow()

    /** Starts loading the selected language if needed; safe to call on every keyboard show. */
    fun load() {
        val language: KeyboardLanguage
        val generation: Int
        synchronized(lock) {
            language = selection.language
            if (selection.engine != null) return
            if (opening == language) return
            opening = language
            generation = ++this.generation
        }
        scope.launch { open(language, generation) }
    }

    /**
     * Switches modes. The new selection, the retirement of the old engine, and the flow writes
     * are one transition under [lock], so a load in flight for the previous language can never
     * publish afterwards and two selectors can never reorder. The outgoing engine's learning is
     * queued independently of that load, so a rapid switch never drops what the user taught.
     */
    fun selectLanguage(language: KeyboardLanguage) {
        val generation: Int
        val pending: PendingWrite?
        synchronized(lock) {
            if (selection.language == language) return
            val outgoing = selection
            selection = Selection(language, null)
            generation = ++this.generation
            opening = language
            pending = outgoing.engine?.let { queueWriteLocked(outgoing.language, it, delayMs = 0L) }
            _engine.value = null
            // The null-engine listener may already have selected another language.
            if (this.generation == generation) _language.value = language
        }
        pending?.start()
        scope.launch { open(language, generation) }
    }

    /** Saves learned words a moment after the last change, so typing never waits on disk. */
    fun scheduleSave() {
        val pending = synchronized(lock) {
            val current = selection
            val engine = current.engine ?: return
            queueWriteLocked(current.language, engine, SAVE_DELAY_MS)
        }
        pending.start()
    }

    /** Builds [language], imports its learning, and publishes only if it is still the selection. */
    private suspend fun open(language: KeyboardLanguage, generation: Int) {
        if (synchronized(lock) { this.generation != generation }) return
        val started = System.nanoTime()
        // A write already queued for this language must land first, so the read sees it.
        awaitWrites(language)
        val engine = runCatching { loader.load(language) }.getOrNull()
        if (engine == null) {
            // Only the request that still owns [opening] may retire it. An older build that
            // fails after an A-B-A switch leaves the marker for the newer build of this same
            // language, so a retry is not launched on top of it.
            synchronized(lock) { if (this.generation == generation) opening = null }
            return
        }
        awaitWrites(language)
        val learned = io.withLock {
            runCatching { store.read(language) }
                .onFailure { Log.w(TAG, "Learned words unreadable; starting fresh", it) }
                .getOrNull()
        }
        learned?.let(engine::importLearned)
        // The staleness check and the publication are one transition: the engine and the
        // selection move together, so nothing can publish an engine the user has already left.
        val published = synchronized(lock) {
            if (this.generation != generation) {
                false
            } else {
                opening = null
                selection = Selection(language, engine)
                _engine.value = engine
                true
            }
        }
        if (published) Log.i(TAG, "Loaded ${language.code} in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** Waits for the latest scheduled write for [language], including one queued while waiting. */
    private suspend fun awaitWrites(language: KeyboardLanguage) {
        while (true) {
            val pending = synchronized(lock) { writes[language] } ?: return
            pending.join()
            if (synchronized(lock) { writes[language] } === pending) return
        }
    }

    /**
     * Schedules a write of [engine]'s learning for [language], coalescing it with any earlier
     * pending one. The job is registered while holding [lock] (before it starts) so a concurrent
     * open always sees it, and the replaced job is cancelled only after its replacement is in
     * place; the engine is exported when the write runs, so the newest words are what land.
     */
    private class PendingWrite(private val next: Deferred<Unit>, private val previous: Deferred<Unit>?) {
        fun start() {
            next.start()
            previous?.cancel()
        }
    }

    /** Called under [lock]; registering a lazy job does not perform disk IO or emit state. */
    private fun queueWriteLocked(language: KeyboardLanguage, engine: WordEngine, delayMs: Long): PendingWrite {
        val job = scope.async(start = CoroutineStart.LAZY) {
            if (delayMs > 0) delay(delayMs)
            io.withLock {
                runCatching { store.write(language, engine.exportLearned()) }
                    .onFailure { Log.w(TAG, "Saving ${language.code} learning failed", it) }
                Unit
            }
        }
        val previous = writes.put(language, job)
        return PendingWrite(job, previous)
    }

    private companion object {
        const val SAVE_DELAY_MS = 3_000L
    }
}

/** Loads English and regional Latin dictionaries without converting text into another script. */
internal class AssetEngineLoader(private val readAsset: (String) -> List<String>) : EngineLoader {
    constructor(context: Context) : this({ name ->
        context.assets.open(name).bufferedReader().use { it.readLines() }
    })

    override suspend fun load(language: KeyboardLanguage): WordEngine? = try {
        when (language) {
            KeyboardLanguage.ENGLISH -> KeyboardEngine.fromWordList(readEnglish(), keyCenters())
            KeyboardLanguage.HINDI -> readRegional(HINDI, HINDI_BIGRAMS)
            KeyboardLanguage.KANNADA -> readRegional(KANNADA, KANNADA_BIGRAMS)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Building the ${language.code} engine failed", t)
        if (language.isIndic) LatinRegionalEngine(emptyList(), emptyList()) else null
    }

    private fun readEnglish(): List<Pair<String, Long>> {
        val base = readList(DICTIONARY) { Log.e(TAG, "Word list unavailable", it) }
        val modern = readList(MODERN) { Log.w(TAG, "Modern word list unavailable; using the base list", it) }
        return if (modern.isEmpty()) base else Lexicon.merge(base, modern)
    }

    private fun readRegional(wordsAsset: String, bigramsAsset: String): WordEngine {
        val regional = try {
            readAsset(wordsAsset).mapNotNull { line ->
                if (line.startsWith('#')) return@mapNotNull null
                val columns = line.split('\t')
                if (columns.size != 2 || !isLatinWord(columns[0])) return@mapNotNull null
                val count = columns[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                columns[0] to count
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$wordsAsset unavailable; preserving typed text", t)
            emptyList()
        }
        if (regional.isEmpty()) return LatinRegionalEngine(emptyList(), emptyList())
        val nextWords = try {
            readAsset(bigramsAsset).mapNotNull { line ->
                if (line.startsWith('#')) return@mapNotNull null
                val columns = line.split('\t')
                if (columns.size != 3) return@mapNotNull null
                val count = columns[2].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                if (!isLatinWord(columns[0]) || !isLatinWord(columns[1])) return@mapNotNull null
                Triple(columns[0], columns[1], count)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$bigramsAsset unavailable; regional next-word suggestions are off", t)
            emptyList()
        }
        return LatinRegionalEngine(regional, readEnglish(), nextWords)
    }

    /** Reads an asset word list, logging and continuing when the file is missing. */
    private fun readList(name: String, onFailure: (Throwable) -> Unit): List<Pair<String, Long>> =
        try {
            Lexicon.parse(readAsset(name).asSequence())
        } catch (t: Throwable) {
            onFailure(t)
            emptyList()
        }

    private fun isLatinWord(word: String): Boolean =
        word.isNotEmpty() && word.any { it in 'a'..'z' } && word.all { it in 'a'..'z' || it == '\'' }

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
        const val HINDI = "dict/hi_latn.tsv"
        const val KANNADA = "dict/kn_latn.tsv"
        const val HINDI_BIGRAMS = "dict/hi_latn_bigrams.tsv"
        const val KANNADA_BIGRAMS = "dict/kn_latn_bigrams.tsv"

        /** Arbitrary dimensions for laying out the canonical board; only their ratio matters. */
        const val BOARD_WIDTH = 1080f
        const val ROW_HEIGHT = 150f
        const val SIDE = 10f
    }
}

/** The real store: one learned file per language, under the app's private storage. */
internal class FileLearnedStore(private val filesDir: File) : LearnedStore {
    override fun read(language: KeyboardLanguage): Lexicon.LearnedData? = LearnedFile.read(fileFor(language))

    override fun write(language: KeyboardLanguage, data: Lexicon.LearnedData) = LearnedFile.write(fileFor(language), data)

    private fun fileFor(language: KeyboardLanguage) = File(
        filesDir,
        when (language) {
            KeyboardLanguage.ENGLISH -> "keyboard/learned.tsv"
            KeyboardLanguage.HINDI -> "keyboard/learned_hi_latn.tsv"
            KeyboardLanguage.KANNADA -> "keyboard/learned_kn_latn.tsv"
        },
    )
}
