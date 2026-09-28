package com.wisperlow.mobile

import android.app.Application
import android.content.ComponentCallbacks2
import com.wisperlow.mobile.dictation.DictationEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WisperlowApp : Application() {

    @Inject lateinit var engine: DictationEngine

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The speech model is the largest allocation by far. Give it back when
        // Android is short on memory and nobody is dictating.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            engine.releaseModelIfIdle()
        }
    }
}
