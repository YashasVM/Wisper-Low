package com.wisperlow.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import com.wisperlow.mobile.MainActivity
import com.wisperlow.mobile.R
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.audio.AudioEngine
import com.wisperlow.mobile.audio.VadEngine
import com.wisperlow.mobile.audio.VadEvent
import com.wisperlow.mobile.history.TranscriptRepository
import com.wisperlow.mobile.overlay.BubbleMode
import com.wisperlow.mobile.overlay.BubbleOverlay
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.stt.ModelDownloader
import com.wisperlow.mobile.stt.SttEngine
import com.wisperlow.mobile.text.PersonalDictionary
import com.wisperlow.mobile.text.TextCleaner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

sealed interface DictationPhase {
    data object Initializing : DictationPhase
    data object Idle : DictationPhase
    data object Listening : DictationPhase
    data object Processing : DictationPhase
    data object Review : DictationPhase
    data class Error(val message: String) : DictationPhase
}

@AndroidEntryPoint
class DictationService : Service() {

    @Inject lateinit var modelDownloader: ModelDownloader
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var transcriptRepository: TranscriptRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = AudioEngine()
    private val initializationMutex = Mutex()
    private val inferenceMutex = Mutex()
    private var transcriptionJob: Job? = null
    @Inject lateinit var overlay: BubbleOverlay
    private var vad: VadEngine? = null
    private var stt: SttEngine? = null
    private var loadedModelId: String? = null
    private var idleReleaseJob: Job? = null
    private val transcriptionGeneration = AtomicLong(0L)
    @Volatile private var lastLevelUpdateNanos = 0L

    @Volatile private var recording = false
    @Volatile private var speechActive = false
    @Volatile private var pendingText: String? = null
    private val fullCapture = ArrayDeque<ShortArray>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _phase.value = DictationPhase.Initializing
        createChannel()
        scope.launch { initializeBubble() }
        scope.launch { watchSettings() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground()
        } catch (error: RuntimeException) {
            failStartup(error.message ?: "Could not start microphone service")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                cancelDictation()
                stopSelf()
            }
            ACTION_RELOAD_MODEL -> scope.launch { reloadModel() }
            else -> Unit
        }
        // A killed dictation process must not be recreated with a microphone
        // foreground service and a loaded model while the user is idle.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        transcriptionGeneration.incrementAndGet()
        transcriptionJob?.cancel()
        idleReleaseJob?.cancel()
        recording = false
        audio.setListener(null)
        audio.stop()
        vad?.close()
        vad = null
        if (_phase.value !is DictationPhase.Error) _phase.value = DictationPhase.Idle
        val engine = stt
        stt = null
        if (engine != null) {
            // Native decode is serialized with release; do not block the main
            // service teardown thread while a long utterance finishes.
            Thread({ engine.release() }, "wisperlow-stt-release").start()
        }
        overlay.hide()
        overlay.onTap = null
        overlay.onCancelGesture = null
        overlay.onConfirm = null
        overlay.onReviewTextChanged = null
        fullCapture.clear()
        pendingText = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification("Tap the bubble to dictate", idle = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun ensureBubbleReady() {
        val canDraw = android.provider.Settings.canDrawOverlays(this)
        if (!canDraw) {
            return failStartup("Overlay permission missing")
        }
        val modelDir = resolveModelDir() ?: run {
            return failStartup("No STT model installed")
        }
        // Validate installation at startup; allocate model weights on first dictation.
        check(modelDir.isDirectory) { "Installed model is unavailable" }
        prepareVad()
        val bubble = overlay
        bubble.onTap = {
            if (_phase.value !is DictationPhase.Initializing) {
                scope.launch {
                    initializationMutex.withLock {
                        if (recording) stopDictation() else startDictation()
                    }
                }
            }
        }
        bubble.onCancelGesture = {
            scope.launch { cancelDictation() }
        }
        bubble.onConfirm = {
            scope.launch { confirmPendingText() }
        }
        bubble.onReviewTextChanged = { text ->
            if (_phase.value is DictationPhase.Review) pendingText = text
        }
        bubble.show(BubbleMode.DOT)
        _phase.value = DictationPhase.Idle
        _running.value = true
        scheduleIdleModelRelease()
    }

    private suspend fun initializeBubble() {
        initializationMutex.withLock {
            try {
                ensureBubbleReady()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                android.util.Log.e(TAG, "Dictation initialization failed", error)
                failStartup(error.message ?: "Dictation initialization failed")
            }
        }
    }

    private suspend fun reloadModel() {
        transcriptionGeneration.incrementAndGet()
        transcriptionJob?.cancel()
        idleReleaseJob?.cancel()
        initializationMutex.withLock {
            recording = false
            speechActive = false
            pendingText = null
            audio.stop()
            synchronized(this) {
                fullCapture.clear()
            }
            overlay.hide()
            releaseStt()
            stt = null
            loadedModelId = null
            _running.value = false
            _phase.value = DictationPhase.Initializing
            try {
                ensureBubbleReady()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                android.util.Log.e(TAG, "Model reload failed", error)
                failStartup(error.message ?: "Model reload failed")
            }
        }
    }

    private fun failStartup(message: String) {
        _phase.value = DictationPhase.Error(message)
        _running.value = false
        showToast(message, long = true)
        stopSelf()
    }

    private suspend fun resolveModelDir(): File? {
        val settings = settingsRepository.settings.first()
        val byId = modelDownloader.installedDirFor(settings.selectedModelId)
        if (byId != null) return byId
        val installed = modelDownloader.installedModels().firstOrNull() ?: return null
        return installed
    }

    private suspend fun prepareStt(modelDir: File): Boolean {
        val modelId = modelDir.name
        if (stt == null || loadedModelId != modelId) {
            releaseStt()
            val engine = SttEngine(modelDir)
            val ok = try {
                withContext(Dispatchers.Default) { engine.load() }
            } catch (error: Throwable) {
                // Loading may allocate native weights before a coroutine is
                // cancelled. Do not strand that allocation on a failed load.
                withContext(NonCancellable + Dispatchers.Default) { engine.release() }
                throw error
            }
            if (!ok) {
                engine.release()
                failStartup("Failed to load model $modelId")
                return false
            }
            stt = engine
            loadedModelId = modelId
        }
        return true
    }

    private suspend fun prepareVad() {
        if (vad != null) return
        val target = File(filesDir, "models")
        target.mkdirs()
        val vadFile = File(target, "silero_vad.onnx")
        if (!vadFile.exists()) {
            withContext(Dispatchers.IO) {
                assets.open("silero_vad.onnx").use { input ->
                    vadFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        val engine = VadEngine(vadFile)
        try {
            withContext(Dispatchers.Default) {
                check(engine.load()) { "Voice detection model failed to load" }
            }
            vad = engine
        } catch (error: Throwable) {
            engine.close()
            throw error
        }
    }

    private suspend fun startDictation() {
        if (recording || _phase.value is DictationPhase.Processing || _phase.value is DictationPhase.Review) return
        if (_phase.value is DictationPhase.Initializing) return
        idleReleaseJob?.cancel()
        val requestGeneration = transcriptionGeneration.get()
        if (stt == null) {
            _phase.value = DictationPhase.Initializing
            overlay.show(BubbleMode.PROCESSING)
            updateNotification("Loading speech model…", idle = false)
            val modelDir = resolveModelDir()
            if (modelDir == null) {
                failStartup("Selected speech model is unavailable")
                return
            }
            if (!prepareStt(modelDir)) return
            if (requestGeneration != transcriptionGeneration.get()) return
        }
        synchronized(this) {
            if (requestGeneration != transcriptionGeneration.get()) return
            startRecording()
        }
    }

    private fun startRecording() {
        // Capture the app's input before the review overlay can become the
        // active window. This keeps confirm insertion out of our own editor.
        WisperlowAccessibilityService.captureEditableTarget()
        fullCapture.clear()
        vad?.reset()
        speechActive = false
        recording = true
        _phase.value = DictationPhase.Listening
        overlay.show(BubbleMode.LISTENING)
        updateNotification("Listening… speak now", idle = false)
        val captureGeneration = transcriptionGeneration.incrementAndGet()
        audio.setListener { samples, level ->
            // VAD stays on the capture worker. State and UI transitions run in
            // order on Main; copy once because AudioRecord reuses its array.
            val event = vad?.process(samples)
            val ownedSamples = samples.copyOf()
            scope.launch {
                if (!recording || captureGeneration != transcriptionGeneration.get()) return@launch
                val now = System.nanoTime()
                if (now - lastLevelUpdateNanos >= LEVEL_UPDATE_INTERVAL_NANOS) {
                    lastLevelUpdateNanos = now
                    overlay.setLevel(level)
                }
                handleAudio(ownedSamples, event)
            }
        }
        audio.setErrorListener { message ->
            scope.launch {
                if (!recording || captureGeneration != transcriptionGeneration.get()) return@launch
                android.util.Log.w(TAG, message)
                if (speechActive || synchronized(this@DictationService) { fullCapture.isNotEmpty() }) {
                    finishSpeech(force = true)
                } else {
                    audio.stop()
                    resetAfterProcessing()
                    showToast("Microphone stopped")
                }
            }
        }
        if (!audio.start()) {
            recording = false
            resetAfterProcessing()
            showToast("Microphone unavailable")
        }
    }

    private fun handleAudio(samples: ShortArray, event: VadEvent?) {
        if (!recording) return
        // Keep the entire utterance, including quiet starts and trailing words.
        // VAD determines when to stop, never which spoken frames to discard.
        fullCapture.addLast(samples)
        when (event) {
            VadEvent.SpeechStart -> speechActive = true
            VadEvent.SpeechEnd -> finishSpeech()
            null -> Unit
        }
        if (recording && DictationPolicy.reachedCaptureLimit(fullCapture.size)) {
            showToast("One-minute limit reached; reviewing captured speech")
            finishSpeech(force = true)
        }
    }

    private fun finishSpeech(force: Boolean = false) {
        if (!recording || (!speechActive && !force)) return
        recording = false
        speechActive = false
        audio.stop()
        val pcm = ShortArray(fullCapture.sumOf { it.size })
        var offset = 0
        for (chunk in fullCapture) {
            chunk.copyInto(pcm, offset)
            offset += chunk.size
        }
        fullCapture.clear()
        processPcm(pcm)
    }

    private fun stopDictation() {
        val hasCapturedAudio = synchronized(this) { fullCapture.isNotEmpty() }
        if (speechActive || hasCapturedAudio) {
            finishSpeech(force = true)
        } else {
            recording = false
            audio.stop()
            vad?.reset()
            overlay.show(BubbleMode.DOT)
            _phase.value = DictationPhase.Idle
            updateNotification("Tap the bubble to dictate", idle = true)
            scheduleIdleModelRelease()
        }
    }

    private fun cancelDictation() {
        transcriptionJob?.cancel()
        synchronized(this) {
            transcriptionGeneration.incrementAndGet()
            speechActive = false
            recording = false
            pendingText = null
        }
        audio.stop()
        synchronized(this) {
            fullCapture.clear()
        }
        overlay.show(BubbleMode.DOT)
        _phase.value = DictationPhase.Idle
        updateNotification("Cancelled", idle = true)
        scheduleIdleModelRelease()
    }

    private fun processPcm(pcm: ShortArray) {
        if (pcm.size < MIN_PCM_SAMPLES) {
            resetAfterProcessing()
            updateNotification("Too short — try again", idle = true)
            return
        }
        overlay.show(BubbleMode.PROCESSING)
        _phase.value = DictationPhase.Processing
        updateNotification("Transcribing…", idle = false)
        val generation = transcriptionGeneration.get()
        transcriptionJob?.cancel()
        transcriptionJob = scope.launch {
            try {
                val engine = stt ?: error("STT not loaded")
                val raw = inferenceMutex.withLock {
                    withContext(Dispatchers.Default) { engine.transcribe(pcm) }
                }
                if (!DictationPolicy.acceptsTranscription(
                        generation,
                        transcriptionGeneration.get(),
                        _phase.value is DictationPhase.Processing,
                    )) {
                    return@launch
                }
                val cleaned = TextCleaner.clean(raw)
                if (TextCleaner.looksLikeGibberish(cleaned)) {
                    showToast("Speech not understood")
                    return@launch resetAfterProcessing()
                }
                val dictionary = settingsRepository.settings.first().personalDictionary
                if (!DictationPolicy.acceptsTranscription(
                        generation,
                        transcriptionGeneration.get(),
                        _phase.value is DictationPhase.Processing,
                    )) {
                    return@launch
                }
                pendingText = PersonalDictionary.apply(cleaned, dictionary)
                _phase.value = DictationPhase.Review
                overlay.setReviewText(pendingText.orEmpty())
                overlay.show(BubbleMode.REVIEW)
                updateNotification("Review dictation", idle = false)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!DictationPolicy.acceptsTranscription(
                        generation,
                        transcriptionGeneration.get(),
                        _phase.value is DictationPhase.Processing,
                    )) {
                    return@launch
                }
                android.util.Log.e(TAG, "transcription failed", t)
                showToast("Transcription failed: ${t.message}")
                resetAfterProcessing()
            }
        }
    }

    private suspend fun confirmPendingText() {
        if (_phase.value !is DictationPhase.Review) return
        val text = pendingText?.takeIf { it.isNotBlank() } ?: return resetAfterProcessing()
        _phase.value = DictationPhase.Processing
        overlay.show(BubbleMode.PROCESSING)
        pendingText = null
        pasteAndSave(text, saveToHistory = true)
    }

    private suspend fun pasteAndSave(text: String, saveToHistory: Boolean) {
        val ok = withContext(Dispatchers.Main) {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("Wisperlow transcript", text))
            WisperlowAccessibilityService.pasteText(text)
        }
        if (saveToHistory) {
            runCatching { transcriptRepository.add(text) }
                .onFailure { android.util.Log.e(TAG, "history save failed", it) }
        }
        if (!ok) {
            showToast(
                "Copied to clipboard. Enable Wisperlow Accessibility for automatic insertion",
                long = true,
            )
        }
        resetAfterProcessing()
    }

    private fun resetAfterProcessing() {
        transcriptionGeneration.incrementAndGet()
        pendingText = null
        vad?.reset()
        overlay.show(BubbleMode.DOT)
        _phase.value = DictationPhase.Idle
        updateNotification("Tap the bubble to dictate", idle = true)
        scheduleIdleModelRelease()
    }

    private fun scheduleIdleModelRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch {
            delay(IDLE_MODEL_RELEASE_MS)
            initializationMutex.withLock {
                if (_phase.value is DictationPhase.Idle && !recording) {
                    releaseStt()
                }
            }
        }
    }

    private suspend fun releaseStt() {
        val engine = stt
        stt = null
        loadedModelId = null
        if (engine != null) withContext(NonCancellable + Dispatchers.Default) { engine.release() }
    }

    private suspend fun watchSettings() {
        settingsRepository.settings.collect { settings ->
            if (settings.bubbleEnabled && _phase.value is DictationPhase.Idle &&
                android.provider.Settings.canDrawOverlays(this)
            ) {
                overlay.show(BubbleMode.DOT)
            }
        }
    }

    private fun showToast(message: String, long: Boolean = false) {
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DictationService, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_dictation), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(body: String, idle: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_listening_title))
            .setContentText(body)
            .setContentIntent(contentIntent)
        if (!idle) builder.setOngoing(true)
        return builder.build()
    }

    private fun updateNotification(body: String, idle: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(body, idle))
    }

    companion object {
        private const val TAG = "DictationService"
        private const val CHANNEL_ID = "dictation"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_PCM_SAMPLES = 1600
        private const val IDLE_MODEL_RELEASE_MS = 120_000L
        private const val LEVEL_UPDATE_INTERVAL_NANOS = 100_000_000L
        const val ACTION_STOP = "com.wisperlow.mobile.action.STOP"
        const val ACTION_RELOAD_MODEL = "com.wisperlow.mobile.action.RELOAD_MODEL"

        val running: StateFlow<Boolean> get() = _running
        private val _running = MutableStateFlow(false)
        val phase: StateFlow<DictationPhase> get() = _phase
        private val _phase = MutableStateFlow<DictationPhase>(DictationPhase.Idle)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DictationService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DictationService::class.java))
        }

        fun reloadModel(context: Context) {
            context.startService(
                Intent(context, DictationService::class.java).setAction(ACTION_RELOAD_MODEL),
            )
        }
    }
}
