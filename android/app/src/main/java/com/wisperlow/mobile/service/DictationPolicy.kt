package com.wisperlow.mobile.service

/** Pure lifecycle rules kept separate so capture bounds can be regression-tested without Android. */
internal object DictationPolicy {
    const val MAX_CAPTURE_CHUNKS = 1_875 // One minute at 512 frames / 16 kHz.

    fun reachedCaptureLimit(chunkCount: Int): Boolean = chunkCount >= MAX_CAPTURE_CHUNKS

    fun acceptsTranscription(expectedGeneration: Long, currentGeneration: Long, processing: Boolean): Boolean =
        expectedGeneration == currentGeneration && processing
}
