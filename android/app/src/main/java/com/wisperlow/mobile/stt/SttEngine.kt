package com.wisperlow.mobile.stt

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SttEngine(private val modelDir: File) {

    private var recognizer: OfflineRecognizer? = null

    val isLoaded: Boolean
        get() = recognizer != null

    suspend fun load(): Boolean = withContext(Dispatchers.Default) {
        if (recognizer != null) return@withContext true
        try {
            val config = buildConfig()
            recognizer = OfflineRecognizer(assetManager = null, config = config)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load model from ${modelDir.absolutePath}", t)
            recognizer = null
            false
        }
    }

    fun transcribe(pcm16kMono: ShortArray): String {
        synchronized(this) {
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
        synchronized(this) {
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
        get() = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_THREADS)

    companion object {
        private const val TAG = "SttEngine"
        private const val MAX_THREADS = 4
    }
}
