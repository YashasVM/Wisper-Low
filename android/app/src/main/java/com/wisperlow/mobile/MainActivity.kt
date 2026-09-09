package com.wisperlow.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.history.TranscriptEntry
import com.wisperlow.mobile.service.DictationPhase
import com.wisperlow.mobile.service.DictationService
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.ModelDownloader
import com.wisperlow.mobile.ui.SetupState
import com.wisperlow.mobile.ui.WisperlowAppScreen
import com.wisperlow.mobile.ui.WisperlowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelDownloader: ModelDownloader
    @Inject lateinit var settingsRepository: SettingsRepository

    private val viewModel: MainViewModel by viewModels()

    private val permissionRefresh = mutableIntStateOf(0)
    private var startRequestInFlight = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WisperlowTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRefresh.intValue++
    }

    @Composable
    private fun MainScreen() {
        val uiState by viewModel.uiState.collectAsState()
        val settings = uiState.settings
        val downloadStates by modelDownloader.states.collectAsState()
        val serviceRunning by DictationService.running.collectAsState()
        val servicePhase by DictationService.phase.collectAsState()
        val refresh = permissionRefresh.intValue

        LaunchedEffect(serviceRunning, servicePhase) {
            if (serviceRunning || servicePhase is DictationPhase.Error) {
                startRequestInFlight = false
            }
        }

        LaunchedEffect(Unit) { modelDownloader.refresh() }

        val setup = remember(refresh, downloadStates, settings.selectedModelId) {
            SetupState(
                microphoneReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                overlayReady = Settings.canDrawOverlays(this),
                accessibilityReady = WisperlowAccessibilityService.isReady,
                modelReady = downloadStates[settings.selectedModelId] is DownloadState.Completed,
            )
        }

        WisperlowAppScreen(
            settings = settings,
            setup = setup,
            serviceRunning = serviceRunning,
            servicePhase = servicePhase,
            downloadStates = downloadStates,
            dictionaryText = uiState.dictionaryText,
            history = uiState.history,
            onRequestMicrophone = ::requestCorePermissions,
            onRequestOverlay = {
                openSystemSettings(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            },
            onRequestAccessibility = {
                openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDownloadModel = { model ->
                lifecycleScope.launch {
                    try {
                        modelDownloader.download(model)
                        settingsRepository.setSelectedModel(model.id)
                        if (DictationService.running.value ||
                            DictationService.phase.value is DictationPhase.Initializing
                        ) {
                            DictationService.reloadModel(this@MainActivity)
                        }
                    } catch (_: Throwable) {
                        // ModelDownloader publishes the user-visible failure state.
                    }
                }
            },
            onSelectModel = { model ->
                lifecycleScope.launch {
                    settingsRepository.setSelectedModel(model.id)
                    // A running service owns the recognizer, so reload it only
                    // after the selection has been persisted.
                    if (DictationService.running.value ||
                        DictationService.phase.value is DictationPhase.Initializing
                    ) {
                        DictationService.reloadModel(this@MainActivity)
                    }
                }
            },
            onToggleService = {
                if (serviceRunning) {
                    DictationService.stop(this)
                } else if (setup.isReady && !startRequestInFlight) {
                    startRequestInFlight = true
                    lifecycleScope.launch { settingsRepository.setBubbleEnabled(true) }
                    try {
                        DictationService.start(this)
                    } catch (_: SecurityException) {
                        startRequestInFlight = false
                        showToast(R.string.dictation_start_failed)
                    } catch (_: IllegalStateException) {
                        startRequestInFlight = false
                        showToast(R.string.dictation_start_failed)
                    }
                }
            },
            onBubbleEnabledChange = { enabled ->
                lifecycleScope.launch { settingsRepository.setBubbleEnabled(enabled) }
                if (!enabled && serviceRunning) DictationService.stop(this)
            },
            onDictionaryTextChange = { text ->
                viewModel.setDictionaryText(text)
            },
            onCopyHistory = { entry -> copyTranscript(entry) },
            onDeleteHistory = { entry -> viewModel.deleteHistory(entry.id) },
        )
    }

    private fun requestCorePermissions() {
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray(),
        )
    }

    private fun openSystemSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToast(R.string.system_settings_unavailable)
        } catch (_: SecurityException) {
            showToast(R.string.system_settings_unavailable)
        }
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    private fun copyTranscript(entry: TranscriptEntry) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.clipboard_transcript_label), entry.text),
        )
    }
}
