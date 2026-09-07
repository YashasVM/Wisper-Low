package com.wisperlow.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.history.TranscriptEntry
import com.wisperlow.mobile.history.TranscriptRepository
import com.wisperlow.mobile.service.DictationService
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.settings.WisperlowSettings
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.ModelDownloader
import com.wisperlow.mobile.ui.SetupState
import com.wisperlow.mobile.ui.WisperlowAppScreen
import com.wisperlow.mobile.ui.WisperlowTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelDownloader: ModelDownloader
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var transcriptRepository: TranscriptRepository

    private val permissionRefresh = mutableIntStateOf(0)

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
        var settings by remember { mutableStateOf(WisperlowSettings()) }
        val downloadStates by modelDownloader.states.collectAsState()
        var dictionaryText by remember { mutableStateOf("") }
        val history by transcriptRepository.entries.collectAsState()
        val serviceRunning by DictationService.running.collectAsState()
        val scope = rememberCoroutineScope()
        val refresh = permissionRefresh.intValue

        LaunchedEffect(Unit) {
            settingsRepository.settings.collect { latest ->
                settings = latest
                if (dictionaryText.isEmpty()) {
                    dictionaryText = latest.personalDictionary.entries.joinToString("\n") { (spoken, written) ->
                        "$spoken=$written"
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            transcriptRepository.load()
        }
        LaunchedEffect(Unit) { modelDownloader.refresh() }

        val setup = remember(refresh, downloadStates) {
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
            downloadStates = downloadStates,
            dictionaryText = dictionaryText,
            history = history,
            onRequestMicrophone = ::requestCorePermissions,
            onRequestOverlay = {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            },
            onRequestAccessibility = {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDownloadModel = { model ->
                scope.launch {
                    try {
                        modelDownloader.download(model)
                        settingsRepository.setSelectedModel(model.id)
                    } catch (_: Throwable) {
                        // ModelDownloader publishes the user-visible failure state.
                    }
                }
            },
            onSelectModel = { model ->
                scope.launch {
                    settingsRepository.setSelectedModel(model.id)
                    // A running service owns the loaded recognizer. Restart it
                    // after a model change so the next tap uses the model the
                    // catalogue shows as active.
                    if (DictationService.running.value) {
                        DictationService.stop(this@MainActivity)
                        delay(250)
                        DictationService.start(this@MainActivity)
                    }
                }
            },
            onToggleService = {
                if (serviceRunning) {
                    DictationService.stop(this)
                } else if (setup.isReady) {
                    scope.launch { settingsRepository.setBubbleEnabled(true) }
                    DictationService.start(this)
                }
            },
            onBubbleEnabledChange = { enabled ->
                scope.launch { settingsRepository.setBubbleEnabled(enabled) }
                if (!enabled && serviceRunning) DictationService.stop(this)
            },
            onDictionaryTextChange = { text ->
                dictionaryText = text
                scope.launch {
                    settingsRepository.setPersonalDictionary(SettingsRepository.parseDictionary(text))
                }
            },
            onCopyHistory = { entry -> copyTranscript(entry) },
            onDeleteHistory = { entry -> scope.launch { transcriptRepository.delete(entry.id) } },
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

    private fun copyTranscript(entry: TranscriptEntry) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Wisperlow transcript", entry.text))
    }
}
