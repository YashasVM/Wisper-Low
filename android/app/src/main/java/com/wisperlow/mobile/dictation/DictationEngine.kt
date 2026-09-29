package com.wisperlow.mobile.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import com.wisperlow.mobile.audio.AudioEngine
import com.wisperlow.mobile.audio.VadEngine
import com.wisperlow.mobile.audio.VadEvent
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.settings.WisperlowSettings
import com.wisperlow.mobile.stt.ModelDownloader
import com.wisperlow.mobile.stt.SttEngine
import com.wisperlow.mobile.text.PersonalDictionary
import com.wisperlow.mobile.text.TextCleaner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DictationState {
    data object Idle : DictationState

    /** Recording. [partial] holds phrases already transcribed while the user keeps talking. */
    data class Listening(val partial: String, val modelLoading: Boolean) : DictationState

    /** Recording stopped; the last phrase is being transcribed. */
    data class Finishing(val partial: String) : DictationState
}

enum class DictationError {
    NO_MODEL,
    MODEL_LOAD_FAILED,
    MIC_PERMISSION,
    MIC_UNAVAILABLE,
    NOTHING_HEARD,
    BUSY,
}

/**
 * The one owner of the microphone, voice detection and speech model. Every
 * surface (floating bubble, in-app practice) runs dictation through here so
 * only one recording can exist and the model is loaded once.
 *
 * Public methods must be called on the main thread. Native decoding runs on a
 * single dedicated thread, so model load, decode and release never overlap.
 */
@Singleton
class DictationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelDownloader: ModelDownloader,
    private val settingsRepository: SettingsRepository,
) {
    fun interface ResultListener {
        fun onResult(result: Result<String, DictationError>)
    }

    sealed interface Result<out T, out E> {
        data class Success<T>(val value: T) : Result<T, Nothing>
        data class Failure<E>(val error: E) : Result<Nothing, E>
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decodeDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wisperlow-stt").apply { priority = Thread.NORM_PRIORITY }
    }.asCoroutineDispatcher()

    private val audio = AudioEngine()

    // Touched only on the decode thread.
    private var stt: SttEngine? = null

    @Volatile private var vad: VadEngine? = null
    @Volatile private var session: Session? = null
    private var idleReleaseJob: Job? = null
    @Volatile private var lastLevelNanos = 0L

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Microphone loudness in 0..1 while listening. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _modelLoaded = MutableStateFlow(false)
    val modelLoaded: StateFlow<Boolean> = _modelLoaded.asStateFlow()

    val isActive: Boolean
        get() = session != null

    private class Session(
        val id: Long,
        val settings: WisperlowSettings,
        val listener: ResultListener,
    ) {
        val pcm = PcmBuffer()
        val boundaries = Channel<Int>(Channel.UNLIMITED)
        val parts = mutableListOf<String>()
        var consumer: Job? = null
        var finishing = false
        var modelLoading = true
    }

    private var nextSessionId = 0L

    /**
     * Starts recording immediately. The model loads in parallel if needed, so
     * the user can begin speaking without waiting. Returns false and reports an
     * error through [listener] when dictation cannot start.
     */
    fun start(listener: ResultListener): Boolean {
        checkMainThread()
        if (session != null) {
            listener.onResult(Result.Failure(DictationError.BUSY))
            return false
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onResult(Result.Failure(DictationError.MIC_PERMISSION))
            return false
        }
        val settings = settingsRepository.current.value
        val modelDir = resolveModelDir(settings) ?: run {
            listener.onResult(Result.Failure(DictationError.NO_MODEL))
            return false
        }
        val detector = ensureVad() ?: run {
            listener.onResult(Result.Failure(DictationError.MODEL_LOAD_FAILED))
            return false
        }
        idleReleaseJob?.cancel()

        val s = Session(++nextSessionId, settings, listener)
        session = s
        detector.reset(settings.autoStop.silenceMs)
        _level.value = 0f
        audio.setErrorListener { message ->
            Log.w(TAG, message)
            scope.launch { if (session === s) finish(s) }
        }
        audio.setListener { samples, level -> onAudio(s, detector, samples, level) }
        if (!audio.start()) {
            session = null
            audio.setListener(null)
            listener.onResult(Result.Failure(DictationError.MIC_UNAVAILABLE))
            scheduleIdleRelease()
            return false
        }
        s.modelLoading = !_modelLoaded.value
        publishListening(s)
        s.consumer = scope.launch { consume(s, modelDir) }
        return true
    }

    /** Stops recording and delivers the transcript once the last phrase is decoded. */
    fun finish() {
        checkMainThread()
        session?.let(::finish)
    }

    /** Stops recording and discards everything captured in this session. */
    fun cancel() {
        checkMainThread()
        val s = session ?: return
        session = null
        audio.stop()
        s.boundaries.close()
        s.consumer?.cancel()
        _level.value = 0f
        _state.value = DictationState.Idle
        scheduleIdleRelease()
    }

    /** Loads the selected model ahead of time so the next dictation has no cold start. */
    fun preload() {
        checkMainThread()
        val settings = settingsRepository.current.value
        val modelDir = resolveModelDir(settings) ?: return
        idleReleaseJob?.cancel()
        scope.launch {
            withContext(decodeDispatcher) { ensureStt(modelDir) }
            if (session == null) scheduleIdleRelease()
        }
    }

    /** Frees the speech model when no dictation is running. */
    fun releaseModelIfIdle() {
        checkMainThread()
        if (session != null) return
        idleReleaseJob?.cancel()
        scope.launch { withContext(NonCancellable + decodeDispatcher) { releaseStt() } }
    }

    /** Frees the model immediately and waits, e.g. before deleting its files. */
    suspend fun releaseModel() {
        withContext(Dispatchers.Main.immediate) { cancel() }
        withContext(NonCancellable + decodeDispatcher) { releaseStt() }
    }

    private fun finish(s: Session) {
        if (session !== s || s.finishing) return
        s.finishing = true
        audio.stop()
        _level.value = 0f
        val detector = vad
        // Drop most of the trailing silence that ended the session; it only
        // costs decode time. Keep a little so final consonants survive.
        val trailing = (detector?.trailingSilenceSamples ?: 0) - KEEP_TRAILING_SAMPLES
        val end = (s.pcm.size - trailing.coerceAtLeast(0)).coerceAtLeast(0)
        s.boundaries.trySend(end)
        s.boundaries.close()
        _state.value = DictationState.Finishing(joinParts(s))
        scope.launch {
            s.consumer?.join()
            if (session !== s) return@launch
            session = null
            _state.value = DictationState.Idle
            val text = compose(s)
            s.listener.onResult(
                if (text.isBlank()) Result.Failure(DictationError.NOTHING_HEARD) else Result.Success(text),
            )
            scheduleIdleRelease()
        }
    }

    /** Audio thread: run VAD, keep samples, and turn pauses into decode points. */
    private fun onAudio(s: Session, detector: VadEngine, samples: ShortArray, level: Float) {
        if (session !== s || s.finishing) return
        val event = detector.process(samples)
        s.pcm.append(samples)
        val now = System.nanoTime()
        if (now - lastLevelNanos >= LEVEL_INTERVAL_NANOS) {
            lastLevelNanos = now
            _level.value = level
        }
        val captured = s.pcm.size
        val heard = detector.heardSpeech
        when {
            captured >= MAX_CAPTURE_SAMPLES -> scope.launch { finish(s) }
            event == VadEvent.EndOfSpeech -> scope.launch { finish(s) }
            event == VadEvent.Pause -> s.boundaries.trySend(captured)
            !heard && captured >= noSpeechLimit(s.settings) -> scope.launch {
                if (session === s) {
                    cancel()
                    s.listener.onResult(Result.Failure(DictationError.NOTHING_HEARD))
                }
            }
        }
    }

    /** Decodes phrases in order as their boundaries arrive. */
    private suspend fun consume(s: Session, modelDir: File) {
        val engine = withContext(decodeDispatcher) { ensureStt(modelDir) }
        if (engine == null) {
            if (session === s) {
                cancel()
                s.listener.onResult(Result.Failure(DictationError.MODEL_LOAD_FAILED))
            }
            return
        }
        s.modelLoading = false
        if (session === s && !s.finishing) publishListening(s)
        var start = 0
        for (boundary in s.boundaries) {
            val isFinal = s.boundaries.isClosedForReceive
            // Very short fragments decode poorly on their own; merge them into
            // the next phrase unless this is the end of the recording.
            if (boundary - start < MIN_SEGMENT_SAMPLES && !isFinal && !s.finishing) continue
            if (boundary <= start) continue
            val pcm = s.pcm.slice(start, boundary)
            start = boundary
            if (pcm.size < MIN_DECODE_SAMPLES) continue
            val text = withContext(decodeDispatcher) { engine.transcribe(pcm) }
            val cleaned = TextCleaner.clean(text)
            if (!TextCleaner.looksLikeGibberish(cleaned)) s.parts += cleaned
            if (session === s) {
                _state.value = if (s.finishing) {
                    DictationState.Finishing(joinParts(s))
                } else {
                    DictationState.Listening(joinParts(s), modelLoading = false)
                }
            }
        }
    }

    private fun publishListening(s: Session) {
        _state.value = DictationState.Listening(joinParts(s), s.modelLoading)
    }

    private fun joinParts(s: Session): String = TextCleaner.clean(s.parts.joinToString(" "))

    private fun compose(s: Session): String =
        PersonalDictionary.apply(joinParts(s), s.settings.personalDictionary)

    private fun resolveModelDir(settings: WisperlowSettings): File? =
        modelDownloader.installedDirFor(settings.selectedModelId)
            ?: modelDownloader.installedModels().firstOrNull()

    private fun ensureVad(): VadEngine? {
        vad?.let { return it }
        return try {
            val target = File(context.filesDir, "models").apply { mkdirs() }
            val file = File(target, VAD_FILE)
            if (!file.isFile || file.length() == 0L) {
                val temp = File(target, "$VAD_FILE.tmp")
                context.assets.open(VAD_FILE).use { input -> temp.outputStream().use(input::copyTo) }
                check(temp.renameTo(file)) { "Cannot install voice detector" }
            }
            VadEngine(file).takeIf { it.load() }?.also { vad = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Voice detector unavailable", t)
            null
        }
    }

    /** Decode thread only. */
    private fun ensureStt(modelDir: File): SttEngine? {
        stt?.let { current ->
            if (current.modelDir == modelDir && current.isLoaded) return current
            current.release()
            stt = null
            _modelLoaded.value = false
        }
        val started = System.nanoTime()
        val engine = SttEngine(modelDir)
        if (!engine.load()) {
            engine.release()
            return null
        }
        Log.i(TAG, "Loaded ${modelDir.name} in ${(System.nanoTime() - started) / 1_000_000} ms")
        stt = engine
        _modelLoaded.value = true
        return engine
    }

    /** Decode thread only. */
    private fun releaseStt() {
        stt?.release()
        stt = null
        _modelLoaded.value = false
    }

    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        if (settingsRepository.current.value.keepModelLoaded) return
        idleReleaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (session == null) withContext(NonCancellable + decodeDispatcher) { releaseStt() }
        }
    }

    private fun noSpeechLimit(settings: WisperlowSettings): Int =
        if (settings.autoStop.silenceMs == 0L) NO_SPEECH_MANUAL_SAMPLES else NO_SPEECH_SAMPLES

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "DictationEngine must be used on the main thread" }
    }

    companion object {
        private const val TAG = "DictationEngine"
        private const val VAD_FILE = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_CAPTURE_SAMPLES = SAMPLE_RATE * 180
        private const val NO_SPEECH_SAMPLES = SAMPLE_RATE * 8
        private const val NO_SPEECH_MANUAL_SAMPLES = SAMPLE_RATE * 30
        private const val MIN_SEGMENT_SAMPLES = SAMPLE_RATE * 3 / 4
        private const val MIN_DECODE_SAMPLES = SAMPLE_RATE / 5
        private const val KEEP_TRAILING_SAMPLES = SAMPLE_RATE * 3 / 10
        private const val IDLE_RELEASE_MS = 5 * 60_000L
        private const val LEVEL_INTERVAL_NANOS = 60_000_000L
    }
}

/** Growable 16-bit PCM store shared between the audio and decode threads. */
internal class PcmBuffer {
    private var data = ShortArray(SAMPLE_RATE * 10)
    private var count = 0

    val size: Int
        @Synchronized get() = count

    @Synchronized
    fun append(samples: ShortArray) {
        if (count + samples.size > data.size) {
            data = data.copyOf(maxOf(data.size * 2, count + samples.size))
        }
        samples.copyInto(data, count)
        count += samples.size
    }

    @Synchronized
    fun slice(from: Int, to: Int): ShortArray {
        val start = from.coerceIn(0, count)
        val end = to.coerceIn(start, count)
        return data.copyOfRange(start, end)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
