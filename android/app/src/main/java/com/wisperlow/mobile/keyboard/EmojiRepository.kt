package com.wisperlow.mobile.keyboard

import android.content.Context
import android.util.Log
import com.wisperlow.mobile.keyboard.emoji.EmojiIndex
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Emoji for typed words, from the CLDR keyword dictionaries in assets. English by
 * default, Hindi and Kannada chosen by the script the word is written in, so hints
 * work even before Indic typing routes are wired up. Dictionaries are parsed once
 * per process; a missing one only costs its hints, never typing itself.
 */
@Singleton
class EmojiRepository internal constructor(
    /**
     * Parses one language's dictionary in a single pass over an open stream. Production
     * points at assets; [EmojiIndex] consumes every line while the stream is open, so a
     * closed reader can never hand back a lazily-read dictionary.
     */
    private val read: (String) -> EmojiIndex,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Parsed dictionaries by language; a language absent here has no hints. */
    internal val available = MutableStateFlow<Map<String, EmojiIndex>>(emptyMap())

    private var started = false

    /** Reports a dictionary that could not be read; replaced in tests to stay off Android Log. */
    internal var missingDictionary: (String, Throwable) -> Unit = { language, error ->
        Log.w(TAG, "Emoji dictionary $language unavailable; its word hints are lost", error)
    }

    /** Starts loading if needed; safe to call on every keyboard show. */
    @Synchronized
    fun load() {
        if (started) return
        started = true
        scope.launch { refresh() }
    }

    /** Parses every dictionary once; suspending so tests can await the result. */
    internal suspend fun refresh() {
        val loaded = HashMap<String, EmojiIndex>(LANGUAGES.size)
        for (language in LANGUAGES) {
            try {
                loaded[language] = read(language)
            } catch (t: Throwable) {
                missingDictionary(language, t)
            }
        }
        if (loaded.isEmpty()) Log.i(TAG, "No emoji dictionaries; the strip offers no emoji hints")
        available.value = loaded
    }

    /** Up to [max] emoji for [word] in the script it is written in; empty before load or when unknown. */
    fun hints(word: String, max: Int = 3): List<String> {
        if (word.isBlank()) return emptyList()
        val index = available.value[script(word)] ?: return emptyList()
        return index.forWord(word, max)
    }

    private companion object {
        const val TAG = "EmojiRepository"
        val LANGUAGES = listOf("en", "hi", "kn")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object EmojiModule {
    /**
     * The dictionary is parsed inside the open asset stream: reading lines lazily after
     * the stream closes would lose every keyword.
     */
    @Provides
    @Singleton
    internal fun repository(@ApplicationContext context: Context): EmojiRepository = EmojiRepository { language ->
        context.assets.open("emoji/$language.tsv").bufferedReader().useLines { EmojiIndex(it) }
    }
}

/**
 * Which dictionary [word] belongs to, read from its characters rather than a language
 * setting: any Devanagari character wins, then any Kannada one, so words that mix
 * scripts still route somewhere useful.
 */
internal fun script(word: String): String = when {
    word.any { it in '\u0900'..'\u097F' } -> "hi"
    word.any { it in '\u0C80'..'\u0CFF' } -> "kn"
    else -> "en"
}
