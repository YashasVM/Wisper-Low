package com.wisperlow.mobile

import android.app.Application
import android.util.Log
import com.wisperlow.mobile.dictation.DictationEngine
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class WisperlowApp : Application() {

    @Inject lateinit var engine: DictationEngine

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            val keyboard = File(filesDir, "keyboard")
            if (keyboard.exists() && !keyboard.deleteRecursively()) {
                Log.w("WisperlowApp", "Could not remove legacy keyboard data")
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The engine decides whether the level warrants releasing the model.
        engine.onTrimMemory(level)
    }
}
