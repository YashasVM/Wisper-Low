package com.wisperlow.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wisperlow.mobile.keyboard.WisperlowKeyboardService
import com.wisperlow.mobile.ui.AppActions
import com.wisperlow.mobile.ui.WisperlowRoot
import com.wisperlow.mobile.ui.WisperlowTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { !viewModel.uiState.value.settingsLoaded }
        enableEdgeToEdge()
        val actions = AppActions(
            requestMicrophone = ::requestMicrophone,
            openKeyboardSettings = { open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            pickKeyboard = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
            openAppInfo = ::openAppInfo,
            copyText = ::copyText,
            isMetered = {
                getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
            },
        )
        setContent {
            WisperlowTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                // Passed as a reader, not a value: only the leaves that draw it react to each sample.
                val level = viewModel.level.collectAsStateWithLifecycle()
                WisperlowRoot(state = state, level = { level.value }, viewModel = viewModel, actions = actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccess()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog over us, so closing it only returns focus.
        if (hasFocus) refreshAccess()
    }

    private fun refreshAccess() {
        viewModel.updateAccess(
            SystemAccess(
                microphone = granted(Manifest.permission.RECORD_AUDIO),
                keyboardEnabled = getSystemService(InputMethodManager::class.java)
                    .enabledInputMethodList.any { it.id == keyboardId },
                keyboardSelected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == keyboardId,
            ),
        )
    }

    private val keyboardId by lazy {
        ComponentName(this, WisperlowKeyboardService::class.java).flattenToShortString()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMicrophone() {
        val askedBefore = prefs.getBoolean(KEY_MIC_ASKED, false)
        if (!granted(Manifest.permission.RECORD_AUDIO) && askedBefore &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        ) {
            // Android stops showing the prompt after repeated denials; only settings can fix it.
            openAppInfo()
            return
        }
        prefs.edit().putBoolean(KEY_MIC_ASKED, true).apply()
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    private fun openAppInfo() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_transcript_label), text))
        // Confirmation is shown as an in-app snackbar by the caller.
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.system_settings_unavailable, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.system_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val KEY_MIC_ASKED = "mic_asked"
    }
}
