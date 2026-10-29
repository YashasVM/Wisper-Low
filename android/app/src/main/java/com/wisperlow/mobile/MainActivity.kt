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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.service.DictationService
import com.wisperlow.mobile.ui.AppActions
import com.wisperlow.mobile.ui.WisperlowRoot
import com.wisperlow.mobile.ui.WisperlowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
            openOverlaySettings = {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
            openAccessibilitySettings = ::openAccessibilitySettings,
            openAppInfo = ::openAppInfo,
            startBubble = ::startBubble,
            stopBubble = ::stopBubble,
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
        lifecycleScope.launch {
            val state = viewModel.uiState.first { it.settingsLoaded }
            // Bring the bubble back after a reboot or app update once the user opens the app.
            if (state.settings.onboardingCompleted && state.settings.bubbleEnabled && !state.bubbleRunning) {
                DictationService.start(this@MainActivity)
            }
        }
    }

    private fun refreshAccess() {
        viewModel.updateAccess(
            SystemAccess(
                microphone = granted(Manifest.permission.RECORD_AUDIO),
                notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    granted(Manifest.permission.POST_NOTIFICATIONS),
                overlay = Settings.canDrawOverlays(this),
                accessibilityEnabled = WisperlowAccessibilityService.isEnabledInSettings(this),
            ),
        )
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
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray(),
        )
    }

    private fun openAccessibilitySettings() {
        val component = ComponentName(this, WisperlowAccessibilityService::class.java).flattenToString()
        // These extras make Settings scroll to and highlight our entry on most phones.
        val highlight = Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, highlight)
        open(intent)
    }

    private fun openAppInfo() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun startBubble() {
        // Persist first: the service stops itself when it reads a stored "off" at startup.
        lifecycleScope.launch {
            viewModel.setBubbleEnabled(true).join()
            if (!DictationService.start(this@MainActivity)) {
                Toast.makeText(this@MainActivity, R.string.home_needs_setup, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopBubble() {
        viewModel.setBubbleEnabled(false)
        DictationService.stop(this)
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
        const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    }
}
