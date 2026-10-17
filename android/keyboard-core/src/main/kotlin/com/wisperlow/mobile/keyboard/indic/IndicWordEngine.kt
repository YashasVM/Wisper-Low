package com.wisperlow.mobile.keyboard.indic

import com.wisperlow.mobile.keyboard.Lexicon
import com.wisperlow.mobile.keyboard.Suggestions
import com.wisperlow.mobile.keyboard.Touch
import com.wisperlow.mobile.keyboard.WordEngine

/**
 * Hindi and Kannada in Latin keystrokes. The user types on the ordinary QWERTY board; [index]
 * maps attested romanizations to native words, and the raw keystrokes stay a first-class
 * candidate so a conversion is never a trap.
 *
 * Nothing here invents native text: the index only knows pairs seen in real data, so an unknown
 * romanization is kept verbatim. A word is converted on space only through
 * [Suggestions.autoCorrect], and only when the typed romanization is an *exact* attested key;
 * completions and typo fixes are offered for a tap, never committed. [Lexicon] holds the user's
 * learned native words, next-word pairs and hand-made roman-to-native fixes, so persistence and
 * unlearning are exactly the English engine's job.
 */
class IndicWordEngine(private val index: RomanizationIndex) : WordEngine {

    /** The user's own state; empty word list, since native words come from [index]. */
    private val user = Lexicon(emptyList())

    override fun suggest(typed: String, previous: String?, max: Int, touches: List<Touch?>?): Suggestions {
        if (max <= 0) return Suggestions.None
        if (typed.isEmpty()) return Suggestions.None

        // The private lexicon owns taught fixes and previously committed words. A fix made by
        // hand beats anything the index has, and trusting the typed word clears its own fix
        // inside [Lexicon], so this single call already respects both.
        val learned = user.suggest(typed, previous, max, touches)
        val scored = index.suggestScored(typed, max + learned.words.size + 1)
        val exact = scored.firstOrNull()?.takeIf { it.kind == RomanizationMatch.EXACT }?.native
        val trustedRaw = user.isKnown(typed)

        val words = LinkedHashSet<String>(max + 2)
        // What was typed is always reachable, and always first.
        words += typed
        learned.autoCorrect?.let { words += it }
        scored.forEach { words += it.native }
        words += learned.words

        // Only an exact attested romanization, and only until the user says the raw spelling is
        // theirs. Completions and typo fixes stay manual.
        val auto = when {
            learned.autoCorrect != null -> learned.autoCorrect
            exact != null && !trustedRaw -> exact
            else -> null
        }?.takeIf { it != typed }
        return Suggestions(words.take(max), auto)
    }

    /** Only what the user has learned; native words are not guessed at from the script alone. */
    override fun predict(previous: String?, max: Int): List<String> = user.predict(previous, max)

    override fun isKnown(word: String): Boolean {
        if (user.isKnown(word)) return true
        // A Latin word counts as known when it is exactly attested. Native words are left to
        // what the user learned, so no autocorrect promise is made about the script itself.
        return index.suggestScored(word, 1).firstOrNull()?.kind == RomanizationMatch.EXACT
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
}
