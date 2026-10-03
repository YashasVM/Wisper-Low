package com.wisperlow.mobile

import android.app.Application
import com.wisperlow.mobile.dictation.DictationEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WisperlowApp : Application() {

    @Inject lateinit var engine: DictationEngine

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The engine decides whether the level warrants releasing the model.
        engine.onTrimMemory(level)
    }
}
