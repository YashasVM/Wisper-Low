package com.wisperlow.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.R
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.ModelCatalog
import com.wisperlow.mobile.stt.ModelKind
import com.wisperlow.mobile.stt.SttModel

@Composable
fun ModelList(
    selectedId: String,
    downloads: Map<String, DownloadState>,
    actions: AppActions,
    onDownload: (SttModel, Boolean) -> Unit,
    onCancel: (SttModel) -> Unit,
    onSelect: (SttModel) -> Unit,
    onDelete: (SttModel) -> Unit,
    models: List<SttModel> = ModelCatalog.all,
) {
    var meteredPrompt by remember { mutableStateOf<SttModel?>(null) }
    var deletePrompt by remember { mutableStateOf<SttModel?>(null) }
    val anyInstalled = downloads.values.any { it is DownloadState.Completed }

    Column(verticalArrangement = Arrangement.spacedBy(Space.Sm)) {
        models.forEach { model ->
            ModelCard(
                model = model,
                state = downloads[model.id] ?: DownloadState.NotStarted,
                selected = model.id == selectedId && anyInstalled,
                recommended = model == ModelCatalog.DEFAULT,
                onDownload = {
                    if (actions.isMetered()) meteredPrompt = model else onDownload(model, false)
                },
                onUseMobileData = { onDownload(model, true) },
                onCancel = { onCancel(model) },
                onSelect = { onSelect(model) },
                onDelete = { deletePrompt = model },
            )
        }
    }

    meteredPrompt?.let { model ->
        AlertDialog(
            onDismissRequest = { meteredPrompt = null },
            title = { Text(stringResource(R.string.metered_title)) },
            text = { Text(stringResource(R.string.metered_body, model.sizeHintMb)) },
            confirmButton = {
                TextButton(onClick = {
                    meteredPrompt = null
                    onDownload(model, true)
                }) { Text(stringResource(R.string.metered_now)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    meteredPrompt = null
                    onDownload(model, false)
                }) { Text(stringResource(R.string.metered_wait)) }
            },
        )
    }
    deletePrompt?.let { model ->
        AlertDialog(
            onDismissRequest = { deletePrompt = null },
            title = { Text(stringResource(R.string.model_delete_title, model.displayName)) },
            text = { Text(stringResource(R.string.model_delete_body, model.sizeHintMb)) },
            confirmButton = {
                TextButton(onClick = {
                    deletePrompt = null
                    onDelete(model)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePrompt = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ModelCard(
    model: SttModel,
    state: DownloadState,
    selected: Boolean,
    recommended: Boolean,
    onDownload: () -> Unit,
    onUseMobileData: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val installed = state is DownloadState.Completed
    OutlinedCard(
        onClick = { if (installed && !selected) onSelect() },
        enabled = installed && !selected,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            disabledContainerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            disabledContentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(model.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Chip(stringResource(R.string.model_size_mb, model.sizeHintMb))
                if (recommended) Chip(stringResource(R.string.model_recommended), highlighted = true)
            }
            Text(
                stringResource(
                    when (model.kind) {
                        ModelKind.MULTILINGUAL_ACCURATE -> R.string.model_kind_multilingual
                        ModelKind.ENGLISH_ACCURATE -> R.string.model_kind_english
                        ModelKind.ENGLISH_FAST -> R.string.model_kind_fast
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                DownloadState.NotStarted -> Button(onClick = onDownload) { Text(stringResource(R.string.model_download)) }
                is DownloadState.Downloading -> {
                    if (state.totalBytes > 0 && !state.waitingForNetwork) {
                        LinearProgressIndicator(progress = { state.progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                state.waitingForNetwork -> stringResource(R.string.model_waiting_wifi)
                                state.totalBytes > 0 -> stringResource(R.string.model_downloading, state.progressPct)
                                else -> stringResource(R.string.model_downloading_indeterminate)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.model_cancel_download)) }
                    }
                    if (state.waitingForNetwork) {
                        FilledTonalButton(onClick = onUseMobileData) { Text(stringResource(R.string.model_use_mobile_data)) }
                    }
                }
                DownloadState.Extracting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.model_extracting), style = MaterialTheme.typography.bodyMedium)
                }
                is DownloadState.Completed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.model_in_use),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp).weight(1f),
                        )
                    } else {
                        Text(
                            stringResource(R.string.model_installed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = onSelect) { Text(stringResource(R.string.model_use)) }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.model_delete))
                    }
                }
                is DownloadState.Failed -> {
                    Text(
                        stringResource(R.string.model_failed, state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload) { Text(stringResource(R.string.model_retry)) }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, highlighted: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Space.S, vertical = 3.dp))
    }
}
