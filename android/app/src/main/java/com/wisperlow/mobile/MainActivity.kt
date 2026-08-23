package com.wisperlow.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wisperlow.mobile.accessibility.WisperlowAccessibilityService
import com.wisperlow.mobile.service.DictationService
import com.wisperlow.mobile.settings.SettingsRepository
import com.wisperlow.mobile.settings.WisperlowSettings
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.ModelCatalog
import com.wisperlow.mobile.stt.ModelDownloader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var modelDownloader: ModelDownloader
    @Inject lateinit var settingsRepository: SettingsRepository

    private val permRefresh = mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permRefresh.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
        requestCorePermissions()
    }

    override fun onResume() {
        super.onResume()
        permRefresh.intValue++
    }

    private fun requestCorePermissions() {
        permissionLauncher.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray(),
        )
    }

    @Composable
    private fun MainScreen() {
        var settings by remember { mutableStateOf(WisperlowSettings()) }
        var downloadStates by remember { mutableStateOf(mapOf<String, DownloadState>()) }
        var dictionaryText by remember { mutableStateOf("") }
        val serviceRunning by DictationService.running.collectAsState()
        val scope = rememberCoroutineScope()
        val refresh = permRefresh.intValue

        LaunchedEffect(Unit) {
            settingsRepository.settings.collect {
                settings = it
                if (dictionaryText.isEmpty()) {
                    dictionaryText = it.personalDictionary.entries.joinToString("\n") { (k, v) -> "$k=$v" }
                }
            }
        }
        LaunchedEffect(Unit) {
            ModelCatalog.all.forEach { model ->
                val dir = modelDownloader.installedDirFor(model.id) ?: return@forEach
                downloadStates = downloadStates + (model.id to DownloadState.Completed(dir))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Wisperlow Mobile", style = MaterialTheme.typography.headlineMedium)

            PermissionCard(refresh)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Speech models", style = MaterialTheme.typography.titleMedium)
                    ModelCatalog.all.forEach { model ->
                        val state = downloadStates[model.id]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.selectedModelId == model.id,
                                onClick = {
                                    scope.launch { settingsRepository.setSelectedModel(model.id) }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text("${model.displayName} (~${model.sizeHintMb}MB)")
                                when (val s = state) {
                                    is DownloadState.Downloading -> {
                                        LinearProgressIndicator(
                                            progress = { s.progressPct / 100f },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    DownloadState.Extracting -> Text("Extracting…")
                                    is DownloadState.Completed -> {
                                        Text("Installed", style = MaterialTheme.typography.bodySmall)
                                    }
                                    is DownloadState.Failed -> Text(
                                        "Failed: ${s.message}",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    DownloadState.NotStarted -> Unit
                                    null -> Unit
                                }
                            }
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        downloadStates =
                                            downloadStates +
                                                (model.id to DownloadState.Downloading(0))
                                        val id = modelDownloader.enqueue(model).getOrThrow()
                                        val dir = modelDownloader.awaitAndExtract(id, model)
                                        downloadStates =
                                            downloadStates + (model.id to DownloadState.Completed(dir))
                                    } catch (t: Throwable) {
                                        downloadStates = downloadStates +
                                            (model.id to DownloadState.Failed(t.message ?: "error"))
                                    }
                                }
                            }) {
                                Text(if (state is DownloadState.Completed) "Redownload" else "Download")
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bubble", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = settings.bubbleEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch { settingsRepository.setBubbleEnabled(enabled) }
                            },
                        )
                        Text("Show dictation bubble")
                    }
                    Button(
                        onClick = {
                            if (serviceRunning) {
                                DictationService.stop(this@MainActivity)
                            } else {
                                DictationService.start(this@MainActivity)
                            }
                        },
                    ) {
                        Text(if (serviceRunning) "Stop bubble service" else "Start bubble service")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Personal dictionary", style = MaterialTheme.typography.titleMedium)
                    Text("One entry per line: spoken=replace")
                    OutlinedTextField(
                        value = dictionaryText,
                        onValueChange = { text ->
                            dictionaryText = text
                            scope.launch {
                                settingsRepository.setPersonalDictionary(
                                    SettingsRepository.parseDictionary(text),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }
        }
    }

    @Composable
    private fun PermissionCard(refreshKey: Int) {
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        val a11yEnabled = WisperlowAccessibilityService.isReady

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                PermissionRow("Microphone", micOk) {
                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }
                PermissionRow("Display over other apps", overlayOk) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                }
                PermissionRow("Accessibility service", a11yEnabled) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, action: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            if (granted) {
                Text("Granted", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = action) { Text("Enable") }
            }
        }
    }
}
