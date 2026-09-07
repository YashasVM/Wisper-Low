package com.wisperlow.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioEngine(private val sampleRate: Int = 16000) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var listener: ((samples: ShortArray, rmsLevel: Float) -> Unit)? = null

    val isRunning: Boolean
        get() = running

    fun setListener(listener: ((samples: ShortArray, rmsLevel: Float) -> Unit)?) {
        this.listener = listener
    }

    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val bufferBytes = minBuffer * 4
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            thread = Thread({ readLoop(rec) }, "wisperlow-audio").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Recording permission denied", e)
            running = false
            record?.release()
            record = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            running = false
            record?.release()
            record = null
            false
        }
    }

    fun stop() {
        running = false
        val worker = thread
        // VAD can decide that speech ended from inside the audio callback.
        // Joining the callback thread from itself stalls for the full timeout
        // and makes every dictation feel like it hangs after speaking.
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(2000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
        record?.release()
        record = null
    }

    private fun readLoop(rec: AudioRecord) {
        val chunk = ShortArray(CHUNK_FRAMES)
        while (running) {
            val n = rec.read(chunk, 0, CHUNK_FRAMES)
            if (n <= 0) continue
            var sumSquares = 0.0
            for (i in 0 until n) {
                val s = chunk[i].toDouble()
                sumSquares += s * s
            }
            val rms = kotlin.math.sqrt(sumSquares / n)
            val level = (rms / 32767.0).coerceIn(0.0, 1.0)
            val samples = if (n == CHUNK_FRAMES) chunk else chunk.copyOf(n)
            listener?.invoke(samples, level.toFloat())
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val CHUNK_FRAMES = 512
    }
}
