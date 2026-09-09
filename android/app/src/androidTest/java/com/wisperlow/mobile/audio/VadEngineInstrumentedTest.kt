package com.wisperlow.mobile.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wisperlow.mobile.test.WavTestAudio
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VadEngineInstrumentedTest {
    @Test
    fun realSpeechThenTrailingSilenceEmitsStartAndEndAfterReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(context.cacheDir, "vad-tests/silero_vad.onnx")
        model.parentFile?.mkdirs()
        if (!model.isFile) {
            context.assets.open("silero_vad.onnx").use { input ->
                model.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue("Bundled VAD model copy is missing", model.isFile && model.length() > 0)

        // The integration model package supplies this known 16 kHz sample.
        // Keep the test skippable on devices without the optional model data.
        val sample = File(
            context.filesDir,
            "models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/test_wavs/en.wav",
        )
        assumeTrue("Install the integration sample before running this test", sample.isFile)
        val pcm = WavTestAudio.readMono16k(sample)

        repeat(2) {
            val vad = VadEngine(model)
            try {
                assertTrue("VAD failed to load", vad.load())
                val events = mutableListOf<VadEvent>()
                feed(vad, pcm, events)
                feed(vad, ShortArray(16_000), events)
                assertTrue("Speech start was not detected", VadEvent.SpeechStart in events)
                assertTrue("Speech end was not detected", VadEvent.SpeechEnd in events)

                vad.reset()
                val silenceEvents = mutableListOf<VadEvent>()
                feed(vad, ShortArray(16_000), silenceEvents)
                assertFalse("Reset VAD emitted speech for silence", silenceEvents.isNotEmpty())
            } finally {
                vad.close()
            }
        }
    }

    private fun feed(vad: VadEngine, pcm: ShortArray, events: MutableList<VadEvent>) {
        (pcm.indices step WINDOW_SIZE)
            .map { start -> pcm.copyOfRange(start, (start + WINDOW_SIZE).coerceAtMost(pcm.size)) }
            .forEach { vad.process(it)?.let(events::add) }
    }

    companion object {
        private const val WINDOW_SIZE = 512
    }
}
