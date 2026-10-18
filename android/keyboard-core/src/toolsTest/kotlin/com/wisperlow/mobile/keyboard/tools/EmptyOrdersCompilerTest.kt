package com.wisperlow.mobile.keyboard.tools

import com.wisperlow.mobile.keyboard.lm.NgramModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmptyOrdersCompilerTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `unigram-only counts compile and reload`() = compile(emptyBigram = true, prune = false)

    @Test
    fun `bigram-only counts compile and reload`() = compile(emptyBigram = false, prune = false)

    @Test
    fun `pruned higher orders compile and reload`() = compile(emptyBigram = false, prune = true)

    private fun compile(emptyBigram: Boolean, prune: Boolean) {
        val counts = temporary.newFolder("counts")
        counts.resolve("unigrams.tsv").writeText((0 until 32).joinToString("") { "word$it\t100\n" })
        counts.resolve("bigrams.tsv").writeText(if (emptyBigram) "" else "word0\tword1\t3\n")
        counts.resolve("trigrams.tsv").writeText(if (prune) "word0\tword1\tword2\t2\n" else "")
        val output = temporary.root.resolve("model.wlm")
        val args = mutableListOf("--counts", counts.path, "--out", output.path, "--vocab", "33")
        if (prune) args.addAll(listOf("--min2", "4", "--min3", "4"))
        main(args.toTypedArray())
        val header = ByteBuffer.wrap(output.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(if (emptyBigram || prune) 0 else 1, header.getInt(9))
        assertEquals(0, header.getInt(13))
        val model = NgramModel.read(output)
        assertEquals(33, model.vocabulary.size)
        val id = model.vocabulary.id("word1")
        assertTrue(model.logProb(id, intArrayOf()).isFinite())
        assertTrue(model.logProb(id, intArrayOf(model.vocabulary.id("word0"))).isFinite())
    }
}
