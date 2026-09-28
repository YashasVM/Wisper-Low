package com.wisperlow.mobile.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

sealed interface VadEvent {
    /** The first real speech of the session was heard. */
    data object SpeechStart : VadEvent

    /** A short pause after speech: a safe point to decode what was said so far. */
    data object Pause : VadEvent

    /** Silence lasted long enough that the speaker is done. */
    data object EndOfSpeech : VadEvent
}

class VadEngine(private val modelFile: File) : AutoCloseable {

    private var vad: Vad? = null

    private val pending = FloatArray(MAX_PENDING)
    // The bundled Silero v5 model requires the preceding 64 samples followed
    // by each new 512-sample window when using sherpa's raw compute API.
    private val window = FloatArray(CONTEXT_SIZE + WINDOW_SIZE)
    private var pendingCount = 0
    private val detector = SpeechDetector()

    /** True once the current session contains speech longer than a click. */
    val heardSpeech: Boolean
        @Synchronized get() = detector.heardSpeech

    /** Samples of silence since the last speech window. */
    val trailingSilenceSamples: Int
        @Synchronized get() = detector.silenceWindows * WINDOW_SIZE

    @Synchronized
    fun load(): Boolean {
        ensureVad()
        return vad != null
    }

    @Synchronized
    fun process(samples: ShortArray): VadEvent? {
        ensureVad()
        val nativeVad = vad ?: return null
        for (s in samples) {
            if (pendingCount == pending.size) break
            pending[pendingCount++] = s / 32768f
        }
        var event: VadEvent? = null
        var offset = 0
        try {
            while (offset + WINDOW_SIZE <= pendingCount) {
                pending.copyInto(window, destinationOffset = CONTEXT_SIZE, startIndex = offset, endIndex = offset + WINDOW_SIZE)
                offset += WINDOW_SIZE
                val probability = nativeVad.compute(window)
                window.copyInto(window, destinationOffset = 0, startIndex = WINDOW_SIZE, endIndex = window.size)
                event = detector.step(probability) ?: event
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD compute failed", t)
            pendingCount = 0
            return null
        }
        if (offset > 0) {
            pending.copyInto(pending, destinationOffset = 0, startIndex = offset, endIndex = pendingCount)
            pendingCount -= offset
        }
        return event
    }

    /** Starts a new session. [endSilenceMs] of 0 disables automatic end of speech. */
    @Synchronized
    fun reset(endSilenceMs: Long) {
        pendingCount = 0
        window.fill(0f)
        detector.reset(endSilenceMs)
        try {
            vad?.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "VAD reset failed", t)
        }
    }

    override fun close() {
        synchronized(this) {
            pendingCount = 0
            try {
                vad?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "VAD release failed", t)
            }
            vad = null
        }
    }

    private fun ensureVad() {
        if (vad != null) return
        try {
            vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = modelFile.absolutePath,
                        threshold = 0.55f,
                        minSpeechDuration = 0.2f,
                        windowSize = WINDOW_SIZE,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create VAD", t)
            vad = null
        }
    }

    companion object {
        private const val TAG = "VadEngine"
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE = 512
        private const val CONTEXT_SIZE = 64
        private const val MAX_PENDING = WINDOW_SIZE * 8
    }
}

/**
 * Pure speech/pause state machine over per-window speech probabilities, kept
 * free of native code so its timing rules are unit-testable on the JVM.
 */
internal class SpeechDetector(
    private val windowSeconds: Float = VadEngine.WINDOW_SIZE.toFloat() / VadEngine.SAMPLE_RATE,
) {
    var heardSpeech = false
        private set
    var silenceWindows = 0
        private set

    private var speechWindows = 0
    private var pauseEmitted = true
    private var endEmitted = false
    private var endSilenceWindows = 0

    fun reset(endSilenceMs: Long) {
        heardSpeech = false
        silenceWindows = 0
        speechWindows = 0
        pauseEmitted = true
        endEmitted = false
        endSilenceWindows = if (endSilenceMs <= 0L) 0 else windowsFor(endSilenceMs / 1000f)
    }

    fun step(probability: Float): VadEvent? {
        if (probability >= SPEECH_THRESHOLD) {
            silenceWindows = 0
            speechWindows++
            if (speechWindows >= windowsFor(MIN_SPEECH_SECONDS)) {
                pauseEmitted = false
                if (!heardSpeech) {
                    heardSpeech = true
                    return VadEvent.SpeechStart
                }
            }
            return null
        }
        // Probabilities between the thresholds neither extend speech nor count
        // as silence, which keeps soft word endings from splitting segments.
        if (probability > SILENCE_THRESHOLD) return null
        silenceWindows++
        if (silenceWindows >= windowsFor(BURST_GAP_SECONDS)) speechWindows = 0
        if (!heardSpeech) return null
        if (endSilenceWindows > 0 && !endEmitted && silenceWindows >= endSilenceWindows) {
            endEmitted = true
            return VadEvent.EndOfSpeech
        }
        if (!pauseEmitted && silenceWindows >= windowsFor(PAUSE_SECONDS)) {
            pauseEmitted = true
            return VadEvent.Pause
        }
        return null
    }

    private fun windowsFor(seconds: Float): Int = kotlin.math.ceil(seconds / windowSeconds).toInt().coerceAtLeast(1)

    companion object {
        const val SPEECH_THRESHOLD = 0.6f
        const val SILENCE_THRESHOLD = 0.35f
        const val MIN_SPEECH_SECONDS = 0.2f
        const val PAUSE_SECONDS = 0.5f
        const val BURST_GAP_SECONDS = 0.3f
    }
}
