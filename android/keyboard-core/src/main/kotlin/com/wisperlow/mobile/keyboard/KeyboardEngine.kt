package com.wisperlow.mobile.keyboard

import com.wisperlow.mobile.keyboard.decoder.ArrayWordIndex
import com.wisperlow.mobile.keyboard.decoder.BeamTypingDecoder
import com.wisperlow.mobile.keyboard.decoder.Candidate
import com.wisperlow.mobile.keyboard.decoder.CandidateKind
import com.wisperlow.mobile.keyboard.decoder.DecoderParams
import com.wisperlow.mobile.keyboard.decoder.KeyResolver
import com.wisperlow.mobile.keyboard.decoder.PolicyParams
import com.wisperlow.mobile.keyboard.decoder.ResolverParams
import com.wisperlow.mobile.keyboard.decoder.SuggestionPolicy
import com.wisperlow.mobile.keyboard.decoder.TrieNextCharModel
import com.wisperlow.mobile.keyboard.decoder.TypedFacts
import com.wisperlow.mobile.keyboard.decoder.TypedKey
import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import com.wisperlow.mobile.keyboard.spatial.GaussianSpatialModel
import com.wisperlow.mobile.keyboard.spatial.KeyCenter
import com.wisperlow.mobile.keyboard.spatial.SpatialModel
import com.wisperlow.mobile.keyboard.spatial.SpatialParams

/**
 * The new typing engine: a noisy-channel decoder over the word list, wrapped in the same
 * personal-word behaviour [Lexicon] has. Touch positions, language-model context and edit
 * penalties decide the reading; the user's own words and taught fixes are laid over it.
 *
 * Personal state (learned words, pairs and fixes) lives in a private [Lexicon] with no
 * word list of its own, so it can be saved and grown exactly as before.
 */
class KeyboardEngine(
    private val model: LanguageModel,
    keys: List<KeyCenter>,
    spatialParams: SpatialParams = SpatialParams(),
    decoderParams: DecoderParams = DecoderParams(),
    policyParams: PolicyParams = PolicyParams(),
    resolverParams: ResolverParams = ResolverParams(),
) : WordEngine {
    private val vocabulary = model.vocabulary
    private val index = ArrayWordIndex(model)
    private val spatial: SpatialModel = GaussianSpatialModel(keys, spatialParams)
    private val decoder = BeamTypingDecoder(index, model, spatial, decoderParams)
    private val policy = SuggestionPolicy(policyParams)
    private val resolver = KeyResolver(spatial, TrieNextCharModel(index, model), resolverParams)

    /** User words, pairs and fixes; the empty word list leaves the decoder to the word list. */
    private val user = Lexicon(emptyList())

    private val sentenceStart = vocabulary.id(Vocabulary.SENTENCE_START)

    /**
     * The model's most probable unigram, the reference that makes rarity a ratio rather
     * than a magic count: a word's log-odds against it do not move when every count is
     * scaled by the same constant.
     */
    private val topUnigram: Float by lazy {
        var best = Float.NEGATIVE_INFINITY
        for (id in 0 until vocabulary.size) {
            val p = model.unigramLogProb(id)
            if (p > best) best = p
        }
        best
    }

    override fun suggest(typed: String, previous: String?, max: Int, touches: List<Touch?>?): Suggestions {
        if (max <= 0) return Suggestions.None
        if (typed.isEmpty()) return Suggestions.None
        val key = Vocabulary.keyOf(typed)
        if (key.isEmpty() || typed.any { !it.isLetter() && !Lexicon.isApostrophe(it) }) {
            return Suggestions(listOf(typed).filter { it.isNotEmpty() })
        }
        // "sooo", "hahaha": stretched for feeling, never a typo to fix.
        if (Lexicon.isExpressive(key, known = vocabulary.idsForKey(key).isNotEmpty())) {
            return expressive(typed, key, max)
        }

        val literalIds = vocabulary.idsForKey(key)
        val context = contextOf(previous)
        val input = typedKeys(typed, touches)
        val decoded = decoder.decode(input, context, DECODE_POOL)
        // The user's own state is a small word list on top of the decoder's readings.
        val personal = user.suggest(typed, previous, max, touches)
        val ranking = ArrayList<Candidate>(decoded.size + personal.words.size + 1)
        // A fix the user taught by hand before beats anything the word list suggests.
        // Real dictionary words need two lessons before a taught fix applies. The fix
        // count is only needed when there is a taught fix for these letters, so the whole
        // learned snapshot is not exported (and its private dictionary copied) per key.
        val taughtCount = if (personal.autoCorrect != null) {
            user.exportLearned().fixes.firstOrNull { Lexicon.key(it.first) == key }?.third ?: 0
        } else {
            0
        }
        val taughtAllowed = taughtCount >= 2 || literalIds.isEmpty()
        if (taughtAllowed) {
            personal.autoCorrect?.let {
                ranking += Candidate(Vocabulary.UNKNOWN, it, TAUGHT_FIX_SCORE, 0f, 0f, CandidateKind.CORRECTION, 1)
            }
        }
        for ((rank, word) in personal.words.withIndex()) {
            if (word.equals(typed, ignoreCase = true)) continue
            if (!taughtAllowed && personal.autoCorrect != null && word.equals(personal.autoCorrect, ignoreCase = true)) continue
            ranking += Candidate(Vocabulary.UNKNOWN, word, USER_BASE - rank, 0f, 0f, kindOf(key, word), 0)
        }
        ranking += decoded

        // The typed letters are a dictionary word; how rare its entry is decides whether a
        // much more probable correction may replace it (the policy owns that threshold).
        val literalKnown = literalIds.isNotEmpty()
        val literalLogOdds = if (literalKnown) {
            literalIds.maxOf { model.unigramLogProb(it) } - topUnigram
        } else {
            0f
        }
        val facts = TypedFacts(
            literalKnown = literalKnown,
            userTrusted = user.isKnown(typed),
            literalLogOdds = literalLogOdds,
        )
        val decided = policy.decide(typed, ranking, facts, max.coerceAtLeast(0))
        return if (key in SLANG) decided.copy(autoCorrect = null) else decided
    }

    override fun predict(previous: String?, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val learned = user.predict(previous, max)
        if (learned.isNotEmpty()) return learned.take(max)
        // A learned word that has been forgotten must not leave model-only predictions behind.
        if (previous != null && vocabulary.id(previous) == Vocabulary.UNKNOWN && !user.isKnown(previous)) {
            return emptyList()
        }
        return model.predict(contextOf(previous), max)
            .map { vocabulary.surface(it.id) }
            .filter { it != Vocabulary.SENTENCE_START }
            .take(max)
    }

    override fun isKnown(word: String): Boolean {
        val key = Vocabulary.keyOf(word)
        if (key.isEmpty()) return false
        if (user.isKnown(word)) return true
        return vocabulary.idsForKey(key).any { vocabulary.surface(it).equals(word, ignoreCase = true) }
    }

    @Synchronized
    override fun learn(word: String, previous: String?) = user.learn(word, previous)

    @Synchronized
    override fun learnFix(typed: String, intended: String) = user.learnFix(typed, intended)

    @Synchronized
    override fun trust(word: String) = user.trust(word)

    override fun isLearned(word: String): Boolean = user.isLearned(word)

    @Synchronized
    override fun forget(word: String) = user.forget(word)

    override fun exportLearned(): Lexicon.LearnedData = user.exportLearned()

    @Synchronized
    override fun importLearned(data: Lexicon.LearnedData) = user.importLearned(data)

    /** The letter a tap types, corrected by touch position and what the model expects next. */
    fun resolveKey(touch: Touch, landedOn: Char, landed: KeyCenter, prefix: String, previous: String?): Char =
        resolver.resolve(touch, landedOn, landed, prefix, contextOf(previous))

    /** The preceding word ids, oldest first: "<s>" at a sentence start, [Vocabulary.UNKNOWN] otherwise. */
    private fun contextOf(previous: String?): IntArray {
        if (previous == null) return intArrayOf(sentenceStart)
        val id = vocabulary.id(previous)
        return intArrayOf(if (id == Vocabulary.UNKNOWN) Vocabulary.UNKNOWN else id)
    }

    /** One [TypedKey] per character, with its touch when the caller tracks them. */
    private fun typedKeys(typed: String, touches: List<Touch?>?): List<TypedKey> =
        if (touches != null && touches.size == typed.length) {
            typed.mapIndexed { i, c -> TypedKey(c, touches[i]) }
        } else {
            typed.map { TypedKey(it, null) }
        }

    private fun kindOf(key: String, word: String): CandidateKind {
        val wordKey = Vocabulary.keyOf(word)
        return when {
            wordKey == key -> CandidateKind.EXACT
            wordKey.startsWith(key) -> CandidateKind.COMPLETION
            else -> CandidateKind.CORRECTION
        }
    }

    /** "sooo" offers the common word it stretched ("so"), but is never corrected. */
    private fun expressive(typed: String, key: String, max: Int): Suggestions {
        val collapsed = collapseRuns(key)
        val shortened = collapsed.filterIndexed { i, c -> i == 0 || c != collapsed[i - 1] }
        val plain = listOf(collapsed, shortened)
            .flatMap { vocabulary.idsForKey(it).toList() }
            .maxByOrNull { model.unigramLogProb(it) }
            ?.let { Lexicon.matchCase(typed, vocabulary.surface(it)) }
        return Suggestions(listOfNotNull(typed, plain).distinct().take(max), autoCorrect = null)
    }

    /** Squeezes runs of three or more of a letter to two: "sooo" to "soo", "yesss" to "yess". */
    private fun collapseRuns(key: String): String = buildString {
        for (c in key) if (length < 2 || c != this[length - 1] || c != this[length - 2]) append(c)
    }

    companion object {
        /** The candidate pool the policy chooses from; the strip itself is shorter. */
        private const val DECODE_POOL = 12

        /** A taught fix outranks every decoder reading. */
        private const val TAUGHT_FIX_SCORE = 30f

        /** Base score of a word the user owns, before its slot in the user's ordering. */
        private const val USER_BASE = 9f

        /** Shorthand people mean to type; never "fixed" into a word-list word. */
        private val SLANG = setOf(
            "lol", "lmao", "omg", "omw", "idk", "btw", "brb", "ppl", "tbh", "imo", "imho", "ngl", "smh", "thx", "pls", "plz",
            "ur", "gonna", "wanna", "gotta", "ok", "okay", "ya", "yea", "yep", "nope", "haha", "hahaha", "hmm", "ugh", "wtf",
            "ty", "np", "rn", "irl", "fyi", "asap", "af", "bc", "cuz", "tho", "dm", "dms", "lmk", "hbu", "wyd", "jk", "nvm",
            "tmrw", "bday", "xd", "fr", "istg", "ikr", "gm", "gn", "ttyl", "yk", "ig", "w", "l",
            "lowkey", "idc", "rizz", "sus", "bruh", "tryna", "delulu", "bestie",
        )

        /**
         * A [KeyboardEngine] over "surface to count" [entries], with the sentence-start marker
         * added so contexts can begin a sentence. Duplicate surfaces keep their first count.
         */
        fun fromWordList(entries: List<Pair<String, Long>>, keys: List<KeyCenter>): KeyboardEngine {
            val distinct = LinkedHashMap<String, Long>()
            for ((word, count) in entries) {
                if (word.isNotBlank() && word !in distinct) distinct[word] = count.coerceAtLeast(1L)
            }
            val surfaces = ArrayList<String>(distinct.size + 1)
            val counts = ArrayList<Long>(distinct.size + 1)
            surfaces += Vocabulary.SENTENCE_START
            counts += 1L
            for ((word, count) in distinct) {
                surfaces += word
                counts += count
            }
            return KeyboardEngine(UnigramModel(Vocabulary(surfaces), counts.toLongArray()), keys)
        }
    }
}
