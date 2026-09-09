package com.wisperlow.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

class AudioEngine(private val sampleRate: Int = 16000) {

    @Volatile private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var listener: ((samples: ShortArray, rmsLevel: Float) -> Unit)? = null

    @Volatile
    private var errorListener: ((message: String) -> Unit)? = null

    val isRunning: Boolean
        get() = running

    fun setListener(listener: ((samples: ShortArray, rmsLevel: Float) -> Unit)?) {
        this.listener = listener
    }

    fun setErrorListener(listener: ((message: String) -> Unit)?) {
        this.errorListener = listener
    }

    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        var rec: AudioRecord? = null
        val bufferBytes = minBuffer * 4
        return try {
            val created = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            rec = created
            if (created.state != AudioRecord.STATE_INITIALIZED) {
                created.release()
                return false
            }
            created.startRecording()
            record = created
            running = true
            thread = Thread({ readLoop(created) }, "wisperlow-audio").apply {
                // Audio input needs predictable scheduling, but MAX_PRIORITY
                // makes inference/UI work compete poorly with the rest of the
                // device and can increase heat on sustained dictation.
                priority = Thread.NORM_PRIORITY
                start()
            }
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Recording permission denied", e)
            running = false
            runCatching { rec?.release() }
            record = null
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            running = false
            runCatching { rec?.release() }
            record = null
            false
        }
    }

    fun stop() {
        running = false
        listener = null
        errorListener = null
        val worker = thread
        val activeRecord = record
        record = null
        thread = null
        // AudioRecord.read() may be blocking. Stop recording first so the
        // worker wakes immediately instead of making every manual stop wait
        // for the join timeout.
        try {
            activeRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord.stop failed", e)
        }
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
        // The worker owns release, including when a vendor read exceeds the
        // join timeout. Never release its native handle while read is active.
    }

    private fun readLoop(rec: AudioRecord) {
        // THREAD_PRIORITY_AUDIO is the scheduler hint intended for a capture
        // worker. It avoids starving the app while keeping audio callbacks
        // ahead of ordinary background work.
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: SecurityException) {
            // Some vendor builds reject the priority change; normal priority
            // is still safe because AudioRecord has its own native buffer.
        }
        val chunk = ShortArray(CHUNK_FRAMES)
        var emptyReads = 0
            while (running && record === rec) {
                val n = try {
                    rec.read(chunk, 0, CHUNK_FRAMES, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    notifyReadFailure("AudioRecord.read failed: ${e.message}")
                    break
                }
                if (!running || record !== rec) break
                if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) {
                    notifyReadFailure("AudioRecord stopped with read error: $n")
                    break
                }
                if (n < 0) {
                    notifyReadFailure("AudioRecord read failed: $n")
                    break
                }
                if (n == 0) {
                    // A vendor implementation may occasionally return an empty
                    // blocking read. Avoid a hot loop and fail after a short,
                    // bounded grace period if it never recovers.
                    emptyReads++
                    if (emptyReads >= MAX_EMPTY_READS) {
                        notifyReadFailure("AudioRecord returned no samples")
                        break
                    }
                    Thread.sleep(10L)
                    continue
                }
                emptyReads = 0
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
 {
            if (record === rec) running = false
            runCatching { rec.release() }
        }
    }

    private fun notifyReadFailure(message: String) {
        Log.w(TAG, message)
        errorListener?.invoke(message)
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val CHUNK_FRAMES = 512
        private const val MAX_EMPTY_READS = 5
    }
}
