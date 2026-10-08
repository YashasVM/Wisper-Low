package com.wisperlow.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.BuildConfig
import com.wisperlow.mobile.MainUiState
import com.wisperlow.mobile.MainViewModel
import com.wisperlow.mobile.R
import com.wisperlow.mobile.settings.AutoStop
import com.wisperlow.mobile.settings.KeyboardPrefs

/** Index of the speech model section, so other screens can scroll straight to it. */
const val SETTINGS_MODEL_ITEM = 5

@Composable
fun SettingsScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    actions: AppActions,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onOpenGuide: () -> Unit,
) {
    val settings = state.settings
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Space.Sm),
    ) {
        item { ScreenTitle(stringResource(R.string.settings_title)) }

        item { SectionHeader(stringResource(R.string.settings_section_keyboard)) }
        item {
            SectionCard {
                LinkRow(
                    stringResource(R.string.settings_keyboard_list),
                    stringResource(
                        if (state.setup.keyboardEnabled) R.string.settings_keyboard_list_on else R.string.settings_keyboard_list_off,
                    ),
                    actions.openKeyboardSettings,
                )
                HorizontalDivider()
                LinkRow(stringResource(R.string.settings_keyboard_pick), null, actions.pickKeyboard)
                HorizontalDivider()
                val kb = settings.keyboard
                SwitchRow(
                    title = stringResource(R.string.settings_typing_autocorrect),
                    body = stringResource(R.string.settings_typing_autocorrect_body),
                    checked = kb.autoCorrect,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(autoCorrect = on) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_typing_autocap),
                    body = null,
                    checked = kb.autoCapitalize,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(autoCapitalize = on) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_typing_double_space),
                    body = null,
                    checked = kb.doubleSpacePeriod,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(doubleSpacePeriod = on) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_typing_number_row),
                    body = null,
                    checked = kb.numberRow,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(numberRow = on) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_typing_haptics),
                    body = null,
                    checked = kb.haptics,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(haptics = on) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_typing_sound),
                    body = null,
                    checked = kb.sound,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(sound = on) } },
                )
                Text(stringResource(R.string.settings_typing_height), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = kb.heightScale,
                    onValueChange = { v -> viewModel.updateKeyboard { it.copy(heightScale = v) } },
                    valueRange = KeyboardPrefs.MIN_HEIGHT_SCALE..KeyboardPrefs.MAX_HEIGHT_SCALE,
                )
                HorizontalDivider()
                val context = LocalContext.current
                val cleared = stringResource(R.string.settings_forget_words_done)
                LinkRow(stringResource(R.string.settings_forget_words), stringResource(R.string.settings_forget_words_body)) {
                    viewModel.forgetLearnedWords()
                    Toast.makeText(context, cleared, Toast.LENGTH_SHORT).show()
                }
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_dictation)) }
        item {
            SectionCard {
                Text(stringResource(R.string.settings_auto_stop), style = MaterialTheme.typography.titleMedium)
                AutoStop.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .selectable(
                                selected = settings.autoStop == option,
                                role = Role.RadioButton,
                                onClick = { viewModel.setAutoStop(option) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.autoStop == option, onClick = null)
                        Text(stringResource(autoStopLabel(option)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_review),
                    body = stringResource(R.string.settings_review_body),
                    checked = settings.reviewBeforeInsert,
                    onCheckedChange = { viewModel.setReviewBeforeInsert(it) },
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_live_preview),
                    body = stringResource(R.string.settings_live_preview_body),
                    checked = settings.keyboard.livePreview,
                    onCheckedChange = { on -> viewModel.updateKeyboard { it.copy(livePreview = on) } },
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_keep_loaded),
                    body = stringResource(R.string.settings_keep_loaded_body),
                    checked = settings.keepModelLoaded,
                    onCheckedChange = { viewModel.setKeepModelLoaded(it) },
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_model)) }
        item {
            ModelList(
                selectedId = settings.selectedModelId,
                downloads = state.downloads,
                actions = actions,
                onDownload = viewModel::downloadModel,
                onCancel = viewModel::cancelDownload,
                onSelect = viewModel::selectModel,
                onDelete = viewModel::deleteModel,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_section_privacy)) }
        item {
            SectionCard {
                SwitchRow(
                    title = stringResource(R.string.settings_history),
                    body = stringResource(R.string.settings_history_body),
                    checked = settings.historyEnabled,
                    onCheckedChange = { viewModel.setHistoryEnabled(it) },
                )
                Text(
                    stringResource(R.string.settings_privacy_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionHeader(stringResource(R.string.settings_section_help)) }
        item {
            SectionCard {
                LinkRow(stringResource(R.string.settings_guide), stringResource(R.string.settings_guide_body), onOpenGuide)
                HorizontalDivider()
                LinkRow(stringResource(R.string.settings_permissions), null, actions.openAppInfo)
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, body: String?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(onClick = onClick, interactionSource = source, color = Color.Transparent, modifier = Modifier.pressScale(source, 0.98f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private fun autoStopLabel(value: AutoStop): Int = when (value) {
    AutoStop.SHORT -> R.string.auto_stop_short
    AutoStop.NORMAL -> R.string.auto_stop_normal
    AutoStop.RELAXED -> R.string.auto_stop_relaxed
    AutoStop.LONG -> R.string.auto_stop_long
    AutoStop.MANUAL -> R.string.auto_stop_manual
}
