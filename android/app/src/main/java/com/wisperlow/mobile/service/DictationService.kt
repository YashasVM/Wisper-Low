package com.wisperlow.mobile.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.wisperlow.mobile.MainActivity
import com.wisperlow.mobile.R
import com.wisperlow.mobile.accessibility.InsertResult
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.dictation.DictationEngine
import com.wisperlow.mobile.dictation.DictationError
import com.wisperlow.mobile.dictation.DictationState
import com.wisperlow.mobile.history.TranscriptRepository
import com.wisperlow.mobile.overlay.BubbleActions
import com.wisperlow.mobile.overlay.BubbleOverlay
import com.wisperlow.mobile.overlay.BubbleUi
import com.wisperlow.mobile.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hosts the floating bubble. Runs as a microphone foreground service so that
 * recording started from the bubble keeps microphone access while another app
 * is on screen; the microphone itself is only open while the user dictates.
 */
@AndroidEntryPoint
class DictationService : Service(), BubbleActions {

    @Inject lateinit var engine: DictationEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var transcriptRepository: TranscriptRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: BubbleOverlay
    private var portraitPosition: Pair<Int, Int>? = null
    private var landscapePosition: Pair<Int, Int>? = null
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val bubblePosition: Pair<Int, Int>?
        get() = if (isLandscape) landscapePosition else portraitPosition
    private var flashJob: Job? = null
    private var lastNotificationListening: Boolean? = null

    /** True while the engine session in progress was started from the bubble, not the in-app practice. */
    private var ownsSession = false

    /** Bubble state that belongs to this service rather than the engine. */
    private data class LocalUi(
        val review: String? = null,
        val flash: BubbleUi.Flash? = null,
        /** Hidden by drag-to-dismiss until the keyboard next closes. */
        val snoozed: Boolean = false,
    )

    private val local = MutableStateFlow(LocalUi())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (!startAsForeground()) {
            // startForegroundService() obliges us to have called startForeground();
            // stopping without it crashes the app, so the failure paths in
            // startAsForeground() still post the notification when allowed.
            stopSelf()
            return
        }
        _running.value = true
        getSystemService(NotificationManager::class.java).cancel(RestartReceiver.NOTIFICATION_ID)
        overlay = BubbleOverlay(this, this)
        scope.launch {
            portraitPosition = settingsRepository.bubblePosition(landscape = false).first()
            landscapePosition = settingsRepository.bubblePosition(landscape = true).first()
            observe()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engine.onTrimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation or split-screen changes the screen size; keep the bubble on screen.
        // Each orientation remembers its own spot.
        if (::overlay.isInitialized) overlay.reposition(bubblePosition)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                settingsRepository.setBubbleEnabled(false)
                stopSelf()
            }
        }
        // A killed process must not come back holding a microphone service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        if (::overlay.isInitialized) {
            // Only stop our own dictation; an in-app practice session is not ours to cancel.
            if (ownsSession && engine.isActive) engine.cancel()
            overlay.destroy()
        }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun observe() {
        scope.launch { engine.level.collect { overlay.setLevel(it) } }
        scope.launch { WisperlowAccessibilityService.keyboardTop.collect { overlay.setKeyboardTop(it) } }
        scope.launch {
            settingsRepository.bubblePosition(landscape = false).collect { position ->
                if (position == portraitPosition) return@collect
                portraitPosition = position
                // "Reset position" in settings: move a resting bubble right away.
                if (position == null && !isLandscape) overlay.resetPlacement()
            }
        }
        scope.launch {
            settingsRepository.bubblePosition(landscape = true).collect { position ->
                if (position == landscapePosition) return@collect
                landscapePosition = position
                if (position == null && isLandscape) overlay.resetPlacement()
            }
        }
        scope.launch {
            // Preload the model when the user starts typing so the first word is instant.
            WisperlowAccessibilityService.keyboardVisible.collect { visible ->
                if (visible) engine.preload() else local.update { it.copy(snoozed = false) }
            }
        }
        scope.launch {
            settingsRepository.settings.map { it.bubbleEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) stopSelf()
            }
        }
        combine(
            engine.state,
            settingsRepository.current,
            WisperlowAccessibilityService.keyboardVisible,
            WisperlowAccessibilityService.connected,
            local,
        ) { state, settings, keyboard, connected, localUi ->
            val ui = when {
                localUi.review != null -> BubbleUi.Review(localUi.review)
                state is DictationState.Listening -> BubbleUi.Listening(state.partial, state.modelLoading)
                state is DictationState.Finishing -> BubbleUi.Finishing(state.partial)
                localUi.flash != null -> localUi.flash
                else -> BubbleUi.Idle
            }
            val wantsBubble = !settings.bubbleOnlyWhenTyping || !connected || keyboard
            val visible = ui !is BubbleUi.Idle || (wantsBubble && !localUi.snoozed)
            ui to visible
        }.collect { (ui, visible) ->
            if (visible && !overlay.isShowing) overlay.show(bubblePosition)
            if (!visible && overlay.isShowing) overlay.hide()
            overlay.render(ui)
            updateNotification(listening = ui is BubbleUi.Listening || ui is BubbleUi.Finishing)
        }
    }

    // ---- bubble actions ----

    override fun onTap() {
        when (engine.state.value) {
            is DictationState.Listening -> engine.finish()
            is DictationState.Finishing -> Unit
            DictationState.Idle -> if (local.value.review == null) startDictation()
        }
    }

    override fun onLongPress() {
        if (engine.isActive) {
            engine.cancel()
            flash(getString(R.string.bubble_cancelled), success = false)
        } else {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onDismissDrop() {
        val settings = settingsRepository.current.value
        if (settings.bubbleOnlyWhenTyping && WisperlowAccessibilityService.connected.value) {
            local.update { it.copy(snoozed = true) }
            toast(R.string.bubble_snoozed)
        } else {
            toast(R.string.bubble_turned_off)
            scope.launch { settingsRepository.setBubbleEnabled(false) }
        }
    }

    override fun onMoved(side: Int, y: Int) {
        val landscape = isLandscape
        if (landscape) landscapePosition = side to y else portraitPosition = side to y
        scope.launch { settingsRepository.setBubblePosition(landscape, side, y) }
    }

    override fun onReviewInsert(text: String) {
        local.update { it.copy(review = null) }
        scope.launch {
            // Give focus back to the app below before inserting into its field.
            delay(REFOCUS_DELAY_MS)
            deliver(text)
        }
    }

    override fun onReviewCopy(text: String) {
        local.update { it.copy(review = null) }
        if (text.isBlank()) return
        copyToClipboard(text)
        remember(text)
        flash(getString(R.string.bubble_copied), success = true)
    }

    override fun onReviewCancel() {
        local.update { it.copy(review = null) }
    }

    // ---- dictation ----

    private fun startDictation() {
        flashJob?.cancel()
        local.update { it.copy(flash = null) }
        // Remember the field now; the review editor could take focus later. The lookup
        // crosses into the other app, so keep it off the frame that starts the bubble animation.
        scope.launch(Dispatchers.Default) { WisperlowAccessibilityService.captureTarget() }
        ownsSession = engine.start(
            listener = { result ->
                ownsSession = false
                when (result) {
                    is DictationEngine.Result.Success -> onTranscript(result.value)
                    is DictationEngine.Result.Failure -> flash(errorMessage(result.error), success = false)
                }
            },
            onCancelled = { ownsSession = false },
        )
    }

    private fun onTranscript(text: String) {
        if (settingsRepository.current.value.reviewBeforeInsert) {
            local.update { it.copy(review = text) }
        } else {
            deliver(text)
        }
    }

    private fun deliver(text: String) {
        if (text.isBlank()) return
        when (WisperlowAccessibilityService.insert(text)) {
            InsertResult.INSERTED, InsertResult.PASTED -> flash(getString(R.string.bubble_inserted), success = true)
            InsertResult.NO_TARGET -> {
                copyToClipboard(text)
                flash(
                    getString(
                        if (WisperlowAccessibilityService.connected.value) {
                            R.string.bubble_copied_no_field
                        } else {
                            R.string.bubble_copied_no_accessibility
                        },
                    ),
                    success = true,
                )
            }
        }
        remember(text)
    }

    private fun remember(text: String) {
        if (!settingsRepository.current.value.historyEnabled) return
        scope.launch {
            runCatching { transcriptRepository.add(text) }
                .onFailure { Log.e(TAG, "History save failed", it) }
        }
    }

    private fun errorMessage(error: DictationError): String = getString(
        when (error) {
            DictationError.NO_MODEL -> R.string.error_no_model
            DictationError.MODEL_LOAD_FAILED -> R.string.error_model_load
            DictationError.MIC_PERMISSION -> R.string.error_mic_permission
            DictationError.MIC_UNAVAILABLE -> R.string.error_mic_busy
            DictationError.NOTHING_HEARD -> R.string.error_nothing_heard
            DictationError.BUSY -> R.string.error_busy
        },
    )

    private fun flash(message: String, success: Boolean) {
        flashJob?.cancel()
        local.update { it.copy(flash = BubbleUi.Flash(message, success)) }
        flashJob = scope.launch {
            delay(if (success) FLASH_SUCCESS_MS else FLASH_ERROR_MS)
            local.update { it.copy(flash = null) }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_transcript_label), text))
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    // ---- foreground notification ----

    private fun startAsForeground(): Boolean {
        // Call startForeground first whenever the microphone type is allowed:
        // bailing out of a startForegroundService() call without it is a crash.
        val started = try {
            val notification = buildNotification(listening = false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: SecurityException) {
            // The microphone permission vanished between the check and now. Older
            // Android still accepts a type-less foreground start, which satisfies
            // the startForegroundService() contract before we stop ourselves.
            Log.e(TAG, "Microphone service not permitted", error)
            runCatching { startForeground(NOTIFICATION_ID, buildNotification(listening = false)) }
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not start the bubble service", error)
            false
        }
        return started && canStart(this)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_dictation), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    private fun buildNotification(listening: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DictationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(if (listening) R.string.notif_listening_title else R.string.notif_ready_title))
            .setContentText(getString(if (listening) R.string.notif_listening_body else R.string.notif_ready_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notif_action_turn_off), stop).build(),
            )
            .build()
    }

    private fun updateNotification(listening: Boolean) {
        if (lastNotificationListening == listening) return
        lastNotificationListening = listening
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(listening))
    }

    companion object {
        private const val TAG = "DictationService"
        const val CHANNEL_ID = "dictation"
        private const val NOTIFICATION_ID = 1001
        private const val REFOCUS_DELAY_MS = 250L
        private const val FLASH_SUCCESS_MS = 1_300L
        private const val FLASH_ERROR_MS = 2_600L
        const val ACTION_START = "com.wisperlow.mobile.action.START"
        const val ACTION_STOP = "com.wisperlow.mobile.action.STOP"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun canStart(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                Settings.canDrawOverlays(context)

        /** Must be called while the app is visible (or from a notification tap). */
        fun start(context: Context): Boolean {
            if (!canStart(context)) return false
            return try {
                context.startForegroundService(Intent(context, DictationService::class.java))
                true
            } catch (error: RuntimeException) {
                Log.w(TAG, "Bubble service start rejected", error)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DictationService::class.java))
        }
    }
}
