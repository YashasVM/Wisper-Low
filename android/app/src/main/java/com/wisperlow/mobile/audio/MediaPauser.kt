package com.wisperlow.mobile.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Pauses music, videos and podcasts while the user dictates, the way Gboard's voice typing does.
 *
 * Holds transient exclusive audio focus: well-behaved players pause (rather than duck) and resume
 * on their own once focus is abandoned. Main thread only.
 */
class MediaPauser(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        // Dictation never plays audio, so there is nothing to do if another app takes focus back.
        .setOnAudioFocusChangeListener { }
        .setWillPauseWhenDucked(false)
        .build()

    private var held = false

    fun pause() {
        if (held || audioManager == null) return
        held = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!held) Log.i(TAG, "Audio focus refused; media keeps playing")
    }

    fun resume() {
        if (!held || audioManager == null) return
        held = false
        audioManager.abandonAudioFocusRequest(request)
    }

    private companion object {
        const val TAG = "MediaPauser"
    }
}
