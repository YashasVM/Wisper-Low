package com.wisperlow.mobile.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

class SttEngine(val modelDir: File) {

    // Loading and native inference both mutate sherpa's native state. Keep the
    // lifecycle and decode operations serialized so a reload cannot publish a
    // recognizer while another thread is releasing the previous one.
    private val lifecycleLock = Any()
    private var recognizer: OfflineRecognizer? = null

    val isLoaded: Boolean
        get() = synchronized(lifecycleLock) { recognizer != null }

    /** Allocates the native recognizer. Blocking; call from a worker thread. */
    fun load(): Boolean {
        synchronized(lifecycleLock) {
            if (recognizer != null) return true
            try {
                val config = buildConfig()
                recognizer = OfflineRecognizer(assetManager = null, config = config)
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load model from ${modelDir.absolutePath}", t)
                recognizer = null
                return false
            }
        }
    }

    fun transcribe(pcm16kMono: ShortArray): String {
        synchronized(lifecycleLock) {
            val rec = recognizer ?: return ""
            if (pcm16kMono.isEmpty()) return ""
            val floats = FloatArray(pcm16kMono.size)
            for (i in pcm16kMono.indices) {
                floats[i] = pcm16kMono[i] / 32768f
            }
            return try {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(floats)
                    rec.decode(stream)
                    rec.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Transcription failed", t)
                ""
            }
        }
    }

    fun release() {
        synchronized(lifecycleLock) {
            try {
                recognizer?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Recognizer release failed", t)
            }
            recognizer = null
        }
    }

    private fun buildConfig(): OfflineRecognizerConfig {
        val files = modelDir.listFiles { f -> f.isFile }
            ?: throw IllegalArgumentException("Not a readable directory: ${modelDir.absolutePath}")
        val encoder = files.firstOrNull { it.name.startsWith("encoder") && it.name.endsWith(".onnx") }
        val decoder = files.firstOrNull { it.name.startsWith("decoder") && it.name.endsWith(".onnx") }
        val joiner = files.firstOrNull { it.name.startsWith("joiner") && it.name.endsWith(".onnx") }
        val tokens = files.firstOrNull { it.name == "tokens.txt" }

        if (encoder != null && decoder != null) {
            if (tokens == null) {
                throw IllegalArgumentException("Transducer layout found but tokens.txt missing")
            }
            return OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner?.absolutePath ?: "",
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = inferenceThreads,
                    debug = false,
                    provider = "cpu",
                    modelType = "nemo_transducer",
                ),
            )
        }

        val singleModel = files.singleOrNull {
            it.name.endsWith(".onnx") && it.name != "tokens.txt"
        }
        if (singleModel != null) {
            if (tokens == null) {
                throw IllegalArgumentException("NeMo CTC layout found but tokens.txt missing")
            }
            return OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = singleModel.absolutePath),
                    tokens = tokens.absolutePath,
                    numThreads = inferenceThreads,
                    debug = false,
                    provider = "cpu",
                ),
            )
        }
        throw IllegalArgumentException("No recognizable model layout in ${modelDir.absolutePath}")
    }

    private val inferenceThreads: Int
        get() = threadsFor(Runtime.getRuntime().availableProcessors())

    companion object {
        private const val TAG = "SttEngine"
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 4

        /**
         * Half the cores (big+little mix makes more threads counterproductive),
         * clamped so decode is faster on 8-core phones without a thermal burst.
         */
        fun threadsFor(cores: Int): Int = (cores / 2).coerceIn(MIN_THREADS, MAX_THREADS)
    }
}
