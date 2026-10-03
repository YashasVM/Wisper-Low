package com.wisperlow.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.MainUiState
import com.wisperlow.mobile.R

class HomeCallbacks(
    val onOpenGuide: () -> Unit,
    val onSeeHistory: () -> Unit,
    val onTogglePractice: () -> Unit,
    val onPracticeText: (String) -> Unit,
    val onClearPractice: () -> Unit,
    val onOpenModels: () -> Unit,
)

@Composable
fun HomeScreen(
    state: MainUiState,
    level: Float,
    actions: AppActions,
    callbacks: HomeCallbacks,
    contentPadding: PaddingValues,
) {
    var tipsOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        item { BubbleStatusCard(state, actions, callbacks) }
        val fixes = fixesFor(state, actions, callbacks)
        if (fixes.isNotEmpty()) {
            item {
                SectionCard(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                    fixes.forEach { fix ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Sm)) {
                            Icon(Icons.Rounded.WarningAmber, contentDescription = null)
                            Text(stringResource(fix.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = fix.action) { Text(stringResource(R.string.action_fix)) }
                        }
                    }
                    TextButton(onClick = callbacks.onOpenGuide) { Text(stringResource(R.string.home_guide)) }
                }
            }
        }
        item {
            SectionCard {
                Text(stringResource(R.string.try_step_title), style = MaterialTheme.typography.titleLarge)
                PracticeArea(
                    practice = state.practice,
                    dictation = state.dictation,
                    level = level,
                    modelReady = state.setup.model && state.setup.microphone,
                    onToggle = callbacks.onTogglePractice,
                    onTextChange = callbacks.onPracticeText,
                    onClear = callbacks.onClearPractice,
                )
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_tips_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { tipsOpen = !tipsOpen }) {
                        Icon(
                            if (tipsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(R.string.home_tips_title),
                        )
                    }
                }
                if (tipsOpen) UsageTips()
            }
        }
        if (state.history.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = callbacks.onSeeHistory) { Text(stringResource(R.string.home_see_all)) }
                }
            }
            items(state.history.take(3), key = { it.id }) { entry ->
                HistoryRow(entry = entry, onCopy = { actions.copyText(entry.text) }, onDelete = null)
            }
        }
    }
}

@Composable
private fun BubbleStatusCard(state: MainUiState, actions: AppActions, callbacks: HomeCallbacks) {
    val on = state.bubbleRunning
    val canRun = state.setup.canRunBubble
    SectionCard(
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(
                if (on) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (on) R.string.home_on_title else R.string.home_off_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(
                        when {
                            on -> R.string.home_on_body
                            canRun -> R.string.home_off_body
                            else -> R.string.home_needs_setup
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (on) {
            OutlinedButton(
                onClick = actions.stopBubble,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(stringResource(R.string.home_turn_off)) }
        } else if (canRun) {
            Button(onClick = actions.startBubble, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_turn_on))
            }
        } else {
            Button(onClick = callbacks.onOpenGuide, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_guide))
            }
        }
    }
}

private class Fix(val label: Int, val action: () -> Unit)

private fun fixesFor(state: MainUiState, actions: AppActions, callbacks: HomeCallbacks): List<Fix> = buildList {
    val setup = state.setup
    if (!setup.model) add(Fix(R.string.home_fix_model, callbacks.onOpenModels))
    if (!setup.microphone) add(Fix(R.string.home_fix_mic, actions.requestMicrophone))
    if (!setup.overlay) add(Fix(R.string.home_fix_overlay, actions.openOverlaySettings))
    if (!setup.accessibility) add(Fix(R.string.home_fix_accessibility, actions.openAccessibilitySettings))
    if (!setup.notifications && setup.microphone) add(Fix(R.string.home_fix_notifications, actions.openAppInfo))
}

fun screenPadding(inner: PaddingValues): PaddingValues = PaddingValues(
    start = Space.Ml,
    end = Space.Ml,
    top = inner.calculateTopPadding() + 16.dp,
    bottom = inner.calculateBottomPadding() + 24.dp,
)
