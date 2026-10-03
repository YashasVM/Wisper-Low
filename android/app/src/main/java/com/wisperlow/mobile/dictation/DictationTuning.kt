package com.wisperlow.mobile.dictation

/** Pure timing rules for dictation, kept free of Android types so they are JVM-testable. */
internal object DictationTuning {
    const val SAMPLE_RATE = 16_000

    /** Detector confirms speech ~0.2s after onset; step back past that plus pre-roll. */
    private const val LEAD_BACKOFF_SAMPLES = SAMPLE_RATE * 8 / 10

    /** Continuous speech with no pause is cut here so decode never sees one huge clip. */
    const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 20

    /** First sample worth decoding: skips leading silence, keeps a pre-roll before speech. */
    fun leadStart(speechStartCaptured: Int): Int =
        (speechStartCaptured - LEAD_BACKOFF_SAMPLES).coerceAtLeast(0)

    fun shouldForceBoundary(captured: Int, lastBoundary: Int): Boolean =
        captured - lastBoundary >= MAX_SEGMENT_SAMPLES

    /** ComponentCallbacks2 levels: RUNNING_CRITICAL=15, UI_HIDDEN=20, BACKGROUND and above=40+. */
    fun shouldReleaseOnTrim(level: Int, keepModelLoaded: Boolean): Boolean =
        level >= 15 && (level >= 40 || !keepModelLoaded) || level == 15
}
