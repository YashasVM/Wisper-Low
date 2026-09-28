package com.wisperlow.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wisperlow.mobile.MainUiState
import com.wisperlow.mobile.MainViewModel
import com.wisperlow.mobile.R
import kotlinx.coroutines.launch

private enum class Tab(val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Rounded.Home),
    HISTORY(R.string.nav_history, Icons.Rounded.History),
    WORDS(R.string.nav_words, Icons.Rounded.Spellcheck),
    SETTINGS(R.string.nav_settings, Icons.Rounded.Settings),
}

@Composable
fun WisperlowRoot(state: MainUiState, level: Float, viewModel: MainViewModel, actions: AppActions) {
    var showGuide by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when {
            // The splash screen stays up until settings load, so nothing flashes.
            !state.settingsLoaded -> Box(Modifier.fillMaxSize())
            !state.settings.onboardingCompleted || showGuide -> OnboardingScreen(
                state = state,
                level = level,
                actions = actions,
                callbacks = OnboardingCallbacks(
                    onDownload = viewModel::downloadModel,
                    onCancelDownload = viewModel::cancelDownload,
                    onSelectModel = viewModel::selectModel,
                    onDeleteModel = viewModel::deleteModel,
                    onTogglePractice = viewModel::togglePractice,
                    onPracticeText = { viewModel.setPracticeText(it) },
                    onClearPractice = { viewModel.clearPractice() },
                    onFinish = {
                        viewModel.completeOnboarding()
                        if (state.setup.canRunBubble) actions.startBubble()
                        showGuide = false
                    },
                    onClose = if (state.settings.onboardingCompleted) ({ showGuide = false }) else null,
                ),
            )
            else -> MainTabs(state, level, viewModel, actions, onOpenGuide = { showGuide = true })
        }
    }
}

@Composable
private fun MainTabs(
    state: MainUiState,
    level: Float,
    viewModel: MainViewModel,
    actions: AppActions,
    onOpenGuide: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsList = rememberLazyListState()
    val context = LocalContext.current
    val tab = Tab.entries[tabIndex]

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.HOME -> HomeScreen(
                state = state,
                level = level,
                actions = actions,
                callbacks = HomeCallbacks(
                    onOpenGuide = onOpenGuide,
                    onSeeHistory = { tabIndex = Tab.HISTORY.ordinal },
                    onTogglePractice = viewModel::togglePractice,
                    onPracticeText = { viewModel.setPracticeText(it) },
                    onClearPractice = { viewModel.clearPractice() },
                    onOpenModels = {
                        tabIndex = Tab.SETTINGS.ordinal
                        scope.launch { settingsList.scrollToItem(SETTINGS_MODEL_ITEM) }
                    },
                ),
                contentPadding = padding,
            )
            Tab.HISTORY -> HistoryScreen(
                state = state,
                contentPadding = padding,
                onCopy = { actions.copyText(it.text) },
                onDelete = { entry ->
                    viewModel.deleteHistory(entry)
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar(
                            message = context.getString(R.string.history_deleted),
                            actionLabel = context.getString(R.string.action_undo),
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.restoreHistory(entry)
                    }
                },
                onClearAll = { viewModel.clearHistory() },
            )
            Tab.WORDS -> WordsScreen(
                words = state.settings.personalDictionary,
                contentPadding = padding,
                onSave = viewModel::saveWord,
                onDelete = { viewModel.deleteWord(it) },
            )
            Tab.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                actions = actions,
                listState = settingsList,
                contentPadding = padding,
                onOpenGuide = onOpenGuide,
                onPositionReset = {
                    viewModel.resetBubblePosition()
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_reset_position_done)) }
                },
            )
        }
    }
}
