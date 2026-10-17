package com.wisperlow.mobile.keyboard.lm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.exp

class NgramEstimatorTest {
    /** Counts spread across 1..9, so every discount bucket of every order has evidence. */
    private val corpus = List(4) { "the cat sat on the mat" } +
        listOf("the cat ran home") +
        List(4) { "the dog ran home" } +
        listOf("cat and dog ran home")

    private val counts = countsOf(corpus)
    private val tables = NgramEstimator.estimate(counts)

    private fun wordId(word: String): Int {
        val id = tables.vocabulary.indexOf(word)
        require(id >= 0) { "corpus has no '$word'" }
        return id
    }

    @Test
    fun `backoff rules sum to one over the whole vocabulary`() {
        val contexts = listOf(
            intArrayOf(wordId(Vocabulary.SENTENCE_START)), // seen bigram context
            intArrayOf(wordId("the")), // seen bigram context with prunable successors
            intArrayOf(wordId("mat")), // a word that never precedes another
            intArrayOf(wordId("the"), wordId("cat")), // seen trigram context
            intArrayOf(wordId("the"), wordId("mat")), // bigram context, never a trigram one
            intArrayOf(wordId("mat"), wordId("the")), // context never observed at all
        )
        for (context in contexts) {
            val words = context.map { tables.vocabulary[it] }
            assertEquals("after $words", 1.0, sumOverVocabulary(tables, context), 1e-3)
        }
    }

    @Test
    fun `observed trigram beats its unobserved alternatives`() {
        val context = intArrayOf(wordId("the"), wordId("cat"))
        val observed = probability(tables, wordId("sat"), context)
        for (word in listOf("dog", "mat", "home", "and", Vocabulary.SENTENCE_START)) {
            assertTrue("$word after 'the cat' ($observed)", observed > 2 * probability(tables, wordId(word), context))
        }
    }

    @Test
    fun `frequent bigram outranks a rare one in the same context`() {
        val context = intArrayOf(wordId("cat"))
        val frequent = probability(tables, wordId("sat"), context) // bigram count 4
        val rare = probability(tables, wordId("ran"), context) // bigram count 1
        assertTrue("sat=$frequent vs ran=$rare", frequent > 5 * rare)
    }

    @Test
    fun `sentence start stays negligible as a prediction`() {
        val start = wordId(Vocabulary.SENTENCE_START)
        assertTrue(exp(tables.unigramLogProb[start].toDouble()) < 1e-6)
        assertTrue(probability(tables, start, intArrayOf(wordId("the"))) < 1e-6)
        assertTrue(probability(tables, start, intArrayOf(wordId("the"), wordId("cat"))) < 1e-6)
    }

    @Test
    fun `degenerate count-of-counts stay finite and normalized`() {
        // Every count is 1: there are no 2- or 3-counts to fit D2 and D3 from.
        val singletons = NgramEstimator.estimate(countsOf(listOf("a b c d")))
        assertFinite(singletons)
        val start = idOf(singletons, Vocabulary.SENTENCE_START)
        assertEquals(1.0, sumOverVocabulary(singletons, intArrayOf(start, idOf(singletons, "a"))), 1e-3)

        // Every count is large: the 1- and 2-count buckets the fit starts from are empty.
        val heavy = NgramEstimator.estimate(countsOf(List(5) { "x y z" }))
        assertFinite(heavy)
        assertEquals(1.0, sumOverVocabulary(heavy, intArrayOf(idOf(heavy, "x"))), 1e-3)
        assertEquals(1.0, sumOverVocabulary(heavy, intArrayOf(idOf(heavy, "<s>"), idOf(heavy, "x"))), 1e-3)
    }

    @Test
    fun `min counts prune rows before estimation`() {
        val pruned = NgramEstimator.estimate(counts, EstimatorConfig(minBigramCount = 2))
        assertEquals(9, pruned.bigramCount)
        assertEquals(9, pruned.trigramCount) // trigrams whose context bigram was pruned go too
        assertEquals(1.0, sumOverVocabulary(pruned, intArrayOf(pruned.vocabulary.indexOf("cat"))), 1e-3)

        val floored = NgramEstimator.estimate(counts, EstimatorConfig(minUnigramCount = 5))
        val rare = floored.vocabulary.indexOf("and") // unigram count 1
        val common = floored.vocabulary.indexOf("cat") // unigram count 6
        assertTrue(exp(floored.unigramLogProb[rare].toDouble()) < 1e-6)
        assertTrue(exp(floored.unigramLogProb[common].toDouble()) > 1e-3)
    }

    @Test
    fun `read round trips tsv files`() {
        withCounterOutput { dir ->
            File(dir, "unigrams.tsv").writeText("the\t5\ncat\t3\n<s>\t2\nmat\t1\n")
            // Deliberately unsorted, and 'dog' never got a unigram row.
            File(dir, "bigrams.tsv").writeText("cat\tmat\t7\n<s>\tthe\t4\nthe\tcat\t9\ndog\tthe\t2\nmat\tthe\t1\n")
            File(dir, "trigrams.tsv").writeText("<s>\tthe\tcat\t4\nthe\tcat\tmat\t6\ncat\tmat\tthe\t1\n")
            val read = NgramCounts.read(dir, maxVocabulary = 10)

            assertEquals(listOf("the", "cat", "<s>", "mat"), read.vocabulary)
            assertArrayEquals(longArrayOf(5, 3, 2, 1), read.unigramCounts)
            assertArrayEquals(intArrayOf(0, 1, 2, 3), read.bigramFirst)
            assertArrayEquals(intArrayOf(1, 3, 0, 0), read.bigramSecond)
            assertArrayEquals(intArrayOf(9, 7, 4, 1), read.bigramCounts)
            assertArrayEquals(intArrayOf(0, 1, 2), read.trigramFirst)
            assertArrayEquals(intArrayOf(1, 3, 0), read.trigramSecond)
            assertArrayEquals(intArrayOf(3, 0, 1), read.trigramThird)
            assertArrayEquals(intArrayOf(6, 1, 4), read.trigramCounts)
        }
    }

    @Test
    fun `read keeps a sentence start the counter never counted`() {
        withCounterOutput { dir ->
            File(dir, "unigrams.tsv").writeText("the\t4\n")
            File(dir, "bigrams.tsv").writeText("<s>\tthe\t4\n")
            val read = NgramCounts.read(dir, maxVocabulary = 10) // no trigrams file at all

            assertEquals(listOf("the", Vocabulary.SENTENCE_START), read.vocabulary)
            assertArrayEquals(intArrayOf(1), read.bigramFirst)
            assertArrayEquals(intArrayOf(0), read.bigramSecond)
            assertArrayEquals(intArrayOf(4), read.bigramCounts)
            assertTrue(read.trigramFirst.isEmpty())
        }
    }

    @Test
    fun `read caps the vocabulary but never cuts the sentence start`() {
        withCounterOutput { dir ->
            File(dir, "unigrams.tsv").writeText("the\t5\ncat\t3\n<s>\t2\n")
            File(dir, "bigrams.tsv").writeText("<s>\tthe\t5\nthe\tcat\t4\n")
            File(dir, "trigrams.tsv").writeText("<s>\tthe\tcat\t4\n")
            val read = NgramCounts.read(dir, maxVocabulary = 2)

            assertEquals(listOf("the", Vocabulary.SENTENCE_START), read.vocabulary)
            assertArrayEquals(intArrayOf(1), read.bigramFirst) // (the, cat) dropped with 'cat'
            assertArrayEquals(intArrayOf(0), read.bigramSecond)
            assertArrayEquals(intArrayOf(5), read.bigramCounts)
            assertTrue(read.trigramFirst.isEmpty())
        }
    }

    private fun withCounterOutput(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("ngram-counts").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}

/**
 * Applies the backoff rules documented in NgramTables.kt as plain probabilities: the listed
 * n-gram when present, else the context's backoff weight times the shorter context's
 * probability, with a missing backoff weight treated as 1.
 */
private fun probability(model: NgramTables, word: Int, context: IntArray): Double {
    if (context.isEmpty()) return exp(model.unigramLogProb[word].toDouble())
    if (context.size == 1) {
        val row = bigramRow(model, context[0], word)
        return if (row >= 0) {
            exp(model.bigramLogProb[row].toDouble())
        } else {
            exp(model.unigramBackoff[context[0]].toDouble()) * exp(model.unigramLogProb[word].toDouble())
        }
    }
    val row = trigramRow(model, context[context.size - 2], context[context.size - 1], word)
    if (row >= 0) return exp(model.trigramLogProb[row].toDouble())
    val backoffRow = bigramRow(model, context[context.size - 2], context[context.size - 1])
    val backoff = if (backoffRow >= 0) exp(model.bigramBackoff[backoffRow].toDouble()) else 1.0
    return backoff * probability(model, word, context.copyOfRange(1, context.size))
}

private fun sumOverVocabulary(model: NgramTables, context: IntArray): Double =
    (0 until model.vocabulary.size).sumOf { probability(model, it, context) }

private fun bigramRow(model: NgramTables, first: Int, word: Int): Int =
    model.bigramFirst.indices
        .firstOrNull { model.bigramFirst[it] == first && model.bigramSecond[it] == word } ?: -1

private fun trigramRow(model: NgramTables, first: Int, second: Int, word: Int): Int =
    model.trigramFirst.indices
        .firstOrNull {
            model.trigramFirst[it] == first && model.trigramSecond[it] == second && model.trigramThird[it] == word
        } ?: -1

private fun assertFinite(model: NgramTables) {
    val every = model.unigramLogProb + model.unigramBackoff +
        model.bigramLogProb + model.bigramBackoff + model.trigramLogProb
    for (value in every) assertTrue("non-finite table value $value", value.isFinite())
}

private fun idOf(model: NgramTables, word: String): Int = model.vocabulary.indexOf(word)

/** Builds counts the way the counter would: <s> prefixes every sentence, ids rank by count. */
private fun countsOf(sentences: List<String>): NgramCounts {
    val unigrams = HashMap<String, Long>()
    val bigrams = HashMap<List<String>, Long>()
    val trigrams = HashMap<List<String>, Long>()
    for (sentence in sentences) {
        val tokens = listOf(Vocabulary.SENTENCE_START) + sentence.split(" ")
        for (token in tokens) unigrams.merge(token, 1L) { a, b -> a + b }
        for (i in 0 until tokens.size - 1) {
            bigrams.merge(tokens.subList(i, i + 2).toList(), 1L) { a, b -> a + b }
        }
        for (i in 0 until tokens.size - 2) {
            trigrams.merge(tokens.subList(i, i + 3).toList(), 1L) { a, b -> a + b }
        }
    }
    val vocabulary = unigrams.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .map { it.key }
    val ids = vocabulary.withIndex().associate { (id, word) -> word to id }
    val bigramsByRow = sortedIds(bigrams.map { (words, count) -> words.map(ids::getValue) + count.toInt() })
    val trigramsByRow = sortedIds(trigrams.map { (words, count) -> words.map(ids::getValue) + count.toInt() })
    return NgramCounts(
        vocabulary = vocabulary,
        unigramCounts = LongArray(vocabulary.size) { unigrams.getValue(vocabulary[it]) },
        bigramFirst = IntArray(bigramsByRow.size) { bigramsByRow[it][0] },
        bigramSecond = IntArray(bigramsByRow.size) { bigramsByRow[it][1] },
        bigramCounts = IntArray(bigramsByRow.size) { bigramsByRow[it][2] },
        trigramFirst = IntArray(trigramsByRow.size) { trigramsByRow[it][0] },
        trigramSecond = IntArray(trigramsByRow.size) { trigramsByRow[it][1] },
        trigramThird = IntArray(trigramsByRow.size) { trigramsByRow[it][2] },
        trigramCounts = IntArray(trigramsByRow.size) { trigramsByRow[it][3] },
    )
}

/** Row lists are word ids plus a trailing count; order by the ids, ties cannot happen. */
private fun sortedIds(rows: List<List<Int>>): List<List<Int>> = rows.sortedWith { a, b ->
    var cmp = 0
    for (i in 0 until a.size - 1) {
        cmp = a[i].compareTo(b[i])
        if (cmp != 0) break
    }
    cmp
}
