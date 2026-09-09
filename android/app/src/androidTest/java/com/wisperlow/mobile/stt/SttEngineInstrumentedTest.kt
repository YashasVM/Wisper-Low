package com.wisperlow.mobile.stt

import android.util.Log
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SttEngineInstrumentedTest {
    @Test
    fun loadsRealModelAndTranscribesBundledSample() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = ModelCatalog.PARAKEET_V3_INT8
        val modelDir = File(context.filesDir, "models/${model.dirName}")
        val sample = File(modelDir, "test_wavs/en.wav")
        assertTrue("Install the integration model before running this test", sample.isFile)

        val pcm = readPcm16Wav(sample)
        var previousTranscript: String? = null
        repeat(2) { pass ->
            val engine = SttEngine(modelDir)
            try {
                val loadStarted = SystemClock.elapsedRealtimeNanos()
                assertTrue("Native recognizer failed to load", engine.load())
                val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1_000_000
                // Loading an already initialized engine must be idempotent.
                assertTrue("Repeated model load was not idempotent", engine.load())
                val decodeStarted = SystemClock.elapsedRealtimeNanos()
                val transcript = engine.transcribe(pcm)
                val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStarted) / 1_000_000
                val normalized = transcript.lowercase()
                Log.i(
                    TAG,
                    "Real-model pass ${pass + 1}: load=${loadMs}ms " +
                        "decode=${decodeMs}ms nativeHeap=${Debug.getNativeHeapAllocatedSize()} " +
                        "transcript=$transcript",
                )
                assertTrue("The real model returned an empty transcript", transcript.length > 5)
                assertTrue("Transcript missed 'tribal'", "tribal" in normalized)
                assertTrue("Transcript missed 'chieftain'", "chieftain" in normalized)
                assertTrue("Transcript missed the final 'gold'", "gold" in normalized)
                if (previousTranscript != null) {
                    assertTrue(
                        "Repeated inference changed the transcript",
                        previousTranscript == transcript,
                    )
                }
                previousTranscript = transcript
            } finally {
                engine.release()
                assertTrue("Recognizer was not released", !engine.isLoaded)
            }
        }
    }

    private fun readPcm16Wav(file: File): ShortArray {
        val bytes = file.readBytes()
        val dataMarker = "data".toByteArray(Charsets.US_ASCII)
        val dataOffset = bytes.indices.firstOrNull { index ->
            index + 8 <= bytes.size && dataMarker.indices.all { offset ->
                bytes[index + offset] == dataMarker[offset]
            }
        } ?: error("WAV data chunk missing")
        val dataSize = ByteBuffer.wrap(bytes, dataOffset + 4, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .coerceAtMost(bytes.size - dataOffset - 8)
        val pcm = ByteBuffer.wrap(bytes, dataOffset + 8, dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(dataSize / 2) { pcm.short }
    }

    companion object {
        private const val TAG = "SttEngineDeviceTest"
    }
}
