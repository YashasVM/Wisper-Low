package com.wisperlow.mobile.keyboard

import kotlin.math.abs
import kotlin.math.ln

/** What the suggestion strip should offer for the word being typed. */
data class Suggestions(
    val words: List<String> = emptyList(),
    /** The word that space or punctuation will replace the typed word with, if any. */
    val autoCorrect: String? = null,
) {
    companion object {
        val None = Suggestions()
    }
}

/**
 * English word frequencies plus what this user has typed. Finds completions and
 * spelling fixes, weighing typos between neighbouring keys as cheaper than others,
 * and learns words and word pairs so its suggestions become personal.
 *
 * Thread-safe: queries may run on a worker while the keyboard learns on the main thread.
 */
class Lexicon(entries: List<Pair<String, Long>>) {
    private val words: Array<String>
    private val keys: Array<String>
    private val scores: FloatArray

    /** Normalized key to every surface form with that key, e.g. "cant" to [can't, cant]. */
    private val byKey = HashMap<String, IntArray>()

    /** Word indices sorted by key, for prefix completion. */
    private val sorted: IntArray

    /** Word indices bucketed by key length, for edit-distance search. */
    private val byLength: Array<IntArray>

    private val userLock = Any()
    private val userWords = HashMap<String, UserWord>()
    private val bigrams = HashMap<String, HashMap<String, Int>>()

    private class UserWord(var surface: String, var count: Int)

    init {
        val distinct = LinkedHashMap<String, Long>()
        for ((word, count) in entries) {
            if (word.isNotBlank() && word !in distinct) distinct[word] = count
        }
        words = distinct.keys.toTypedArray()
        keys = Array(words.size) { key(words[it]) }
        scores = FloatArray(words.size) { ln(distinct.getValue(words[it]).coerceAtLeast(1).toFloat() + 1f) }
        val grouped = HashMap<String, MutableList<Int>>()
        keys.forEachIndexed { i, k -> grouped.getOrPut(k) { ArrayList(1) } += i }
        grouped.forEach { (k, list) -> byKey[k] = list.sortedByDescending { scores[it] }.toIntArray() }
        sorted = keys.indices.sortedWith(compareBy({ keys[it] }, { -scores[it] })).toIntArray()
        val maxLen = keys.maxOfOrNull { it.length } ?: 0
        val buckets = Array(maxLen + 1) { ArrayList<Int>() }
        keys.forEachIndexed { i, k -> buckets[k.length] += i }
        byLength = Array(buckets.size) { buckets[it].toIntArray() }
    }

    val size: Int get() = words.size

    /** True when [word] is in the dictionary or the user has made it theirs. */
    fun isKnown(word: String): Boolean {
        val k = key(word)
        if (k.isEmpty()) return false
        if (isTypedWord(word, k)) return true
        return synchronized(userLock) { userWords[k]?.let { it.count >= USER_TRUSTED && it.surface.equals(word, true) } == true }
    }

    fun suggest(typed: String, previous: String? = null, max: Int = 3): Suggestions {
        val k = key(typed)
        if (k.isEmpty() || typed.any { !it.isLetter() && !isApostrophe(it) }) return Suggestions(listOf(typed).filter { it.isNotEmpty() })
        val prev = previous?.let(::key)?.takeIf { it.isNotEmpty() }
        val candidates = HashMap<String, Candidate>()
        fun offer(word: String, score: Float, kind: Kind, distance: Float = 0f) {
            val existing = candidates[word.lowercase()]
            if (existing == null || existing.score < score) candidates[word.lowercase()] = Candidate(word, score, kind, distance)
        }

        // Same letters, maybe different apostrophes or capitals: "dont", "monday", "i".
        byKey[k]?.forEach { offer(words[it], scores[it] + context(prev, keys[it]), Kind.EXACT) }
        // Completions of what was typed so far.
        for (i in completions(k, COMPLETION_POOL)) {
            if (keys[i].length > k.length) {
                offer(words[i], scores[i] + context(prev, keys[i]) - COMPLETION_PENALTY - 0.08f * (keys[i].length - k.length), Kind.COMPLETION)
            }
        }
        // Typos. Short words only tolerate one slip.
        val maxDistance = when {
            k in SLANG -> 0f
            // Two letters only tolerate a swap or a neighbouring key: "ot", "si".
            k.length <= 2 -> SWAP_COST
            k.length <= 4 -> 1f
            else -> 2f
        }
        if (maxDistance > 0f) {
            val reach = if (k.length <= 3) 0 else maxDistance.toInt()
            val lengths = (k.length - reach).coerceAtLeast(1)..(k.length + reach)
            for (len in lengths) {
                val bucket = byLength.getOrNull(len) ?: continue
                val first = k[0]
                val second = k.getOrNull(1)
                for (i in bucket) {
                    val w = keys[i]
                    // People rarely miss the first letter; skip words that could only match by doing so.
                    val c = w[0]
                    if (c != first && c != second && !Qwerty.adjacent(c, first)) continue
                    if (w == k) continue
                    val d = distance(k, keys[i], maxDistance)
                    if (d <= maxDistance) offer(words[i], scores[i] + context(prev, keys[i]) - typoPenalty(d), Kind.CORRECTION, d)
                }
            }
        }
        synchronized(userLock) {
            for ((uk, user) in userWords) {
                if (user.count < USER_TRUSTED && uk != k) continue
                val bonus = USER_BASE + ln(user.count.toFloat() + 1f) + context(prev, uk)
                when {
                    uk == k -> offer(user.surface, bonus, Kind.EXACT)
                    uk.startsWith(k) -> offer(user.surface, bonus - COMPLETION_PENALTY, Kind.COMPLETION)
                    maxDistance > 0f && abs(uk.length - k.length) <= maxDistance -> {
                        val d = distance(k, uk, maxDistance)
                        if (d <= maxDistance) offer(user.surface, bonus - typoPenalty(d), Kind.CORRECTION, d)
                    }
                }
            }
        }

        val ranked = candidates.values.sortedByDescending { it.score }
        val auto = autoCorrection(typed, k, ranked)
        val others = ranked.map { matchCase(typed, it.word) }.filter { it != typed && it != auto }.distinct()
        // Slots read left to right; the middle one is what space will type, as on most keyboards.
        val slots = when {
            auto != null -> listOf(typed, auto) + others.take(1)
            isCommonWord(typed, k) -> listOfNotNull(others.getOrNull(0), typed, others.getOrNull(1))
            else -> listOf(typed) + others.take(2)
        }
        return Suggestions(slots.take(max), auto)
    }

    /** "the", "The" and "THE" are all the word "the"; "monday" is not "Monday" and "i" is not "I". */
    private fun isTypedWord(typed: String, k: String): Boolean =
        byKey[k]?.any { sameWord(words[it], typed) } == true

    private fun isCommonWord(typed: String, k: String): Boolean =
        byKey[k]?.any { sameWord(words[it], typed) && scores[it] > COMMON_SCORE } == true

    private fun sameWord(surface: String, typed: String): Boolean =
        surface.equals(typed, ignoreCase = true) &&
            (surface.none { it.isUpperCase() } || typed.first().isUpperCase() || surface == typed)

    /** Likely next words after [previous], from what this user has typed before. */
    fun predict(previous: String?, max: Int = 3): List<String> {
        val prev = previous?.let(::key)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val next = synchronized(userLock) { bigrams[prev]?.entries?.sortedByDescending { it.value }?.take(max * 2)?.map { it.key } }
            ?: return emptyList()
        return next.mapNotNull(::surfaceFor).take(max)
    }

    /** Counts a word the user committed, after [previous] if there was one. */
    fun learn(word: String, previous: String?) {
        val k = key(word)
        if (k.isEmpty() || word.any { !it.isLetter() && !isApostrophe(it) }) return
        synchronized(userLock) {
            if (!isTypedWord(word, k)) {
                val user = userWords.getOrPut(k) { UserWord(word, 0) }
                user.count++
                // Keep the user's spelling of names and brands ("iPhone", "Ngozi") once they use it.
                if (word.any { it.isUpperCase() }) user.surface = word
            }
            val prev = previous?.let(::key)?.takeIf { it.isNotEmpty() } ?: return
            val next = bigrams.getOrPut(prev) { HashMap() }
            next[k] = (next[k] ?: 0) + 1
            if (bigrams.size > MAX_BIGRAM_HEADS) bigrams.remove(bigrams.keys.first())
        }
    }

    /** The user undid a correction: treat [word] as theirs from now on. */
    fun trust(word: String) {
        val k = key(word)
        if (k.isEmpty()) return
        synchronized(userLock) {
            val user = userWords.getOrPut(k) { UserWord(word, 0) }
            user.count = maxOf(user.count, USER_TRUSTED)
        }
    }

    fun forgetLearned() {
        synchronized(userLock) {
            userWords.clear()
            bigrams.clear()
        }
    }

    /** Learned words and word pairs, for saving. */
    fun exportLearned(): LearnedData = synchronized(userLock) {
        LearnedData(
            words = userWords.values.map { it.surface to it.count },
            pairs = bigrams.flatMap { (prev, next) -> next.map { (n, c) -> Triple(prev, n, c) } },
        )
    }

    fun importLearned(data: LearnedData) {
        synchronized(userLock) {
            for ((word, count) in data.words) {
                val k = key(word)
                if (k.isNotEmpty()) userWords[k] = UserWord(word, count)
            }
            for ((prev, next, count) in data.pairs) bigrams.getOrPut(prev) { HashMap() }[next] = count
        }
    }

    private fun surfaceFor(k: String): String? =
        byKey[k]?.firstOrNull()?.let { words[it] } ?: synchronized(userLock) { userWords[k]?.surface }

    private fun context(prev: String?, k: String): Float {
        if (prev == null) return 0f
        val count = synchronized(userLock) { bigrams[prev]?.get(k) } ?: return 0f
        return PAIR_BASE + ln(count.toFloat() + 1f)
    }

    private fun autoCorrection(typed: String, k: String, ranked: List<Candidate>): String? {
        if (typed.length < 2 && !typed.equals("i", ignoreCase = true)) return null
        val typedIsWord = isTypedWord(typed, k)
        val user = synchronized(userLock) { userWords[k] }
        if (user != null && user.count >= USER_TRUSTED) return null
        val exact = byKey[k]?.firstOrNull()
        if (exact != null) {
            val best = words[exact]
            if (typedIsWord) {
                // "cant" is a word, but "can't" is what people mean; "its" is not "it's".
                val own = byKey.getValue(k).first { sameWord(words[it], typed) }
                if (k in AMBIGUOUS || k in SLANG) return null
                if (own == exact || scores[exact] - scores[own] < VARIANT_MARGIN) return rareWordFix(typed, scores[own], ranked)
            }
            val fixed = matchCase(typed, best)
            return fixed.takeIf { it != typed }
        }
        val top = ranked.firstOrNull { it.kind == Kind.CORRECTION } ?: return null
        // Three letters leave little to go on; only fix them into everyday words ("teh", "hte").
        if (k.length <= 3 && top.score + typoPenalty(top.distance) < COMMON_SCORE) return null
        val rival = ranked.firstOrNull { it !== top && it.kind != Kind.COMPLETION }
        if (rival != null && top.score - rival.score < CONFIDENCE_MARGIN) return null
        // A completion that beats every fix means the user is probably still typing that word.
        val completion = ranked.firstOrNull { it.kind == Kind.COMPLETION }
        if (completion != null && completion.score > top.score + CONFIDENCE_MARGIN) return null
        return matchCase(typed, top.word)
    }

    /**
     * The word list comes from real-world text, so it holds misspellings like "realy".
     * A rare typed word still gets fixed when a far more common word is one slip away.
     */
    private fun rareWordFix(typed: String, ownScore: Float, ranked: List<Candidate>): String? {
        if (ownScore > RARE_SCORE) return null
        val top = ranked.firstOrNull { it.kind == Kind.CORRECTION && it.distance <= 1f } ?: return null
        val rawTop = top.score + typoPenalty(top.distance)
        return if (rawTop - ownScore >= RARE_RATIO) matchCase(typed, top.word) else null
    }

    private fun completions(prefix: String, limit: Int): List<Int> {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[sorted[mid]] < prefix) lo = mid + 1 else hi = mid
        }
        // Keep the best few by score in one pass over the matching range.
        val best = ArrayList<Int>(limit + 1)
        var i = lo
        while (i < sorted.size && keys[sorted[i]].startsWith(prefix)) {
            val idx = sorted[i]
            if (best.size < limit || scores[idx] > scores[best.last()]) {
                val at = best.indexOfFirst { scores[it] < scores[idx] }.let { if (it < 0) best.size else it }
                best.add(at, idx)
                if (best.size > limit) best.removeAt(best.lastIndex)
            }
            i++
        }
        return best
    }

    private enum class Kind { EXACT, COMPLETION, CORRECTION }

    private class Candidate(val word: String, val score: Float, val kind: Kind, val distance: Float)

    data class LearnedData(val words: List<Pair<String, Int>>, val pairs: List<Triple<String, String, Int>>)

    companion object {
        private const val COMPLETION_POOL = 8
        private const val COMPLETION_PENALTY = 1.2f
        private const val TYPO_WEIGHT = 3.2f
        private const val SECOND_TYPO_WEIGHT = 5f
        private const val CONFIDENCE_MARGIN = 0.6f

        /** ln(count) under which a dictionary word is rare enough to be a likely typo. */
        private const val RARE_SCORE = 7f

        /** ln(count) above which a typed word is common enough to keep the middle slot over completions. */
        private const val COMMON_SCORE = 9.5f

        /** ln of how many times more common a fix must be than a rare typed word (about 50x). */
        private const val RARE_RATIO = 3.9f
        private const val VARIANT_MARGIN = 3.5f
        private const val USER_BASE = 9f
        private const val USER_TRUSTED = 2
        private const val PAIR_BASE = 1.5f
        private const val MAX_BIGRAM_HEADS = 20_000

        /** Real words that look like a missing apostrophe but are often meant as typed. */
        private val AMBIGUOUS = setOf("its", "well", "were", "hell", "shell", "ill", "lets", "shed", "wed", "id", "hed", "whos", "cause")

        /** Reads "word<TAB>count" lines; lines starting with # are comments. */
        fun parse(lines: Sequence<String>): List<Pair<String, Long>> = lines
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).trim().toLongOrNull() ?: 1L)
            }
            .toList()

        fun key(word: String): String = buildString(word.length) {
            for (c in word) if (!isApostrophe(c)) append(c.lowercaseChar())
        }

        /** Shorthand people mean to type; never "fixed" into a dictionary word. */
        private val SLANG = setOf(
            "lol", "lmao", "omg", "omw", "idk", "btw", "brb", "ppl", "tbh", "imo", "imho", "ngl", "smh", "thx", "pls", "plz",
            "ur", "gonna", "wanna", "gotta", "ok", "okay", "ya", "yea", "yep", "nope", "haha", "hahaha", "hmm", "ugh", "wtf",
            "ty", "np", "rn", "irl", "fyi", "asap", "af", "bc", "cuz", "tho", "dm", "dms", "lmk", "hbu", "wyd", "jk", "nvm",
            "tmrw", "bday", "xd", "fr", "istg", "ikr", "gm", "gn", "ttyl", "yk", "ig", "w", "l",
        )

        private fun typoPenalty(d: Float): Float =
            if (d <= 1f) TYPO_WEIGHT * d else TYPO_WEIGHT + SECOND_TYPO_WEIGHT * (d - 1f)

        fun isApostrophe(c: Char) = c == '\'' || c == '’'

        /** Gives [word] the capitalization the user typed: "Teh" to "The", "TEH" to "THE". */
        fun matchCase(typed: String, word: String): String = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

        /**
         * Damerau-Levenshtein distance where a slip onto a neighbouring key costs less
         * than any other substitution. Stops early once [limit] is exceeded.
         */
        fun distance(a: String, b: String, limit: Float): Float {
            if (abs(a.length - b.length) > limit) return Float.MAX_VALUE
            val cols = b.length + 1
            var prev2 = FloatArray(cols)
            var prev = FloatArray(cols) { it.toFloat() }
            var cur = FloatArray(cols)
            for (i in 1..a.length) {
                cur[0] = i.toFloat()
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val ca = a[i - 1]
                    val cb = b[j - 1]
                    val sub = if (ca == cb) 0f else if (Qwerty.adjacent(ca, cb)) NEAR_KEY_COST else 1f
                    // Dropping or doubling a repeated letter ("helo", "untill") is the most common slip.
                    val drop = if (i > 1 && ca == a[i - 2]) DOUBLE_COST else 1f
                    val add = if (j > 1 && cb == b[j - 2]) DOUBLE_COST else 1f
                    var v = minOf(prev[j] + drop, cur[j - 1] + add, prev[j - 1] + sub)
                    if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = minOf(v, prev2[j - 2] + SWAP_COST)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > limit) return Float.MAX_VALUE
                val t = prev2
                prev2 = prev
                prev = cur
                cur = t
            }
            return prev[b.length]
        }

        private const val NEAR_KEY_COST = 0.7f
        private const val SWAP_COST = 0.7f
        private const val DOUBLE_COST = 0.3f
    }
}

/** Key positions on a QWERTY board, for judging which typos are likely. */
internal object Qwerty {
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val offsets = floatArrayOf(0f, 0.5f, 1.5f)
    private val position = HashMap<Char, Pair<Float, Int>>().apply {
        rows.forEachIndexed { r, row -> row.forEachIndexed { c, ch -> put(ch, (c + offsets[r]) to r) } }
    }

    fun adjacent(a: Char, b: Char): Boolean {
        val pa = position[a] ?: return false
        val pb = position[b] ?: return false
        return abs(pa.second - pb.second) <= 1 && abs(pa.first - pb.first) <= 1.01f
    }
}
