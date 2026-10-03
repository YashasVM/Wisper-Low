package com.wisperlow.mobile.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.MainUiState
import com.wisperlow.mobile.R
import com.wisperlow.mobile.history.TranscriptEntry

@Composable
fun HistoryScreen(
    state: MainUiState,
    contentPadding: PaddingValues,
    onCopy: (TranscriptEntry) -> Unit,
    onDelete: (TranscriptEntry) -> Unit,
    onClearAll: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val filtered = remember(state.history, query) {
        if (query.isBlank()) state.history else state.history.filter { it.text.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Space.Sm),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.animateItem()) {
                ScreenTitle(stringResource(R.string.history_title), Modifier.weight(1f))
                if (state.history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear_all)) }
                }
            }
        }
        if (!state.settings.historyEnabled) {
            item {
                SectionCard(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Text(stringResource(R.string.history_off), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.history.size > 4) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.history_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        }
        when {
            state.history.isEmpty() -> item {
                EmptyState(Icons.Rounded.History, R.string.history_empty_title, R.string.history_empty_body)
            }
            filtered.isEmpty() -> item {
                Text(
                    stringResource(R.string.history_no_results, query.trim()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.S),
                )
            }
            else -> items(filtered, key = { it.id }) { entry ->
                HistoryRow(entry = entry, onCopy = { onCopy(entry) }, onDelete = { onDelete(entry) }, modifier = Modifier.animateItem())
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAll()
                }) { Text(stringResource(R.string.history_clear_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun HistoryRow(entry: TranscriptEntry, onCopy: () -> Unit, onDelete: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = Space.M, top = Space.Sm, end = Space.Xs, bottom = Space.Xs)) {
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = Space.Sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateUtils.getRelativeTimeSpanString(entry.timestampMillis).toString() + " · " +
                        pluralStringResource(R.plurals.history_words, entry.wordCount, entry.wordCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int) {
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(!Motion.enabled) }
    LaunchedEffect(Unit) { shown.targetState = true }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = shown,
        enter = androidx.compose.animation.fadeIn(Motion.slow()) + androidx.compose.animation.slideInVertically(Motion.slow()) { it / 8 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Space.Xl, horizontal = Space.L),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            HeroIcon(icon)
            Spacer(Modifier.height(Space.Xs))
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
