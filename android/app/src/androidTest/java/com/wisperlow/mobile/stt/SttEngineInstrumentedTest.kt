package com.wisperlow.mobile.stt

import android.util.Log
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wisperlow.mobile.test.WavTestAudio
import java.io.File
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

        val wav = WavTestAudio.readPcm16(sample)
        assertTrue("Expected mono test audio", wav.channels == 1)
        assertTrue("Expected 24 kHz source fixture", wav.sampleRate == 24_000)
        val pcm = WavTestAudio.readMono16k(sample)
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
                assertTrue("Transcript missed 'country'", "country" in normalized)
                assertTrue("Transcript missed repeated 'ask'", normalized.split("ask").size >= 3)
                assertTrue("Transcript missed 'do'", "do" in normalized)
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

    companion object {
        private const val TAG = "SttEngineDeviceTest"
    }
}
