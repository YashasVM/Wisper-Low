package com.wisperlow.mobile.keyboard.eval

/**
 * Evaluation-only diagnostics: one record per intended token, so a run can be diffed
 * as an exact raw -> composing -> committed chain instead of only as aggregate rates.
 * Nothing here feeds a metric; it is written only when `--trace=FILE` is passed.
 *
 * The fields mirror what the simulator already observes: [raw] is the dictionary-free
 * literal the user's tap events spell on the board, [composing] is the engine's literal
 * after any [SessionEngine.resolveTap] reading, [committed] is what reached the field,
 * and [strip] is what the suggestion bar showed just before the word-ending space.
 * [steps] names each event's raw character and the composing delta it produced, so a
 * resolver that changed a tap shows as `w>e`.
 */
data class TokenTrace(
    val preset: String,
    val seed: Long,
    val sentence: Int,
    val intended: String,
    val planned: String,
    val raw: String,
    val steps: List<String>,
    val composing: String,
    val strip: List<String>,
    val revertible: String?,
    val committed: String,
    val outcome: String,
) {
    fun asJson(): Map<String, Any?> = linkedMapOf(
        "preset" to preset,
        "seed" to seed,
        "sentence" to sentence,
        "intended" to intended,
        "planned" to planned,
        "raw" to raw,
        "steps" to steps,
        "composing" to composing,
        "strip" to strip,
        "revertible" to revertible,
        "committed" to committed,
        "outcome" to outcome,
    )

    companion object {
        /** The classification the trace uses for a token; only [FALSE] is an engine defect. */
        const val FALSE = "false-correction"
        const val CASE = "case-variant"
        const val RESCUE = "rescue"
        const val MISS = "miss"
        const val OK = "ok"

        fun classify(clean: Boolean, correct: Boolean, exact: Boolean): String = when {
            clean && !correct -> FALSE
            clean && !exact -> CASE
            !clean && correct -> RESCUE
            else -> OK
        }
    }
}
