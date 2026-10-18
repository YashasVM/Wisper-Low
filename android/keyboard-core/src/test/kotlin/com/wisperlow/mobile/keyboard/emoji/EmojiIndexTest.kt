package com.wisperlow.mobile.keyboard.emoji

import java.io.File
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiIndexTest {
    private val assets = File(System.getProperty("wisperlow.assets") ?: "../app/src/main/assets")

    private val english by lazy { emojiIndex("en.tsv") }
    private val hindi by lazy { emojiIndex("hi.tsv") }

    private fun emojiIndex(name: String) =
        EmojiIndex(File(File(assets, "emoji"), name).readLines().asSequence())

    private val sample = EmojiIndex(
        sequenceOf(
            "# gift\t🎁", // comments, blanks and tab-less lines are never keywords
            "",
            "heart\t❤ 💖",
            "love\t❤ 💖",
            "laugh\t🤣 😂",
            "party\t🎉",
            "pizza\t🍕",
            "pizza\t🍕 🍕", // a repeated keyword or emoji is kept once
            "noshape",
        ),
    )

    @Test
    fun `parses keyword lines`() {
        assertEquals(listOf("🍕"), sample.forWord("pizza"))
        assertEquals(listOf("❤", "💖"), sample.forWord("heart"))
        assertTrue(sample.forWord("gift").isEmpty())
        assertTrue(sample.forWord("noshape").isEmpty())
    }

    @Test
    fun `strips plural and -ing endings to reach a keyword`() {
        assertEquals(listOf("❤", "💖"), sample.forWord("hearts"))
        assertEquals(listOf("🤣", "😂"), sample.forWord("laughing"))
    }

    @Test
    fun `prefix-matches a word still being typed`() {
        assertEquals(listOf("🍕"), sample.forWord("pizz"))
        assertTrue(sample.forWord("par").isEmpty()) // under four letters, no prefix match
    }

    @Test
    fun `ignores case and honours the limit`() {
        assertEquals(listOf("🍕"), sample.forWord("PIZZA"))
        assertEquals(listOf("🤣", "😂"), sample.forWord("laugh", 2))
    }

    @Test
    fun `unknown words find nothing`() {
        assertTrue(sample.forWord("xyzzy").isEmpty())
        assertTrue(sample.forWord("").isEmpty())
        assertTrue(english.forWord("xyzzy").isEmpty())
    }

    @Test
    fun `English keywords find food, feelings and laughing faces`() {
        assertEquals("🍕", english.forWord("pizza").first())
        assertTrue(english.forWord("love").contains("❤"))
        assertTrue(english.forWord("laugh").contains("😹"))
        assertTrue(english.forWord("laughing").contains("😹"))
        assertTrue(english.forWord("laugh", 8).contains("🤣"))
    }

    @Test
    fun `Hindi keywords from the generated asset find their emoji`() {
        assertEquals("🍕", hindi.forWord("पिज़्ज़ा").first())
        assertTrue(hindi.forWord("दिल").contains("❤"))
        assertTrue(hindi.forWord("प्यार").contains("💕"))
    }

    @Test
    fun `Hindi lookup also works from decomposed input`() {
        val decomposed = Normalizer.normalize("पिज़्ज़ा", Normalizer.Form.NFD)
        assertEquals("🍕", hindi.forWord(decomposed).first())
    }
}
