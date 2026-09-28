package com.wisperlow.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wisperlow.mobile.R
import com.wisperlow.mobile.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android does not let an app start a microphone service on its own after a
 * reboot or update. A notification tap is allowed to, so offer one.
 */
@AndroidEntryPoint
class RestartReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (settings.onboardingCompleted && settings.bubbleEnabled && DictationService.canStart(context)) {
                    postRestartNotification(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun postRestartNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_restart),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val start = PendingIntent.getForegroundService(
            context,
            2,
            Intent(context, DictationService::class.java).setAction(DictationService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(context.getString(R.string.notif_restart_title))
            .setContentText(context.getString(R.string.notif_restart_body))
            .setContentIntent(start)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "restart"
        const val NOTIFICATION_ID = 1002
    }
}
