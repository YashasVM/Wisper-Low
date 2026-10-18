package com.wisperlow.mobile.keyboard.lm

import java.io.File
import java.text.Normalizer
import kotlin.math.pow

/**
 * What this user has typed: word counts and word pairs, so the engine can blend their
 * habits into a [LanguageModel]. Counts merge by [Vocabulary.keyOf], keep the user's
 * capitals once they use them ("iPhone"), and halve every [HALF_LIFE] so old habits stop
 * dominating; decay is applied lazily from the stored last-update time.
 *
 * Thread-safe: queries may run on a worker while the keyboard learns on the main thread.
 */
class UserHistory(private val clock: () -> Long = System::currentTimeMillis) {
    private class Word(val key: String, var surface: String, var count: Double, val seq: Long)
    private class Link(var count: Double, val seq: Long)
    private class Head(val links: HashMap<String, Link> = HashMap(), var total: Double = 0.0)

    private val lock = Any()
    private var words = HashMap<String, Word>()
    private var pairs = HashMap<String, Head>()
    private var wordTotal = 0.0
    private var linkCount = 0
    private var lastUpdate = clock()
    private var nextSeq = 0L

    /** Bumped when a word joins or leaves, so a cache of the word list knows to rebuild. */
    @Volatile
    var revision: Int = 0
        private set

    /** Bumped when any count changes, so a cached ranking knows it is stale. */
    @Volatile
    var countsRevision: Int = 0
        private set

    /** Counts one committed word, after [previous] if there was one. */
    fun learn(word: String, previous: String?) {
        val cleaned = nfc(word)
        val key = normalize(cleaned) ?: return
        val prevKey = previous?.let(::normalize)
        synchronized(lock) {
            decay()
            val created = key !in words
            val entry = words.getOrPut(key) { Word(key, cleaned, 0.0, nextSeq++) }
            if (created) revision++
            // Keep the user's spelling of names and brands ("iPhone", "Ngozi") once they use it.
            if (cleaned.any { it.isUpperCase() } && entry.surface != cleaned) {
                entry.surface = cleaned
                // The spelling is part of what a vocabulary holds, so caches must rebuild.
                revision++
            }
            entry.count += 1.0
            wordTotal += 1.0
            if (prevKey != null) {
                val head = pairs.getOrPut(prevKey) { Head() }
                val link = head.links.getOrPut(key) { Link(0.0, nextSeq++).also { linkCount++ } }
                link.count += 1.0
                head.total += 1.0
            }
            countsRevision++
            enforceCaps()
        }
    }

    /** Forgets one word: no longer counted, and no longer part of any pair. */
    fun forget(word: String) {
        val key = keyOf(word)
        synchronized(lock) {
            decay()
            if (removeWord(key)) countsRevision++
        }
    }

    /** True when the user's decayed count for [word] still reaches [USER_TRUSTED]. */
    fun isTrusted(word: String): Boolean = count(word) >= USER_TRUSTED

    /** The user's decayed count for [word]. */
    fun count(word: String): Double = synchronized(lock) {
        decay()
        words[keyOf(word)]?.count ?: 0.0
    }

    /** The user's decayed count for the pair [previous] then [word]. */
    fun pairCount(previous: String, word: String): Double = synchronized(lock) {
        decay()
        pairs[keyOf(previous)]?.links?.get(keyOf(word))?.count ?: 0.0
    }

    /** The user's decayed bigram evidence after [previous]: the denominator of P(next | previous). */
    fun evidenceAfter(previous: String): Double = synchronized(lock) {
        decay()
        pairs[keyOf(previous)]?.total ?: 0.0
    }

    /** All the user's decayed word evidence: the denominator of their unigram estimate. */
    fun totalCount(): Double = synchronized(lock) {
        decay()
        wordTotal
    }

    /** The user's likeliest words after [previous], best first, as surface to decayed count. */
    fun topAfter(previous: String, max: Int): List<Pair<String, Double>> {
        synchronized(lock) {
            decay()
            val head = pairs[keyOf(previous)]
            if (head != null) {
                return head.links.entries.sortedByDescending { it.value.count }.take(max).mapNotNull { (key, link) ->
                    words[key]?.let { it.surface to link.count }
                }
            }
        }
        return emptyList()
    }

    /** A snapshot of everything the user has typed: surface to decayed count. */
    fun words(): Map<String, Double> = synchronized(lock) {
        decay()
        val snapshot = HashMap<String, Double>(words.size * 2)
        for (word in words.values) snapshot[word.surface] = word.count
        snapshot
    }

    /** Saves to [file] as tab-separated lines, with the update time so decay survives a restart. */
    fun write(file: File) {
        synchronized(lock) {
            decay()
            file.parentFile?.mkdirs()
            // A temp file first, so a crash mid-save never loses what was learned.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.bufferedWriter().use { out ->
                out.write("t\t$lastUpdate\n")
                for (word in words.values) out.write("w\t${word.surface}\t${word.count}\n")
                for ((prevKey, head) in pairs) {
                    for ((nextKey, link) in head.links) out.write("p\t$prevKey\t$nextKey\t${link.count}\n")
                }
            }
            if (!temp.renameTo(file)) temp.delete()
        }
    }

    /** Replaces what is learned with [file]'s contents, decaying from the file's own update time. */
    fun read(file: File) {
        if (!file.isFile) return
        var stored = -1L
        var seq = 0L
        val loadedWords = HashMap<String, Word>()
        val rawLinks = ArrayList<Triple<String, String, Double>>()
        file.forEachLine { line ->
            val parts = line.split('\t')
            when {
                parts.size == 2 && parts[0] == "t" -> stored = parts[1].toLongOrNull() ?: -1L
                parts.size == 3 && parts[0] == "w" -> {
                    val count = parts[2].toDoubleOrNull() ?: return@forEachLine
                    val surface = nfc(parts[1])
                    val key = Vocabulary.keyOf(surface)
                    if (key.isEmpty() || count <= 0.0) return@forEachLine
                    val existing = loadedWords[key]
                    if (existing == null) loadedWords[key] = Word(key, surface, count, seq++)
                    else if (count > existing.count) existing.count = count
                }
                parts.size == 4 && parts[0] == "p" -> {
                    val count = parts[3].toDoubleOrNull() ?: return@forEachLine
                    if (count > 0.0) rawLinks += Triple(keyOf(parts[1]), keyOf(parts[2]), count)
                }
            }
        }
        val loadedPairs = HashMap<String, Head>()
        var loadedTotal = 0.0
        var loadedLinks = 0
        for (word in loadedWords.values) loadedTotal += word.count
        for ((prevKey, nextKey, count) in rawLinks) {
            // Keep every pair's target a word, so the user's estimates stay proper fractions.
            if (prevKey.isEmpty() || nextKey.isEmpty() || nextKey !in loadedWords) continue
            val head = loadedPairs.getOrPut(prevKey) { Head() }
            val link = head.links.getOrPut(nextKey) { Link(0.0, seq++).also { loadedLinks++ } }
            link.count += count
            head.total += count
        }
        synchronized(lock) {
            words = loadedWords
            pairs = loadedPairs
            wordTotal = loadedTotal
            linkCount = loadedLinks
            lastUpdate = if (stored >= 0) stored else clock()
            revision++
            countsRevision++
            decay()
            enforceCaps()
        }
    }

    /**
     * The word's key when it is something this history should remember, and null for
     * anything else. Letters and combining marks, so languages written with marks
     * (Hindi, Kannada) learn like Latin words do; the old apostrophe rule stays. A word
     * must open with a letter, which keeps leading-mark junk, URLs, digits, control
     * characters and emoji out.
     */
    private fun normalize(word: String): String? {
        val cleaned = nfc(word)
        var letters = 0
        var i = 0
        while (i < cleaned.length) {
            val cp = cleaned.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isLetter(cp) -> letters++
                isMark(cp) -> if (letters == 0) return null
                cp == '\''.code || cp == '’'.code -> {}
                else -> return null
            }
        }
        if (letters == 0) return null
        return Vocabulary.keyOf(cleaned).takeIf { it.isNotEmpty() }
    }

    /** The lookup key for any word: NFC first, so composed and decomposed spellings match. */
    private fun keyOf(word: String): String = Vocabulary.keyOf(nfc(word))

    private fun nfc(word: String): String =
        if (Normalizer.isNormalized(word, Normalizer.Form.NFC)) word else Normalizer.normalize(word, Normalizer.Form.NFC)

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp).toInt()
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    /** Halves every count once per [HALF_LIFE] since the last update; runs under the lock. */
    private fun decay() {
        val now = clock()
        if (now <= lastUpdate) return
        val halvings = (now - lastUpdate) / HALF_LIFE_MS
        if (halvings <= 0) return
        val factor = 0.5.pow(halvings.toDouble())
        for (word in words.values) word.count *= factor
        for (head in pairs.values) {
            head.total *= factor
            for (link in head.links.values) link.count *= factor
        }
        wordTotal *= factor
        lastUpdate += halvings * HALF_LIFE_MS
    }

    /**
     * Drops a word and every pair it takes part in, learned as an output or only ever
     * recorded as a context; true when anything changed.
     */
    private fun removeWord(key: String): Boolean {
        val word = words.remove(key)
        var removed = word != null
        if (word != null) {
            revision++
            wordTotal -= word.count
        }
        val head = pairs.remove(key)
        if (head != null) {
            linkCount -= head.links.size
            removed = true
        }
        val empty = ArrayList<String>()
        for ((prevKey, other) in pairs) {
            val link = other.links.remove(key)
            if (link != null) {
                other.total -= link.count
                linkCount--
                removed = true
            }
            if (other.links.isEmpty()) empty += prevKey
        }
        empty.forEach { pairs.remove(it) }
        return removed
    }

    /** Keeps the tables bounded, evicting the weakest entries down to a low-water mark. */
    private fun enforceCaps() {
        if (words.size > MAX_WORDS) evictWords(words.size - (MAX_WORDS - EVICT_HEADROOM))
        if (linkCount > MAX_PAIRS) evictPairs(linkCount - (MAX_PAIRS - EVICT_HEADROOM))
    }

    private fun evictWords(howMany: Int) {
        val victims = words.values.sortedWith(compareBy({ it.count }, { it.seq })).take(howMany).map { it.key }
        victims.forEach { removeWord(it) }
        countsRevision++
    }

    private fun evictPairs(howMany: Int) {
        val victims = ArrayList<Triple<String, String, Link>>()
        for ((prevKey, head) in pairs) for ((nextKey, link) in head.links) victims += Triple(prevKey, nextKey, link)
        victims.sortWith(compareBy({ it.third.count }, { it.third.seq }))
        for ((prevKey, nextKey, _) in victims.take(howMany)) {
            val head = pairs[prevKey] ?: continue
            val link = head.links.remove(nextKey) ?: continue
            head.total -= link.count
            linkCount--
            if (head.links.isEmpty()) pairs.remove(prevKey)
        }
        countsRevision++
    }

    companion object {
        /** How often counts halve: thirty days, so a year-old habit weighs a thousandth as much. */
        private const val HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

        /** How far below the caps to evict, so a full table does not sort on every word. */
        private const val EVICT_HEADROOM = 1_000

        private const val MAX_WORDS = 20_000
        private const val MAX_PAIRS = 50_000

        /** Twice, like the old Lexicon's USER_TRUSTED: one slip could make a word, twice makes it theirs. */
        private const val USER_TRUSTED = 2.0
    }
}
