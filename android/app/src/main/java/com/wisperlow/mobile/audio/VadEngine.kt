package com.wisperlow.mobile.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

sealed interface VadEvent {
    data object SpeechStart : VadEvent
    data object SpeechEnd : VadEvent
}

class VadEngine(private val modelFile: File) : AutoCloseable {

    private var vad: Vad? = null

    private val pending = ArrayList<Float>(WINDOW_SIZE)
    private var inSpeech = false
    private var speechWindowCount = 0
    private var silenceWindowCount = 0

    @Synchronized
    fun process(samples: ShortArray): VadEvent? {
        ensureVad()
        val nativeVad = vad ?: return null
        for (s in samples) {
            pending.add(s / 32768f)
        }
        var event: VadEvent? = null
        var offset = 0
        try {
            while (offset + WINDOW_SIZE <= pending.size) {
                val window = FloatArray(WINDOW_SIZE)
                for (i in 0 until WINDOW_SIZE) {
                    window[i] = pending[offset + i]
                }
                offset += WINDOW_SIZE
                val probability = nativeVad.compute(window)
                event = step(probability)
                if (event != null) break
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD compute failed", t)
            pending.clear()
            return null
        }
        if (offset > 0) {
            pending.subList(0, offset).clear()
        }
        if (pending.size >= MAX_PENDING) {
            pending.clear()
        }
        return event
    }

    @Synchronized
    fun reset() {
        pending.clear()
        inSpeech = false
        speechWindowCount = 0
        silenceWindowCount = 0
        try {
            vad?.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "VAD reset failed", t)
        }
    }

    override fun close() {
        synchronized(this) {
            pending.clear()
            try {
                vad?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "VAD release failed", t)
            }
            vad = null
        }
    }

    private fun step(probability: Float): VadEvent? {
        if (!inSpeech) {
            if (probability >= SPEECH_START_THRESHOLD) {
                inSpeech = true
                silenceWindowCount = 0
                return VadEvent.SpeechStart
            }
            return null
        }
        speechWindowCount++
        if (probability <= SILENCE_THRESHOLD) {
            silenceWindowCount++
            val silenceSeconds = silenceWindowCount.toFloat() * windowSeconds()
            if (silenceSeconds >= HANGOVER_SECONDS) {
                if (speechDurationSeconds() < MIN_SPEECH_SECONDS) {
                    resetSpeechState()
                    return null
                }
                resetSpeechState()
                return VadEvent.SpeechEnd
            }
        } else {
            silenceWindowCount = 0
        }
        return null
    }

    private fun resetSpeechState() {
        inSpeech = false
        speechWindowCount = 0
        silenceWindowCount = 0
    }

    private fun speechDurationSeconds(): Float =
        speechWindowCount.toFloat() * windowSeconds()

    private fun windowSeconds(): Float = WINDOW_SIZE.toFloat() / SAMPLE_RATE

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
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SIZE = 512
        private const val SPEECH_START_THRESHOLD = 0.6f
        private const val SILENCE_THRESHOLD = 0.35f
        private const val HANGOVER_SECONDS = 0.7f
        private const val MIN_SPEECH_SECONDS = 0.25f
        private const val MAX_PENDING = WINDOW_SIZE * 8
    }
}
