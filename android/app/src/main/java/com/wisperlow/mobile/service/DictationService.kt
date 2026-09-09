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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = AudioEngine()
    private val initializationMutex = Mutex()
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
    private val collected = ArrayList<ShortArray>(256)
    private val preroll = ArrayDeque<ShortArray>()
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
        startAsForeground()
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
        idleReleaseJob?.cancel()
        audio.setListener(null)
        audio.stop()
        vad?.close()
        stt?.release()
        overlay.hide()
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
        if (!prepareStt(modelDir)) return
        prepareVad()
        val bubble = overlay
        bubble.onTap = {
            scope.launch { if (recording) stopDictation() else startDictation() }
        }
        bubble.onCancelGesture = {
            scope.launch { cancelDictation() }
        }
        bubble.onConfirm = {
            scope.launch { confirmPendingText() }
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
                android.util.Log.e(TAG, "Dictation initialization failed", error)
                failStartup(error.message ?: "Dictation initialization failed")
            }
        }
    }

    private suspend fun reloadModel() {
        transcriptionGeneration.incrementAndGet()
        idleReleaseJob?.cancel()
        initializationMutex.withLock {
            recording = false
            speechActive = false
            pendingText = null
            audio.stop()
            synchronized(this) {
                collected.clear()
                preroll.clear()
                fullCapture.clear()
            }
            overlay.hide()
            stt?.release()
            stt = null
            loadedModelId = null
            _running.value = false
            _phase.value = DictationPhase.Initializing
            try {
                ensureBubbleReady()
            } catch (error: Throwable) {
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
            stt?.release()
            val engine = SttEngine(modelDir)
            val ok = withContext(Dispatchers.Default) { engine.load() }
            if (!ok) {
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
        vad = VadEngine(vadFile).also { engine ->
            check(engine.load()) { "Voice detection model failed to load" }
        }
    }

    private fun startDictation() {
        if (recording || _phase.value is DictationPhase.Processing || _phase.value is DictationPhase.Review) return
        idleReleaseJob?.cancel()
        if (stt == null) {
            _phase.value = DictationPhase.Initializing
            scope.launch {
                initializationMutex.withLock {
                    val modelDir = resolveModelDir()
                    if (modelDir == null || !prepareStt(modelDir)) return@withLock
                    startRecording()
                }
            }
            return
        }
        startRecording()
    }

    private fun startRecording() {
        collected.clear()
        preroll.clear()
        fullCapture.clear()
        speechActive = false
        recording = true
        _phase.value = DictationPhase.Listening
        overlay.show(BubbleMode.LISTENING)
        updateNotification("Listening… speak now", idle = false)
        audio.setListener { samples, level ->
            val now = System.nanoTime()
            if (now - lastLevelUpdateNanos >= LEVEL_UPDATE_INTERVAL_NANOS) {
                lastLevelUpdateNanos = now
                scope.launch(Dispatchers.Main.immediate) { overlay.setLevel(level) }
            }
            handleAudio(samples)
        }
        if (!audio.start()) {
            recording = false
            _phase.value = DictationPhase.Error("Microphone unavailable")
            overlay.show(BubbleMode.DOT)
        }
    }

    private fun handleAudio(samples: ShortArray) {
        if (!recording) return
        var reachedDurationLimit = false
        val event = synchronized(this) {
            fullCapture.addLast(samples.copyOf())
            reachedDurationLimit = fullCapture.size >= MAX_CAPTURE_CHUNKS
            if (preroll.size >= PREROLL_CHUNKS) preroll.removeFirst()
            preroll.addLast(samples.copyOf())
            vad?.process(samples)
        }
        when (event) {
            VadEvent.SpeechStart -> {
                speechActive = true
                synchronized(this) {
                    collected.addAll(preroll)
                    preroll.clear()
                }
            }
            VadEvent.SpeechEnd -> finishSpeech()
            null -> {
                if (speechActive) {
                    synchronized(this) { collected.add(samples.copyOf()) }
                }
            }
        }
        if (reachedDurationLimit) {
            showToast("Maximum dictation length reached")
            finishSpeech(force = true)
        }
    }

    private fun finishSpeech(force: Boolean = false) {
        val shouldFinish = synchronized(this) {
            if (!recording || (!speechActive && !force)) {
                false
            } else {
                speechActive = false
                recording = false
                true
            }
        }
        if (!shouldFinish) return
        // Stop and join the capture thread before taking the collection lock.
        // Otherwise a manual stop can wait on a callback that is itself waiting
        // for this lock, adding a two-second stall to every dictation.
        audio.stop()
        val pcm = synchronized(this) {
            // A manual stop can happen before VAD crosses its speech threshold.
            // In that case retain any collected frames instead of silently
            // dropping the whole dictation.
            val chunks = if (collected.isNotEmpty()) collected else fullCapture.toList()
            val flat = ShortArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(flat, offset)
                offset += chunk.size
            }
            collected.clear()
            preroll.clear()
            fullCapture.clear()
            flat
        }
        processPcm(pcm)
    }

    private fun stopDictation() {
        val hasCapturedAudio = synchronized(this) { collected.isNotEmpty() || fullCapture.isNotEmpty() }
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
        transcriptionGeneration.incrementAndGet()
        speechActive = false
        recording = false
        pendingText = null
        audio.stop()
        synchronized(this) {
            collected.clear()
            preroll.clear()
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
        scope.launch {
            try {
                val engine = stt ?: error("STT not loaded")
                val raw = withContext(Dispatchers.Default) { engine.transcribe(pcm) }
                if (generation != transcriptionGeneration.get() || _phase.value !is DictationPhase.Processing) {
                    return@launch
                }
                val command = TextCleaner.classifyCommand(raw)
                if (command != null) {
                    handleCommand(command)
                    return@launch
                }
                val cleaned = TextCleaner.clean(raw)
                if (TextCleaner.looksLikeGibberish(cleaned)) {
                    showToast("Speech not understood")
                    return@launch resetAfterProcessing()
                }
                val dictionary = settingsRepository.settings.first().personalDictionary
                pendingText = PersonalDictionary.apply(cleaned, dictionary)
                _phase.value = DictationPhase.Review
                overlay.setReviewText(pendingText.orEmpty())
                overlay.show(BubbleMode.REVIEW)
                updateNotification("Review dictation", idle = false)
            } catch (t: Throwable) {
                if (generation != transcriptionGeneration.get() || _phase.value !is DictationPhase.Processing) {
                    return@launch
                }
                android.util.Log.e(TAG, "transcription failed", t)
                showToast("Transcription failed: ${t.message}")
                resetAfterProcessing()
            }
        }
    }

    private suspend fun handleCommand(command: String) {
        when (command) {
            "newline" -> pasteAndSave("\n", saveToHistory = false)
            "paragraph" -> pasteAndSave("\n\n", saveToHistory = false)
            "send" -> {
                val sent = withContext(Dispatchers.Main) {
                    WisperlowAccessibilityService.pressEnter()
                }
                if (!sent) {
                    showToast("Could not send in this text field")
                }
                resetAfterProcessing()
            }
            "cancel", "undo" -> resetAfterProcessing()
            else -> resetAfterProcessing()
        }
    }

    private suspend fun confirmPendingText() {
        val text = pendingText ?: return
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
            if (_phase.value is DictationPhase.Idle && !recording) {
                stt?.release()
                stt = null
                loadedModelId = null
            }
        }
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
        private const val PREROLL_CHUNKS = 15
        private const val MAX_CAPTURE_CHUNKS = 9375 // Five minutes at 512 frames / 16 kHz.
        private const val MIN_PCM_SAMPLES = 8000
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
            context.startService(
                Intent(context, DictationService::class.java).setAction(ACTION_STOP),
            )
        }

        fun reloadModel(context: Context) {
            context.startService(
                Intent(context, DictationService::class.java).setAction(ACTION_RELOAD_MODEL),
            )
        }
    }
}
