package com.wisperlow.mobile.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.wisperlow.mobile.BuildConfig
import java.io.File

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
        if (BuildConfig.DEBUG && DEBUG_MIC_FILE.isFile) return startDebugMic()
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
                // VOICE_RECOGNITION skips the call-oriented AGC/echo processing
                // that smears consonants and hurts recognizer accuracy.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
        try {
            while (running && record === rec) {
                val n = try {
                    rec.read(chunk, 0, CHUNK_FRAMES, AudioRecord.READ_BLOCKING)
                } catch (e: IllegalStateException) {
                    notifyReadFailure("AudioRecord.read failed: ${e.message}")
                    break
                }
                if (!running || record !== rec) break
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
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            // The worker owns the native handle so it is never released while
            // a blocking read is still inside the vendor HAL.
            if (record === rec) {
                running = false
                record = null
            }
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
    }

    /**
     * Debug builds only: replays raw 16 kHz mono s16le PCM from [DEBUG_MIC_FILE] in real time,
     * then silence, in place of the microphone. Emulators often run without host audio, so this
     * is the only way to exercise the bubble end to end there.
     */
    private fun startDebugMic(): Boolean {
        val bytes = runCatching { DEBUG_MIC_FILE.readBytes() }.getOrNull() ?: return false
        val pcm = ShortArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        Log.i(TAG, "Debug mic: replaying ${pcm.size} samples from $DEBUG_MIC_FILE")
        running = true
        thread = Thread({
            var pos = 0
            val chunkMs = CHUNK_FRAMES * 1000L / sampleRate
            while (running) {
                val chunk = ShortArray(CHUNK_FRAMES)
                val n = (pcm.size - pos).coerceIn(0, CHUNK_FRAMES)
                if (n > 0) System.arraycopy(pcm, pos, chunk, 0, n)
                pos += n
                var sumSquares = 0.0
                for (s in chunk) sumSquares += s.toDouble() * s
                val level = (kotlin.math.sqrt(sumSquares / CHUNK_FRAMES) / 32767.0).coerceIn(0.0, 1.0)
                listener?.invoke(chunk, level.toFloat())
                try {
                    Thread.sleep(chunkMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "wisperlow-audio-debug").apply { start() }
        return true
    }

    private fun notifyReadFailure(message: String) {
        Log.w(TAG, message)
        errorListener?.invoke(message)
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val CHUNK_FRAMES = 512
        private const val MAX_EMPTY_READS = 5
        private val DEBUG_MIC_FILE = File("/sdcard/Android/data/com.wisperlow.mobile/files/debug-mic.pcm")
    }
}
