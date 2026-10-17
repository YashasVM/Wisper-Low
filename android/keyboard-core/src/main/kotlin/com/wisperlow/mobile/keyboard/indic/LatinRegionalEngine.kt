package com.wisperlow.mobile.keyboard.indic

import com.wisperlow.mobile.keyboard.Lexicon
import com.wisperlow.mobile.keyboard.Qwerty
import com.wisperlow.mobile.keyboard.Suggestions
import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.WordEngine
import java.util.Locale

/**
 * Regional words written and committed in Latin letters, including mixed English text.
 * Each vocabulary has its own [Lexicon], so a large English corpus cannot displace regional
 * completions by count alone. Counts rank manual suggestions within their own vocabulary;
 * the legacy lexicon's absolute autocorrection thresholds are deliberately not used here.
 *
 * An attested spelling is always kept, however rare. Untaught automatic changes require touch
 * evidence for one unambiguous keyboard transposition or adjacent consonant substitution.
 * Vowel changes and added/dropped letters remain manual because they can be valid romanizations.
 * Without touch evidence an unknown spelling stays literal. Trusting a word prevents future
 * changes, and explicit taught fixes take precedence over this policy.
 */
class LatinRegionalEngine(
    regional: List<Pair<String, Long>>,
    english: List<Pair<String, Long>>,
    nextWords: List<Triple<String, String, Long>> = emptyList(),
) : WordEngine {
    private val regionalEntries = cleanEntries(regional)
    private val englishEntries = cleanEntries(english)
    private val regionalWords = regionalEntries.mapTo(HashSet()) { it.first }
    private val englishWords = englishEntries.mapTo(HashSet()) { it.first }
    private val regionalLexicon = Lexicon(regionalEntries)
    private val englishLexicon = Lexicon(englishEntries)
    private val personal = Lexicon(emptyList())
    private val fallback = regionalEntries.sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).map { it.first }
    private val corpusNext = nextWords.asSequence()
        .filter { (prev, next, count) -> isLatinWord(prev) && isLatinWord(next) && count > 0 }
        .groupBy({ normalized(it.first) }, { normalized(it.second) to it.third })
        .mapValues { (_, entries) ->
            entries.groupBy({ it.first }, { it.second }).mapValues { (_, counts) -> counts.fold(0L, ::saturatingAdd) }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).map { it.key }
        }

    @Synchronized
    override fun suggest(typed: String, previous: String?, max: Int, touches: List<Touch?>?): Suggestions {
        if (max <= 0 || !isLatinWord(typed)) return Suggestions.None
        val key = normalized(typed)
        val prev = previous?.takeIf(::isLatinWord)
        val learned = personal.suggest(typed, prev, max, touches)
        val taught = personal.learnedFixFor(typed)
        val mixedCase = typed.drop(1).any { it.isUpperCase() } && typed.any { it.isLowerCase() }
        val auto = when {
            max < 2 -> null
            mixedCase -> null
            taught != null -> Lexicon.matchCase(typed, taught)
            isKnown(typed) -> null
            else -> keyboardCorrection(key, touches)?.let { Lexicon.matchCase(typed, it) }
        }?.takeIf { it != typed }

        val candidates = LinkedHashSet<String>()
        candidates += typed
        auto?.let { candidates += it }
        candidates += learned.words.filter { it != typed }
        val regionalSuggestions = regionalLexicon.suggest(typed, prev, max, touches).words.filter { it != typed }
        val englishSuggestions = englishLexicon.suggest(typed, prev, max, touches).words.filter { it != typed }
        // Alternate the two independent rankings. The first regional completion stays reachable
        // alongside the first English completion on a three-slot strip.
        for (i in 0 until maxOf(regionalSuggestions.size, englishSuggestions.size)) {
            regionalSuggestions.getOrNull(i)?.let { candidates += Lexicon.matchCase(typed, it) }
            englishSuggestions.getOrNull(i)?.let { candidates += Lexicon.matchCase(typed, it) }
        }
        return Suggestions(candidates.filter(::isLatinWord).take(max), auto)
    }

    private fun keyboardCorrection(typed: String, touches: List<Touch?>?): String? {
        if (typed.length < 5 || typed.any { it !in 'a'..'z' } || Lexicon.isExpressive(typed) || touches?.size != typed.length) return null
        val candidates = HashSet<String>()
        val letters = typed.toCharArray()
        for (i in 0 until letters.lastIndex) {
            if (letters[i] == letters[i + 1]) continue
            if (letters[i] in VOWELS && letters[i + 1] in VOWELS) continue
            val left = touches[i] ?: continue
            val right = touches[i + 1] ?: continue
            if (!favors(left, letters[i + 1], letters[i]) || !favors(right, letters[i], letters[i + 1])) continue
            val first = letters[i]
            letters[i] = letters[i + 1]
            letters[i + 1] = first
            val swapped = String(letters)
            if (swapped in regionalWords) candidates += swapped
            letters[i + 1] = letters[i]
            letters[i] = first
        }
        for (i in letters.indices) {
            val original = letters[i]
            if (original in VOWELS) continue
            val touch = touches[i] ?: continue
            for (replacement in 'a'..'z') {
                if (replacement == original || replacement in VOWELS || !Qwerty.adjacent(original, replacement)) continue
                if (!favors(touch, replacement, original)) continue
                letters[i] = replacement
                val corrected = String(letters)
                if (corrected in regionalWords) candidates += corrected
            }
            letters[i] = original
        }
        return candidates.singleOrNull()
    }

    private fun favors(touch: Touch, intended: Char, typed: Char): Boolean =
        Qwerty.slipCost(touch, intended) + TOUCH_MARGIN < Qwerty.slipCost(touch, typed)

    @Synchronized
    override fun predict(previous: String?, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val prev = previous?.takeIf(::isLatinWord)
        val words = LinkedHashSet<String>()
        words += personal.predict(prev, max)
        prev?.let { corpusNext[normalized(it)] }?.let { words += it.take(max) }
        words += fallback.take(max)
        return words.filter(::isLatinWord).take(max)
    }

    @Synchronized
    override fun isKnown(word: String): Boolean = isLatinWord(word) &&
        (normalized(word) in regionalWords || normalized(word) in englishWords || personal.isKnown(word))

    @Synchronized
    override fun learn(word: String, previous: String?) {
        if (isLatinWord(word)) personal.learn(word, previous?.takeIf(::isLatinWord))
    }

    @Synchronized
    override fun learnFix(typed: String, intended: String) {
        if (isLatinWord(typed) && isLatinWord(intended)) {
            personal.learnFix(typed, intended)
        }
    }

    @Synchronized
    override fun trust(word: String) {
        if (isLatinWord(word)) {
            personal.trust(word)
        }
    }

    @Synchronized
    override fun isLearned(word: String): Boolean = isLatinWord(word) && personal.isLearned(word)

    @Synchronized
    override fun forget(word: String) {
        if (isLatinWord(word)) {
            personal.forget(word)
        }
    }

    @Synchronized
    override fun exportLearned(): Lexicon.LearnedData = personal.exportLearned()

    @Synchronized
    override fun importLearned(data: Lexicon.LearnedData) {
        val clean = Lexicon.LearnedData(
            words = data.words.filter { (word, count) -> isLatinWord(word) && count > 0 },
            pairs = data.pairs.filter { (prev, next, count) -> isLatinWord(prev) && isLatinWord(next) && count > 0 }
                .map { (prev, next, count) -> Triple(Lexicon.key(prev), Lexicon.key(next), count) },
            fixes = data.fixes.filter { (typed, intended, count) -> isLatinWord(typed) && isLatinWord(intended) && count > 0 }
                .map { (typed, intended, count) -> Triple(Lexicon.key(typed), intended, count) },
        )
        personal.importLearned(clean)
    }

    private companion object {
        const val VOWELS = "aeiouy"
        const val TOUCH_MARGIN = 0.15f

        fun normalized(word: String): String = word.lowercase(Locale.ROOT)

        fun isLatinWord(word: String): Boolean = word.any { it.isLetter() } && word.all {
            Lexicon.isApostrophe(it) || (it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN)
        }

        fun saturatingAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

        fun cleanEntries(entries: List<Pair<String, Long>>): List<Pair<String, Long>> = entries.asSequence()
            .filter { (word, count) -> isLatinWord(word) && count > 0 }
            .groupBy({ normalized(it.first) }, { it.second })
            .map { (word, counts) -> word to counts.fold(0L, ::saturatingAdd) }
    }
}
