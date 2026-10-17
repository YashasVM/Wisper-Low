package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import kotlin.math.exp
import kotlin.math.ln

/**
 * [NextCharModel] over a [WordIndex]: the trie carries the unigram view, the language model
 * carries the contextual one, and [contextWeight] decides how much the previous words move a key
 * target. After "he" the child 'y' covers "hey" while 't' covers "heat", so the invisible target
 * on 'y' grows once "hey" looks likely in context.
 *
 * One call walks the trie once and scans at most [contextWords] predictions, so it stays well
 * under 0.3 ms on a 100k word index. Results are memoised because a tap asks once per prefix and
 * a backspace revisits the prefixes behind it.
 */
class TrieNextCharModel(
    private val index: WordIndex,
    private val model: LanguageModel,
    private val contextWeight: Float = 0.6f,
    private val contextWords: Int = 64,
) : NextCharModel {
    private val cache: MutableMap<Key, Map<Char, Float>> =
        object : LinkedHashMap<Key, Map<Char, Float>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Map<Char, Float>>) = size > CACHE_SIZE
        }

    override fun logProbs(prefix: String, context: IntArray): Map<Char, Float> {
        // The trie holds keys, so the prefix has to be normalised the way a word key is.
        val cacheKey = Key(Vocabulary.keyOf(prefix), context)
        cache[cacheKey]?.let { return it }

        val node = walk(cacheKey.prefix)
        val children = if (node == WordIndex.NONE) CharArray(0) else index.children(node)
        if (children.isEmpty()) return emptyMap<Char, Float>().also { cache[cacheKey] = it }

        val unigram = FloatArray(children.size) { i ->
            exp(index.massBelow(index.child(node, children[i])) - index.massBelow(node)).coerceAtLeast(0f)
        }
        val contextShare = contextualShares(cacheKey.prefix, children, context)
        val blended = FloatArray(children.size) { i ->
            if (contextShare == null) unigram[i]
            else contextWeight * contextShare[i] + (1f - contextWeight) * unigram[i]
        }

        val total = blended.sum()
        val result = if (total <= 0f) emptyMap<Char, Float>()
        else LinkedHashMap<Char, Float>(children.size).apply {
            for (i in children.indices) put(children[i], ln(blended[i] / total))
        }
        cache[cacheKey] = result
        return result
    }

    /** [node] for [key], or [WordIndex.NONE] when the prefix leaves the trie. */
    private fun walk(key: String): Int {
        var node = index.root
        for (c in key) {
            node = index.child(node, c)
            if (node == WordIndex.NONE) return WordIndex.NONE
        }
        return node
    }

    /**
     * P(child letter | context) as a share of the contextual mass of the predictions continuing
     * [key], or null when nothing predicted continues it and the unigram view stands alone.
     */
    private fun contextualShares(key: String, children: CharArray, context: IntArray): FloatArray? {
        val predictions = model.predict(context, contextWords)
        if (predictions.isEmpty()) return null
        val shares = FloatArray(children.size)
        // Only letters that are really in the trie may take mass; every other letter would enlarge
        // a key target the keyboard doesn't have.
        val slot = HashMap<Char, Int>(children.size)
        for (i in children.indices) slot[children[i]] = i

        var total = 0.0
        for (scored in predictions) {
            val wordKey = model.vocabulary.key(scored.id)
            if (!wordKey.startsWith(key)) continue
            val slotOfLetter = slot[wordKey.getOrNull(key.length)] ?: continue
            val mass = exp(scored.logProb.toDouble())
            shares[slotOfLetter] += mass.toFloat()
            total += mass
        }
        if (total <= 0.0) return null
        for (i in shares.indices) shares[i] = (shares[i] / total).toFloat()
        return shares
    }

    /** Cache identity: a normalised prefix plus the preceding word ids. */
    private class Key(prefix: String, context: IntArray) {
        val prefix = prefix
        private val context = context.copyOf()
        private val hash = 31 * prefix.hashCode() + this.context.contentHashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean =
            other is Key && hash == other.hash && prefix == other.prefix && context.contentEquals(other.context)
    }

    private companion object {
        /** A tap asks once per prefix; a backspace revisits the last few. */
        const val CACHE_SIZE = 32
    }
}