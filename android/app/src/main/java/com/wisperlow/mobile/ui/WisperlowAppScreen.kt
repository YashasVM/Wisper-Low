package com.wisperlow.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.R
import com.wisperlow.mobile.history.TranscriptEntry
import com.wisperlow.mobile.service.DictationPhase
import com.wisperlow.mobile.settings.WisperlowSettings
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.ModelCatalog
import com.wisperlow.mobile.stt.SttModel

data class SetupState(
    val microphoneReady: Boolean,
    val overlayReady: Boolean,
    val accessibilityReady: Boolean,
    val modelReady: Boolean,
) {
    val completedCount: Int
        get() = listOf(microphoneReady, overlayReady, accessibilityReady, modelReady).count { it }

    /** Accessibility improves insertion into focused fields but clipboard fallback works without it. */
    val isReady: Boolean
        get() = microphoneReady && overlayReady && modelReady

    companion object {
        const val TOTAL_STEPS = 4
    }
}

private enum class AppTab(val labelRes: Int) {
    Home(R.string.nav_home),
    Dictionary(R.string.nav_dictionary),
    Settings(R.string.nav_settings),
}

@Composable
fun WisperlowAppScreen(
    settings: WisperlowSettings,
    setup: SetupState,
    serviceRunning: Boolean,
    servicePhase: DictationPhase = DictationPhase.Idle,
    downloadStates: Map<String, DownloadState>,
    dictionaryText: String,
    history: List<TranscriptEntry> = emptyList(),
    onRequestMicrophone: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onDownloadModel: (SttModel) -> Unit,
    onSelectModel: (SttModel) -> Unit = {},
    onToggleService: () -> Unit,
    onBubbleEnabledChange: (Boolean) -> Unit,
    onDictionaryTextChange: (String) -> Unit,
    onCopyHistory: (TranscriptEntry) -> Unit = {},
    onDeleteHistory: (TranscriptEntry) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tab = AppTab.entries[selectedTab]

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AppBottomBar(selectedTab = selectedTab, onSelect = { selectedTab = it })
        },
    ) { contentPadding ->
        when (tab) {
            AppTab.Home -> HomeScreen(
                setup = setup,
                serviceRunning = serviceRunning,
                servicePhase = servicePhase,
                modelDownloadState = downloadStates[settings.selectedModelId],
                onRequestMicrophone = onRequestMicrophone,
                onRequestOverlay = onRequestOverlay,
                onRequestAccessibility = onRequestAccessibility,
                onDownloadModel = {
                    onDownloadModel(
                        ModelCatalog.byId(settings.selectedModelId)
                            ?: ModelCatalog.PARAKEET_V3_INT8,
                    )
                },
                onToggleService = onToggleService,
                history = history,
                onCopyHistory = onCopyHistory,
                onDeleteHistory = onDeleteHistory,
                modifier = Modifier.padding(contentPadding),
            )
            AppTab.Dictionary -> DictionaryScreen(
                dictionaryText = dictionaryText,
                onDictionaryTextChange = onDictionaryTextChange,
                modifier = Modifier.padding(contentPadding),
            )
            AppTab.Settings -> SettingsScreen(
                settings = settings,
                downloadStates = downloadStates,
                onBubbleEnabledChange = onBubbleEnabledChange,
                onDownloadModel = onDownloadModel,
                onSelectModel = onSelectModel,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

@Composable
private fun HomeScreen(
    setup: SetupState,
    serviceRunning: Boolean,
    servicePhase: DictationPhase,
    modelDownloadState: DownloadState?,
    onRequestMicrophone: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onDownloadModel: () -> Unit,
    onToggleService: () -> Unit,
    history: List<TranscriptEntry>,
    onCopyHistory: (TranscriptEntry) -> Unit,
    onDeleteHistory: (TranscriptEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenList(modifier) {
        item { BrandHeader() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.local_dictation_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!setup.isReady) {
            item {
                SetupCard(
                    setup = setup,
                    modelDownloadState = modelDownloadState,
                    onRequestMicrophone = onRequestMicrophone,
                    onRequestOverlay = onRequestOverlay,
                    onRequestAccessibility = onRequestAccessibility,
                    onDownloadModel = onDownloadModel,
                )
            }
        }
        item {
            DictationCard(
                setupReady = setup.isReady,
                serviceRunning = serviceRunning,
                servicePhase = servicePhase,
                onToggleService = onToggleService,
            )
        }
        item { PrivacyCard() }
        item {
            Text(
                text = stringResource(R.string.recent_dictations),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        if (history.isEmpty()) {
            item { EmptyHistoryCard() }
        } else {
            items(history, key = { it.id }) { entry ->
                HistoryCard(
                    entry = entry,
                    onCopy = { onCopyHistory(entry) },
                    onDelete = { onDeleteHistory(entry) },
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: TranscriptEntry,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.history_words, entry.wordCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCopy) {
                        Text(stringResource(R.string.history_copy))
                    }
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.history_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(
    setup: SetupState,
    modelDownloadState: DownloadState?,
    onRequestMicrophone: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onDownloadModel: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(
                        R.string.setup_progress,
                        setup.completedCount,
                        SetupState.TOTAL_STEPS,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { setup.completedCount / SetupState.TOTAL_STEPS.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
            SetupRow(
                title = stringResource(R.string.setup_microphone),
                detail = stringResource(R.string.setup_microphone_detail),
                actionLabel = stringResource(R.string.action_allow),
                complete = setup.microphoneReady,
                onAction = onRequestMicrophone,
            )
            SetupRow(
                title = stringResource(R.string.setup_overlay),
                detail = stringResource(R.string.setup_overlay_detail),
                actionLabel = stringResource(R.string.action_open),
                complete = setup.overlayReady,
                onAction = onRequestOverlay,
            )
            SetupRow(
                title = stringResource(R.string.setup_accessibility),
                detail = stringResource(R.string.setup_accessibility_detail),
                actionLabel = stringResource(R.string.action_open),
                complete = setup.accessibilityReady,
                onAction = onRequestAccessibility,
            )
            SetupRow(
                title = stringResource(R.string.setup_model),
                detail = stringResource(R.string.setup_model_detail),
                actionLabel = stringResource(
                    if (modelDownloadState is DownloadState.Failed) {
                        R.string.action_redownload
                    } else {
                        R.string.action_download
                    },
                ),
                complete = setup.modelReady,
                busy = modelDownloadState.isDownloadInProgress(),
                onAction = onDownloadModel,
            )
            if (modelDownloadState.isDownloadInProgress()) {
                modelDownloadState?.let { ModelDownloadProgress(it) }
            } else if (modelDownloadState is DownloadState.Failed) {
                Text(
                    text = stringResource(
                        R.string.model_download_failed,
                        modelDownloadState.message,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    detail: String,
    actionLabel: String,
    complete: Boolean,
    busy: Boolean = false,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMark(complete)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (complete) {
            Text(
                text = stringResource(R.string.status_ready),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else if (busy) {
            Text(
                text = stringResource(R.string.model_downloading_short),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun DictationCard(
    setupReady: Boolean,
    serviceRunning: Boolean,
    servicePhase: DictationPhase,
    onToggleService: () -> Unit,
) {
    val active = setupReady && serviceRunning
    val starting = servicePhase is DictationPhase.Initializing
    val serviceError = (servicePhase as? DictationPhase.Error)?.message
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = if (active) 0.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowMark(inverted = active)
                Spacer(Modifier.width(12.dp))
                Surface(
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    shape = CircleShape,
                ) {
                    Text(
                        text = stringResource(R.string.on_device_badge),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Text(
                text = stringResource(
                    if (active) R.string.bubble_ready_title else R.string.bubble_paused_title,
                ),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (serviceError != null) {
                Text(
                    text = stringResource(R.string.dictation_error, serviceError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(
                    if (active) R.string.bubble_ready_body else R.string.bubble_paused_body,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (active) {
                OutlinedButton(
                    onClick = onToggleService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.action_pause))
                }
            } else {
                Button(
                    onClick = onToggleService,
                    enabled = setupReady && !starting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                ) {
                    Text(
                        stringResource(
                            if (starting) R.string.action_starting else R.string.action_turn_on,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivacyCard() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrivacyGlyph()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.privacy_card_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.privacy_card_body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.recent_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.recent_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DictionaryScreen(
    dictionaryText: String,
    onDictionaryTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entryCount = remember(dictionaryText) {
        dictionaryText.lineSequence().count { it.contains('=') }
    }
    ScreenList(modifier) {
        item { BrandHeader() }
        item {
            ScreenTitle(
                title = stringResource(R.string.dictionary_title),
                subtitle = stringResource(R.string.dictionary_subtitle),
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.dictionary_card_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.dictionary_count, entryCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        stringResource(R.string.dictionary_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = dictionaryText,
                        onValueChange = onDictionaryTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dictionary_label)) },
                        placeholder = { Text(stringResource(R.string.dictionary_example)) },
                        minLines = 7,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.dictionary_tip_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.dictionary_tip_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    settings: WisperlowSettings,
    downloadStates: Map<String, DownloadState>,
    onBubbleEnabledChange: (Boolean) -> Unit,
    onDownloadModel: (SttModel) -> Unit,
    onSelectModel: (SttModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenList(modifier) {
        item { BrandHeader() }
        item {
            ScreenTitle(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
            )
        }
        item {
            SettingsToggleCard(
                title = stringResource(R.string.bubble_setting_title),
                body = stringResource(R.string.bubble_setting_body),
                checked = settings.bubbleEnabled,
                onCheckedChange = onBubbleEnabledChange,
            )
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.speech_model_title), style = MaterialTheme.typography.titleLarge)
                    ModelCatalog.all.forEachIndexed { index, model ->
                        if (index > 0) HorizontalDivider()
                        ModelRow(
                            model = model,
                            state = downloadStates[model.id],
                            onDownload = { onDownloadModel(model) },
                            selected = settings.selectedModelId == model.id,
                            onSelect = { onSelectModel(model) },
                        )
                    }
                }
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.local_storage_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.local_storage_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: SttModel,
    state: DownloadState?,
    onDownload: () -> Unit,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val installed = state is DownloadState.Completed
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.model_size, model.sizeHintMb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                state is DownloadState.Downloading || state is DownloadState.Extracting -> {
                    if (state is DownloadState.Downloading && state.totalBytes > 0L) {
                        Text(
                            text = stringResource(R.string.model_progress_percentage, state.progressPct),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                }
                selected && installed -> Text(
                    stringResource(R.string.model_active),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelLarge,
                )
                installed -> OutlinedButton(onClick = onSelect) {
                    Text(stringResource(R.string.action_select))
                }
                else -> OutlinedButton(onClick = onDownload) {
                    Text(
                        stringResource(R.string.action_download),
                    )
                }
            }
        }
        when (state) {
            is DownloadState.Downloading, DownloadState.Extracting -> ModelDownloadProgress(state)
            is DownloadState.Failed -> Text(
                stringResource(R.string.model_download_failed, state.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is DownloadState.Completed -> Text(
                stringResource(R.string.model_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            DownloadState.NotStarted, null -> Unit
        }
    }
}

private fun DownloadState?.isDownloadInProgress(): Boolean =
    this is DownloadState.Downloading || this is DownloadState.Extracting

@Composable
private fun ModelDownloadProgress(state: DownloadState) {
    when (state) {
        is DownloadState.Downloading -> {
            val progress = if (state.totalBytes > 0L) {
                state.downloadedBytes.toFloat() / state.totalBytes.toFloat()
            } else {
                null
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.model_downloading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    if (progress != null) {
                        Text(
                            text = stringResource(R.string.model_progress_percentage, state.progressPct),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                if (progress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        DownloadState.Extracting -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.model_extracting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        else -> Unit
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AppBottomBar(selectedTab: Int, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
            AppTab.entries.forEachIndexed { index, tab ->
                val label = stringResource(tab.labelRes)
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = { onSelect(index) },
                    icon = { NavGlyph(tab, Modifier.clearAndSetSemantics { }) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowMark()
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.app_name).lowercase(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenList(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 20.dp,
                end = 20.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun StatusMark(complete: Boolean) {
    val readyLabel = stringResource(
        if (complete) R.string.status_ready else R.string.status_needs_setup,
    )
    val color = if (complete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = readyLabel },
    ) {
        drawCircle(color = color.copy(alpha = if (complete) 0.16f else 0.10f))
        drawCircle(color = color, style = Stroke(width = 2.dp.toPx()))
        if (complete) {
            drawLine(
                color = color,
                start = Offset(size.width * 0.28f, size.height * 0.52f),
                end = Offset(size.width * 0.44f, size.height * 0.68f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width * 0.44f, size.height * 0.68f),
                end = Offset(size.width * 0.74f, size.height * 0.34f),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun FlowMark(inverted: Boolean = false) {
    val background = if (inverted) MaterialTheme.colorScheme.onPrimary else WisperlowColors.Violet
    val foreground = if (inverted) MaterialTheme.colorScheme.primary else Color.White
    Canvas(
        modifier = Modifier
            .size(36.dp)
            .clearAndSetSemantics { },
    ) {
        drawCircle(background)
        val barWidth = 2.5.dp.toPx()
        val center = size.height / 2f
        listOf(0.28f, 0.44f, 0.56f, 0.72f).forEachIndexed { index, x ->
            val halfHeight = if (index == 1 || index == 2) size.height * 0.18f else size.height * 0.10f
            drawLine(
                color = foreground,
                start = Offset(size.width * x, center - halfHeight),
                end = Offset(size.width * x, center + halfHeight),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun PrivacyGlyph() {
    val color = MaterialTheme.colorScheme.onSecondaryContainer
    Canvas(modifier = Modifier.size(32.dp).clearAndSetSemantics { }) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.2f, size.height * 0.38f),
            size = Size(size.width * 0.6f, size.height * 0.46f),
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.31f, size.height * 0.12f),
            size = Size(size.width * 0.38f, size.height * 0.48f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun NavGlyph(tab: AppTab, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        when (tab) {
            AppTab.Home -> {
                val roof = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.48f)
                    lineTo(size.width * 0.5f, size.height * 0.18f)
                    lineTo(size.width * 0.84f, size.height * 0.48f)
                }
                drawPath(roof, color, style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.25f, size.height * 0.43f),
                    size = Size(size.width * 0.5f, size.height * 0.4f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
            }
            AppTab.Dictionary -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.15f, size.height * 0.18f),
                    size = Size(size.width * 0.7f, size.height * 0.66f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.5f, size.height * 0.2f),
                    end = Offset(size.width * 0.5f, size.height * 0.82f),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            AppTab.Settings -> {
                listOf(0.28f, 0.5f, 0.72f).forEachIndexed { index, y ->
                    drawLine(
                        color = color,
                        start = Offset(size.width * 0.16f, size.height * y),
                        end = Offset(size.width * 0.84f, size.height * y),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    val x = if (index == 1) 0.66f else 0.38f
                    drawCircle(
                        color = surface,
                        radius = 3.5.dp.toPx(),
                        center = Offset(size.width * x, size.height * y),
                    )
                    drawCircle(
                        color = color,
                        radius = 3.5.dp.toPx(),
                        center = Offset(size.width * x, size.height * y),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
    }
}
