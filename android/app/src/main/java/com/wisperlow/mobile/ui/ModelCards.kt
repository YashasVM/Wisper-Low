package com.wisperlow.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        Motion.tween(),
        label = "modelCard",
    )
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        Motion.tween(),
        label = "modelBorder",
    )
    val borderWidth by animateDpAsState(if (selected) 2.dp else 1.dp, Motion.spring(), label = "modelBorderW")
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = { if (installed && !selected) onSelect() },
        enabled = installed && !selected,
        interactionSource = source,
        modifier = Modifier.fillMaxWidth().pressScale(source),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(Modifier.animateContentSize(Motion.tween()).padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
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
                DownloadState.NotStarted -> PrimaryCta(stringResource(R.string.model_download), onDownload)
                is DownloadState.Downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
                        val determinate = state.totalBytes > 0 && !state.waitingForNetwork
                        DownloadRing(if (determinate) state.progressPct / 100f else null)
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
                        SecondaryCta(stringResource(R.string.model_use_mobile_data), onUseMobileData)
                    }
                }
                DownloadState.Extracting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.Sm),
                ) {
                    DownloadRing(null)
                    Text(stringResource(R.string.model_extracting), style = MaterialTheme.typography.bodyMedium)
                }
                is DownloadState.Completed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.model_in_use),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = Space.S).weight(1f),
                        )
                    } else {
                        Text(
                            stringResource(R.string.model_installed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryCta(stringResource(R.string.model_use), onSelect)
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
                    PrimaryCta(stringResource(R.string.model_retry), onDownload)
                }
            }
        }
    }
}

/** Circular download ring; progress glides between updates, null spins while the size is unknown. */
@Composable
private fun DownloadRing(progress: Float?) {
    val shown by animateFloatAsState(progress ?: 0f, Motion.slow(), label = "downloadRing")
    val modifier = Modifier.size(Space.Xl + Space.S)
    if (progress == null) {
        CircularProgressIndicator(modifier = modifier, strokeWidth = Space.Xs)
    } else {
        CircularProgressIndicator(
            progress = { shown.coerceAtLeast(0.02f) },
            modifier = modifier,
            strokeWidth = Space.Xs,
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun Chip(text: String, highlighted: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Space.S, vertical = Space.Xs))
    }
}
