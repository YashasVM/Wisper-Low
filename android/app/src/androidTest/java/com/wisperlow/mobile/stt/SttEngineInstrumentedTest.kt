package com.wisperlow.mobile.stt

import android.util.Log
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

        val engine = SttEngine(modelDir)
        try {
            assertTrue("Native recognizer failed to load", engine.load())
            val transcript = engine.transcribe(readPcm16Wav(sample))
            Log.i(TAG, "Real-model transcript: $transcript")
            assertTrue("The real model returned an empty transcript", transcript.length > 5)
        } finally {
            engine.release()
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
