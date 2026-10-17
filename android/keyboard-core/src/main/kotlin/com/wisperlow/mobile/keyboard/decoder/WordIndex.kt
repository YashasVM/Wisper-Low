package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.LanguageModel
import java.util.TreeMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The vocabulary as a letter tree over word keys (lowercase, no apostrophes), for searches
 * that extend a word one letter at a time. Nodes are ints; a missing child is [NONE].
 */
interface WordIndex {
    val root: Int

    /** The child of [node] along [c], or [NONE]. */
    fun child(node: Int, c: Char): Int

    /** Letters leading out of [node], ascending. */
    fun children(node: Int): CharArray

    /** Ids of the words whose key ends exactly at [node]. */
    fun words(node: Int): IntArray

    /** The highest unigram ln-probability of any word at or below [node], for pruning. */
    fun bestBelow(node: Int): Float

    /** ln of the summed unigram probability of the words at or below [node]. */
    fun massBelow(node: Int): Float

    /** Up to [max] word ids at or below [node], most probable first. */
    fun topWordsBelow(node: Int, max: Int): IntArray

    companion object {
        const val NONE = -1
    }
}

/** A [WordIndex] on hash maps: plainly correct, not fast. The reference other indexes are tested against. */
class SimpleWordIndex(private val model: LanguageModel) : WordIndex {
    private class Node {
        val children = TreeMap<Char, Int>()
        val words = ArrayList<Int>(0)
        var best = Float.NEGATIVE_INFINITY
        var mass = 0.0
    }

    private val nodes = arrayListOf(Node())

    init {
        val vocabulary = model.vocabulary
        for (id in 0 until vocabulary.size) {
            val key = vocabulary.key(id)
            if (key.isEmpty() || key == "<s>") continue
            var node = 0
            for (c in key) node = nodes[node].children.getOrPut(c) { nodes.add(Node()); nodes.lastIndex }
            nodes[node].words += id
        }
        fill(0)
    }

    private fun fill(n: Int) {
        val node = nodes[n]
        for (id in node.words) {
            val p = model.unigramLogProb(id)
            node.best = max(node.best, p)
            node.mass += exp(p.toDouble())
        }
        for (child in node.children.values) {
            fill(child)
            node.best = max(node.best, nodes[child].best)
            node.mass += nodes[child].mass
        }
    }

    override val root: Int get() = 0

    override fun child(node: Int, c: Char): Int = nodes[node].children[c] ?: WordIndex.NONE

    override fun children(node: Int): CharArray = nodes[node].children.keys.toCharArray()

    override fun words(node: Int): IntArray = nodes[node].words.toIntArray()

    override fun bestBelow(node: Int): Float = nodes[node].best

    override fun massBelow(node: Int): Float = if (nodes[node].mass > 0) ln(nodes[node].mass).toFloat() else Float.NEGATIVE_INFINITY

    override fun topWordsBelow(node: Int, max: Int): IntArray {
        val all = ArrayList<Int>()
        fun collect(n: Int) {
            all += nodes[n].words
            nodes[n].children.values.forEach(::collect)
        }
        collect(node)
        return all.sortedByDescending { model.unigramLogProb(it) }.take(max).toIntArray()
    }
}
