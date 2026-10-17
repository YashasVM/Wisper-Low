package com.wisperlow.mobile.keyboard.decoder

import com.wisperlow.mobile.keyboard.lm.LanguageModel
import com.wisperlow.mobile.keyboard.lm.UnigramModel
import com.wisperlow.mobile.keyboard.lm.Vocabulary
import com.wisperlow.mobile.keyboard.spatial.SpatialModel
import java.util.Arrays

/**
 * Every tuning constant of [BeamTypingDecoder], in log-score units (higher is better).
 * Penalties are added to a candidate's score, so they are negative. The defaults follow
 * docs/keyboard/research/decoding-science.md section 3 where it gives a number.
 */
data class DecoderParams(
    /** How much the language model matters relative to touch and edit evidence ("lambda"). */
    val lmWeight: Float = 1.0f,
    /** States kept per input position. */
    val beamWidth: Int = 128,
    /** Structural edits (omissions, insertions, transpositions) a word may assume. */
    val maxEdits: Int = 2,
    /** Shorter words tolerate fewer: four letters or fewer get [shortWordMaxEdits]. */
    val shortWordMaxEdits: Int = 1,
    val shortWordLength: Int = 4,
    /** The word has a letter the user did not type. */
    val omissionPenalty: Float = -3.5f,
    /** An omission that merely leaves one copy of a doubled letter ("helo" for hello). */
    val doubledOmissionPenalty: Float = -1.0f,
    /** An extra typed key. */
    val insertionPenalty: Float = -3.5f,
    /** An insertion that doubles the previous typed letter ("untill" for until). */
    val doubledInsertionPenalty: Float = -1.0f,
    /** Two adjacent typed letters came out swapped. */
    val transpositionPenalty: Float = -2.5f,
    /** Matching a typed key without a known touch position to a different letter. */
    val substitutionNoTouch: Float = -6.0f,
    /** People rarely miss the first letter, so changing it costs extra. */
    val firstLetterPenalty: Float = -2.0f,
    /** A word longer than everything typed. */
    val completionPenalty: Float = -1.5f,
    /** Per letter beyond the typed input, on top of [completionPenalty]. */
    val perExtraLetter: Float = -0.1f,
    val maxCompletions: Int = 4,
    /**
     * A transposition only explores a letter matched against a typed key while its
     * one-letter cost stays above this; keeps the two-edge expansion small.
     */
    val transpositionCutoff: Float = -6.0f,
)

/**
 * The noisy-channel word decoder: `score(w) = spatial evidence + lmWeight * log P(w | context)
 * + edit penalties`. It walks [index]'s letter tree with a beam over (tree node, input position,
 * edits), matching each typed key to a tree letter by touch likelihood, and offers terminal
 * words, completions and the literal reading of the input.
 */
class BeamTypingDecoder(
    private val index: WordIndex,
    private val model: LanguageModel,
    private val spatial: SpatialModel,
    private val params: DecoderParams = DecoderParams(),
) : TypingDecoder {

    private val vocabulary: Vocabulary get() = model.vocabulary

    override fun decode(input: List<TypedKey>, context: IntArray, max: Int): List<Candidate> {
        val typed = input.joinToString("") { it.char.toString() }
        val literal = literalCandidate(typed, context)
        // Apostrophes have no key: the search only sees the keys the user pressed.
        val keys = input.filter { !isApostrophe(it.char) }
        val found = search(keys, context)
        val best = LinkedHashMap<String, Candidate>(found.size + 1)
        // The literal goes in first so it survives a tie with an identical surface.
        best[literal.word] = literal
        for (candidate in found) {
            val old = best[candidate.word]
            if (old == null || candidate.score > old.score) best[candidate.word] = candidate
        }
        val ranked = ArrayList(best.values.sortedByDescending { it.score }.take(max))
        // The typed letters must stay reachable even when rare: give them the last slot.
        if (ranked.isNotEmpty() && ranked.none { it.word == literal.word }) ranked[ranked.size - 1] = literal
        return ranked
    }

    /** The typed letters themselves, always offered so the user's own spelling is never lost. */
    private fun literalCandidate(typed: String, context: IntArray): Candidate {
        val id = vocabulary.id(typed)
        val lmScore = if (id != Vocabulary.UNKNOWN) model.logProb(id, context) else UnigramModel.UNKNOWN_LOG_PROB
        return Candidate(
            wordId = id,
            word = typed,
            score = params.lmWeight * lmScore,
            spatialScore = 0f,
            lmScore = lmScore,
            kind = CandidateKind.LITERAL,
            edits = 0,
        )
    }

    /** The beam search over the letter tree; every reading of [keys] it finds. */
    private fun search(keys: List<TypedKey>, context: IntArray): List<Candidate> {
        val length = keys.size
        val typedChar = CharArray(length) { keys[it].char.lowercaseChar() }
        val maxEdits = if (length <= params.shortWordLength) params.shortWordMaxEdits else params.maxEdits
        val deltas = deltas(keys, typedChar)
        val frontiers = Array(length + 1) { State(64) }
        val slots = Array(length + 1) { Slots() }
        val scratch = State(0)
        val found = ArrayList<Candidate>(8)
        frontiers[0].add(index.root, 0, 0f, 0f, changed = false, prev = '\u0000', depth = 0)
        for (position in 0..length) {
            val cur = frontiers[position]
            closeOmissions(cur, slots[position], position, maxEdits)
            prune(cur, scratch, params.beamWidth)
            for (t in 0 until cur.size) {
                if (position == length) {
                    offerTerminals(cur, t, context, found)
                    offerCompletions(cur, t, context, found)
                    continue
                }
                expand(cur, t, position, length, typedChar, deltas, maxEdits, frontiers, slots)
            }
        }
        return found
    }

    /** A state may skip a letter of the word (an omission) without consuming input. */
    private fun closeOmissions(cur: State, slots: Slots, position: Int, maxEdits: Int) {
        val initial = cur.size
        for (t in 0 until initial) {
            val edits = cur.edits[t]
            if (edits >= maxEdits) continue
            val node = cur.node[t]
            for (c in index.children(node)) {
                val child = index.child(node, c)
                val penalty = if (c == cur.prev[t]) params.doubledOmissionPenalty else params.omissionPenalty
                // Skipping the word's first letter is its own rare kind of slip.
                val extra = if (position == 0) params.firstLetterPenalty else 0f
                add(cur, slots, child, edits + 1, cur.score[t] + penalty + extra, cur.spatial[t], cur.changed[t], c, cur.depth[t] + 1)
            }
        }
    }

    /** One position's worth of transitions: match, extra tap, and swapped pair. */
    private fun expand(
        cur: State,
        t: Int,
        position: Int,
        length: Int,
        typedChar: CharArray,
        deltas: FloatArray,
        maxEdits: Int,
        frontiers: Array<State>,
        slots: Array<Slots>,
    ) {
        val node = cur.node[t]
        val edits = cur.edits[t]
        val score = cur.score[t]
        val spatialScore = cur.spatial[t]
        val previous = cur.prev[t]
        val typed = typedChar[position]
        val next = frontiers[position + 1]
        val nextSlots = slots[position + 1]
        for (c in index.children(node)) {
            val delta = delta(deltas, position, c, typed)
            val changed = cur.changed[t] || c != typed
            val first = if (position == 0 && c != typed) params.firstLetterPenalty else 0f
            add(next, nextSlots, index.child(node, c), edits, score + delta + first, spatialScore + delta, changed, c, cur.depth[t] + 1)
        }
        if (edits >= maxEdits) return
        val doubled = position > 0 && typed == typedChar[position - 1]
        val insertion = if (doubled) params.doubledInsertionPenalty else params.insertionPenalty
        add(next, nextSlots, node, edits + 1, score + insertion, spatialScore, cur.changed[t], previous, cur.depth[t])
        if (position + 1 >= length || typed == typedChar[position + 1]) return
        val swapped = frontiers[position + 2]
        val swappedSlots = slots[position + 2]
        val other = typedChar[position + 1]
        for (c in index.children(node)) {
            val dc = delta(deltas, position + 1, c, other)
            if (dc < params.transpositionCutoff) continue
            val mid = index.child(node, c)
            for (d in index.children(mid)) {
                val dd = delta(deltas, position, d, typed)
                if (dd < params.transpositionCutoff) continue
                val changed = cur.changed[t] || c != other || d != typed
                val first = if (position == 0 && c != typed) params.firstLetterPenalty else 0f
                val cost = score + dc + dd + params.transpositionPenalty + first
                add(swapped, swappedSlots, index.child(mid, d), edits + 1, cost, spatialScore + dc + dd, changed, d, cur.depth[t] + 2)
            }
        }
    }

    /** Adds a state, keeping only the best score per (node, edits) at this position. */
    private fun add(frontier: State, slots: Slots, node: Int, edits: Int, score: Float, spatial: Float, changed: Boolean, prev: Char, depth: Int) {
        val key = (node.toLong() shl 3) or edits.toLong()
        val at = slots.find(key)
        if (at < 0) {
            slots.put(key, frontier.size)
            frontier.add(node, edits, score, spatial, changed, prev, depth)
        } else if (score > frontier.score[at]) {
            frontier.set(at, score, spatial, changed, prev)
        }
    }

    /** Full words at a state that consumed every typed key. */
    private fun offerTerminals(cur: State, t: Int, context: IntArray, found: MutableList<Candidate>) {
        val node = cur.node[t]
        var best = Vocabulary.UNKNOWN
        var bestLm = Float.NEGATIVE_INFINITY
        for (id in index.words(node)) {
            val lm = model.logProb(id, context)
            if (lm > bestLm) {
                bestLm = lm
                best = id
            }
        }
        if (best == Vocabulary.UNKNOWN) return
        val kind = if (cur.edits[t] == 0 && !cur.changed[t]) CandidateKind.EXACT else CandidateKind.CORRECTION
        val score = cur.score[t] + params.lmWeight * bestLm
        found += Candidate(best, vocabulary.surface(best), score, cur.spatial[t], bestLm, kind, cur.edits[t])
    }

    /** Longer words the input plausibly began. */
    private fun offerCompletions(cur: State, t: Int, context: IntArray, found: MutableList<Candidate>) {
        val node = cur.node[t]
        val depth = cur.depth[t]
        if (depth == 0) return // nothing typed yet: nothing to complete
        var taken = 0
        for (id in index.topWordsBelow(node, params.maxCompletions + index.words(node).size)) {
            val extra = vocabulary.key(id).length - depth
            if (extra <= 0) continue
            val lm = model.logProb(id, context)
            val penalty = params.completionPenalty + params.perExtraLetter * extra
            found += Candidate(
                id, vocabulary.surface(id), cur.score[t] + params.lmWeight * lm + penalty,
                cur.spatial[t], lm, CandidateKind.COMPLETION, cur.edits[t],
            )
            if (++taken == params.maxCompletions) return
        }
    }

    /** Keeps the best [limit] states of a position, ranked by score plus a language-model bound. */
    private fun prune(frontier: State, scratch: State, limit: Int) {
        val n = frontier.size
        if (n <= limit) return
        val packed = LongArray(n)
        for (t in 0 until n) {
            val rank = frontier.score[t] + params.lmWeight * index.bestBelow(frontier.node[t])
            packed[t] = (sortKey(rank).toLong() shl 32) or (t.toLong() and 0xffffffffL)
        }
        Arrays.sort(packed)
        scratch.clear()
        for (t in n - limit until n) {
            val at = packed[t].toInt()
            scratch.add(frontier.node[at], frontier.edits[at], frontier.score[at], frontier.spatial[at], frontier.changed[at], frontier.prev[at], frontier.depth[at])
        }
        System.arraycopy(scratch.node, 0, frontier.node, 0, limit)
        System.arraycopy(scratch.edits, 0, frontier.edits, 0, limit)
        System.arraycopy(scratch.score, 0, frontier.score, 0, limit)
        System.arraycopy(scratch.spatial, 0, frontier.spatial, 0, limit)
        System.arraycopy(scratch.changed, 0, frontier.changed, 0, limit)
        System.arraycopy(scratch.prev, 0, frontier.prev, 0, limit)
        System.arraycopy(scratch.depth, 0, frontier.depth, 0, limit)
        frontier.size = limit
    }

    /** ln P(touch | c) - ln P(touch | the typed key) for every typed key and letter. */
    private fun deltas(keys: List<TypedKey>, typedChar: CharArray): FloatArray {
        val table = FloatArray(keys.size * LETTERS)
        for (i in keys.indices) {
            val touch = keys[i].touch
            val lower = typedChar[i]
            for (c in 'a'..'z') {
                table[i * LETTERS + (c - 'a')] = when {
                    c == lower -> 0f
                    touch != null -> spatial.logLikelihood(touch, c) - spatial.logLikelihood(touch, lower)
                    else -> params.substitutionNoTouch
                }
            }
        }
        return table
    }

    private fun delta(table: FloatArray, position: Int, c: Char, typed: Char): Float {
        val at = c - 'a'
        return when {
            at in 0 until LETTERS -> table[position * LETTERS + at]
            c == typed -> 0f
            else -> params.substitutionNoTouch
        }
    }

    /** A score as an Int that sorts like the Float, so ranks can ride in the high 32 bits of a Long. */
    private fun sortKey(score: Float): Int {
        val bits = score.toRawBits()
        return bits xor (bits shr 31 or Int.MIN_VALUE)
    }

    private fun isApostrophe(c: Char) = c == '\'' || c == '’'

    private companion object {
        const val LETTERS = 26
    }

    /**
     * One state of the beam: a tree node, the input consumed to reach it, the edits assumed,
     * and the score so far. [prev] is the last letter of the node's key, needed to price a
     * doubled-letter omission; [depth] is the node's key length, for completion penalties.
     */
    private class State(initialCapacity: Int) {
        var size = 0
        var node = IntArray(initialCapacity)
        var edits = IntArray(initialCapacity)
        var score = FloatArray(initialCapacity)
        var spatial = FloatArray(initialCapacity)
        var changed = BooleanArray(initialCapacity)
        var prev = CharArray(initialCapacity)
        var depth = IntArray(initialCapacity)

        fun add(node: Int, edits: Int, score: Float, spatial: Float, changed: Boolean, prev: Char, depth: Int) {
            if (size == this.node.size) grow()
            this.node[size] = node
            this.edits[size] = edits
            this.score[size] = score
            this.spatial[size] = spatial
            this.changed[size] = changed
            this.prev[size] = prev
            this.depth[size] = depth
            size++
        }

        fun set(at: Int, score: Float, spatial: Float, changed: Boolean, prev: Char) {
            this.score[at] = score
            this.spatial[at] = spatial
            this.changed[at] = changed
            this.prev[at] = prev
        }

        fun clear() {
            size = 0
        }

        private fun grow() {
            val capacity = if (node.isEmpty()) 16 else node.size * 2
            node = node.copyOf(capacity)
            edits = edits.copyOf(capacity)
            score = score.copyOf(capacity)
            spatial = spatial.copyOf(capacity)
            changed = changed.copyOf(capacity)
            prev = prev.copyOf(capacity)
            depth = depth.copyOf(capacity)
        }
    }

    /** A tiny open-addressing map from a packed (node, edits) key to a [State] slot. */
    private class Slots(initialCapacity: Int = 256) {
        private var keys = LongArray(initialCapacity)
        private var values = IntArray(initialCapacity)
        private var size = 0
        private var limit = initialCapacity / 2

        fun clear() {
            if (size > 0) Arrays.fill(keys, 0L)
            size = 0
        }

        /** The slot holding [key], or -1. */
        fun find(key: Long): Int {
            val masked = key + 1 // 0 stays the empty marker
            var slot = mix(masked) and (keys.size - 1)
            while (true) {
                val stored = keys[slot]
                if (stored == 0L) return -1
                if (stored == masked) return values[slot]
                slot = (slot + 1) and (keys.size - 1)
            }
        }

        fun put(key: Long, value: Int) {
            if (size >= limit) grow()
            val masked = key + 1
            var slot = mix(masked) and (keys.size - 1)
            while (keys[slot] != 0L && keys[slot] != masked) slot = (slot + 1) and (keys.size - 1)
            if (keys[slot] == 0L) {
                keys[slot] = masked
                size++
            }
            values[slot] = value
        }

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            keys = LongArray(oldKeys.size * 2)
            values = IntArray(oldValues.size * 2)
            size = 0
            limit = keys.size / 2
            for (i in oldKeys.indices) {
                val key = oldKeys[i]
                if (key != 0L) {
                    var slot = mix(key) and (keys.size - 1)
                    while (keys[slot] != 0L) slot = (slot + 1) and (keys.size - 1)
                    keys[slot] = key
                    values[slot] = oldValues[i]
                    size++
                }
            }
        }

        private fun mix(key: Long): Int {
            var x = key * -7046029254386353131L
            x = x xor (x ushr 32)
            return x.toInt()
        }
    }
}
