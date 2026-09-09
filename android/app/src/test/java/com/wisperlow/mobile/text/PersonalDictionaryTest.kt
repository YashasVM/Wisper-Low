package com.wisperlow.mobile.text

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalDictionaryTest {

    @Test
    fun exactMatchWithTrailingPunctuation() {
        val dict = mapOf("yashasvm" to "YashasVM")
        assertEquals("hi YashasVM,", PersonalDictionary.apply("hi yashasvm,", dict))
    }

    @Test
    fun surroundingPunctuationPreserved() {
        val dict = mapOf("yashasvm" to "YashasVM")
        assertEquals("(YashasVM)", PersonalDictionary.apply("(yashasvm)", dict))
        assertEquals("\"YashasVM\".", PersonalDictionary.apply("\"yashasvm\".", dict))
    }

    @Test
    fun caseInsensitiveKeyMatch() {
        val dict = mapOf("kotlinx" to "KotlinX")
        assertEquals("i love KotlinX!", PersonalDictionary.apply("i love KOTLINX!", dict))
        assertEquals("KotlinX", PersonalDictionary.apply("KoTlInX", dict))
    }

    @Test
    fun valueInsertedVerbatimRegardlessOfTokenCase() {
        val dict = mapOf("ai" to "A.I.")
        assertEquals("A.I.! wow", PersonalDictionary.apply("AI! wow", dict))
        assertEquals("Use A.I.", PersonalDictionary.apply(TextCleaner.clean("Use ai."), dict))
    }

    @Test
    fun noSubstringMatches() {
        val dict = mapOf("cat" to "Cat")
        assertEquals("the category stayed", PersonalDictionary.apply("the category stayed", dict))
        assertEquals("Cat and category", PersonalDictionary.apply("cat and category", dict))
    }

    @Test
    fun multipleTokensReplacedInOnePass() {
        val dict = mapOf("yashasvm" to "YashasVM", "wisperlow" to "WisperLow")
        assertEquals("YashasVM built WisperLow.", PersonalDictionary.apply("yashasvm built wisperlow.", dict))
    }

    @Test
    fun longestPhraseWinsAndWhitespaceIsPreservedAroundIt() {
        val dict = mapOf("new york" to "NY", "new york city" to "New York City")
        assertEquals("Visit New York City, then NY.", PersonalDictionary.apply("Visit new york city, then new   york.", dict))
    }

    @Test
    fun phrasesCanCrossNewlines() {
        val dict = mapOf("machine learning" to "ML")
        assertEquals("Use ML\nnext", PersonalDictionary.apply("Use machine\nlearning\nnext", dict))
    }

    @Test
    fun keysAreLiteralAndUnicodeBoundariesAreRespected() {
        val dict = mapOf("c++" to "C plus plus", "猫" to "Cat", "[api]" to "API")
        assertEquals("C plus plus [api] Cat", PersonalDictionary.apply("C++ [api] 猫", dict))
        assertEquals("scat category 猫猫", PersonalDictionary.apply("scat category 猫猫", dict))
    }

    @Test
    fun replacementDoesNotCascadeIntoAnotherDictionaryEntry() {
        val dict = mapOf("alpha" to "beta", "beta" to "gamma")
        assertEquals("beta", PersonalDictionary.apply("alpha", dict))
    }

    @Test
    fun unmatchedTokensUntouched() {
        val dict = mapOf("teh" to "the")
        assertEquals("nothing to see here", PersonalDictionary.apply("nothing to see here", dict))
    }

    @Test
    fun emptyDictionaryOrEmptyTextIsNoOp() {
        assertEquals("leave me alone", PersonalDictionary.apply("leave me alone", emptyMap()))
        assertEquals("", PersonalDictionary.apply("", mapOf("a" to "b")))
    }
}
