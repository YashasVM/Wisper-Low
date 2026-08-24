package com.wisperlow.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import javax.inject.Inject

sealed interface DictationPhase {
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audio = AudioEngine()
    private val overlay = LazyOverlay()
    private val phase = MutableStateFlow<DictationPhase>(DictationPhase.Idle)
    private var vad: VadEngine? = null
    private var stt: SttEngine? = null
    private var loadedModelId: String? = null

    private var recording = false
    private var speechActive = false
    private var pendingText: String? = null
    private val collected = ArrayList<ShortArray>(256)
    private val preroll = ArrayDeque<ShortArray>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { ensureBubbleReady() }
        scope.launch { watchSettings() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP -> stopDictation()
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        audio.stop()
        vad?.close()
        stt?.release()
        overlay.get()?.hide()
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
            phase.value = DictationPhase.Error("Overlay permission missing")
            return
        }
        val modelDir = resolveModelDir() ?: run {
            phase.value = DictationPhase.Error("No STT model installed")
            return
        }
        if (!prepareStt(modelDir)) return
        prepareVad()
        val bubble = overlay.get() ?: run {
            phase.value = DictationPhase.Error("Bubble unavailable")
            return
        }
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
        phase.value = DictationPhase.Idle
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
                phase.value = DictationPhase.Error("Failed to load model $modelId")
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
        vad = VadEngine(vadFile)
    }

    private fun startDictation() {
        if (recording || phase.value is DictationPhase.Processing || phase.value is DictationPhase.Review) return
        collected.clear()
        preroll.clear()
        speechActive = false
        recording = true
        phase.value = DictationPhase.Listening
        overlay.get()?.show(BubbleMode.LISTENING)
        updateNotification("Listening… speak now", idle = false)
        audio.setListener { samples, level ->
            overlay.get()?.setLevel(level)
            handleAudio(samples)
        }
        if (!audio.start()) {
            recording = false
            phase.value = DictationPhase.Error("Microphone unavailable")
            overlay.get()?.show(BubbleMode.DOT)
        }
    }

    private fun handleAudio(samples: ShortArray) {
        val event = synchronized(this) {
            if (preroll.size > PREROLL_CHUNKS) preroll.removeFirst()
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
    }

    private fun finishSpeech() {
        if (!speechActive) return
        speechActive = false
        val pcm = synchronized(this) {
            recording = false
            audio.stop()
            val flat = ShortArray(collected.sumOf { it.size })
            var offset = 0
            for (chunk in collected) {
                chunk.copyInto(flat, offset)
                offset += chunk.size
            }
            collected.clear()
            flat
        }
        processPcm(pcm)
    }

    private fun stopDictation() {
        if (speechActive) {
            finishSpeech()
        } else {
            recording = false
            audio.stop()
            overlay.get()?.show(BubbleMode.DOT)
            updateNotification("Tap the bubble to dictate", idle = true)
        }
    }

    private fun cancelDictation() {
        speechActive = false
        recording = false
        pendingText = null
        audio.stop()
        synchronized(this) {
            collected.clear()
            preroll.clear()
        }
        overlay.get()?.show(BubbleMode.DOT)
        updateNotification("Cancelled", idle = true)
    }

    private fun processPcm(pcm: ShortArray) {
        if (pcm.size < MIN_PCM_SAMPLES) {
            resetAfterProcessing()
            updateNotification("Too short — try again", idle = true)
            return
        }
        overlay.get()?.show(BubbleMode.PROCESSING)
        phase.value = DictationPhase.Processing
        updateNotification("Transcribing…", idle = false)
        scope.launch {
            try {
                val engine = stt ?: error("STT not loaded")
                val raw = withContext(Dispatchers.Default) { engine.transcribe(pcm) }
                val cleaned = TextCleaner.clean(raw)
                if (TextCleaner.looksLikeGibberish(cleaned)) {
                    showToast("Speech not understood")
                    return@launch resetAfterProcessing()
                }
                val command = TextCleaner.classifyCommand(cleaned)
                when (command) {
                    "cancel", "undo" -> resetAfterProcessing()
                    else -> {
                        val dictionary = settingsRepository.settings.first().personalDictionary
                        pendingText = PersonalDictionary.apply(cleaned, dictionary)
                        phase.value = DictationPhase.Review
                        overlay.get()?.show(BubbleMode.REVIEW)
                        updateNotification("Review dictation", idle = false)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "transcription failed", t)
                showToast("Transcription failed: ${t.message}")
                resetAfterProcessing()
            }
        }
    }

    private suspend fun confirmPendingText() {
        val text = pendingText ?: return
        pendingText = null
        val ok = WisperlowAccessibilityService.pasteText(text)
        if (!ok) {
            showToast(
                "Enable Wisperlow in Accessibility settings so text can be pasted",
                long = true,
            )
        }
        resetAfterProcessing()
    }

    private fun resetAfterProcessing() {
        pendingText = null
        vad?.reset()
        overlay.get()?.show(BubbleMode.DOT)
        phase.value = DictationPhase.Idle
        updateNotification("Tap the bubble to dictate", idle = true)
    }

    private suspend fun watchSettings() {
        settingsRepository.settings.collect { settings ->
            if (settings.bubbleEnabled && phase.value is DictationPhase.Idle &&
                android.provider.Settings.canDrawOverlays(this)
            ) {
                overlay.get()?.show(BubbleMode.DOT)
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

    private inner class LazyOverlay {
        private var instance: BubbleOverlay? = null
        fun get(): BubbleOverlay? {
            if (!android.provider.Settings.canDrawOverlays(applicationContext)) return null
            if (instance == null) instance = BubbleOverlay(applicationContext)
            return instance
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
        private const val MIN_PCM_SAMPLES = 8000
        const val ACTION_STOP = "com.wisperlow.mobile.action.STOP"

        val running: StateFlow<Boolean> get() = _running
        private val _running = MutableStateFlow(false)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DictationService::class.java))
            _running.value = true
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DictationService::class.java).setAction(ACTION_STOP),
            )
            context.stopService(Intent(context, DictationService::class.java))
            _running.value = false
        }
    }
}
