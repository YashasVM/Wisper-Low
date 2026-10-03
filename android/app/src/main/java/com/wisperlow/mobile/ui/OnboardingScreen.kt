package com.wisperlow.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.MainUiState
import com.wisperlow.mobile.R
import com.wisperlow.mobile.stt.DownloadState
import com.wisperlow.mobile.stt.SttModel
import kotlinx.coroutines.delay

private enum class GuideStep { WELCOME, HOW, MODEL, MICROPHONE, OVERLAY, ACCESSIBILITY, TRY, DONE }

class OnboardingCallbacks(
    val onDownload: (SttModel, Boolean) -> Unit,
    val onCancelDownload: (SttModel) -> Unit,
    val onSelectModel: (SttModel) -> Unit,
    val onDeleteModel: (SttModel) -> Unit,
    val onTogglePractice: () -> Unit,
    val onPracticeText: (String) -> Unit,
    val onClearPractice: () -> Unit,
    val onFinish: () -> Unit,
    val onClose: (() -> Unit)?,
)

@Composable
fun OnboardingScreen(
    state: MainUiState,
    level: Float,
    actions: AppActions,
    callbacks: OnboardingCallbacks,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var micAsked by rememberSaveable { mutableStateOf(false) }
    val steps = GuideStep.entries
    val step = steps[index]
    val setup = state.setup
    val next = { index = (index + 1).coerceAtMost(steps.lastIndex) }

    BackHandler(enabled = index > 0 || callbacks.onClose != null) {
        if (index > 0) index-- else callbacks.onClose?.invoke()
    }

    // Returning from a system settings screen with the switch on moves ahead by itself.
    AutoAdvance(step == GuideStep.MICROPHONE, setup.microphone, next)
    AutoAdvance(step == GuideStep.OVERLAY, setup.overlay, next)
    AutoAdvance(step == GuideStep.ACCESSIBILITY, setup.accessibility, next)

    val modelStarted = state.downloads.values.any {
        it is DownloadState.Completed || it is DownloadState.Downloading || it is DownloadState.Extracting
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = Space.L, vertical = Space.Sm), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.onboarding_step, index + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                callbacks.onClose?.let { close ->
                    TextButton(onClick = close) { Text(stringResource(R.string.action_done)) }
                }
            }
            val progress by androidx.compose.animation.core.animateFloatAsState(
                (index + 1f) / steps.size,
                animationSpec = Motion.slow(),
                label = "guideProgress",
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (
                    slideInHorizontally(Motion.tween()) { if (forward) it / 6 else -it / 6 } +
                        fadeIn(Motion.tween(), initialAlpha = 0f)
                    ) togetherWith
                    (
                        slideOutHorizontally(Motion.fade()) { if (forward) -it / 8 else it / 8 } +
                            fadeOut(Motion.fade())
                        ) using androidx.compose.animation.SizeTransform(clip = false)
            },
            modifier = Modifier.weight(1f),
            label = "guideStep",
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.L, vertical = Space.Sm),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when (current) {
                    GuideStep.WELCOME -> WelcomeStep()
                    GuideStep.HOW -> HowStep()
                    GuideStep.MODEL -> ModelStep(state, actions, callbacks)
                    GuideStep.MICROPHONE -> MicrophoneStep(setup.microphone, micAsked, actions)
                    GuideStep.OVERLAY -> OverlayStep(setup.overlay)
                    GuideStep.ACCESSIBILITY -> AccessibilityStep(setup.accessibility, actions)
                    GuideStep.TRY -> TryStep(state, level, callbacks)
                    GuideStep.DONE -> DoneStep(setup.canRunBubble)
                }
            }
        }

        // Bottom bar: one clear primary action per step.
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.L, vertical = Space.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            if (index > 0) {
                TextButton(onClick = { index-- }) { Text(stringResource(R.string.action_back)) }
            }
            Spacer(Modifier.weight(1f))
            when (step) {
                GuideStep.WELCOME -> PrimaryButton(R.string.welcome_start, next)
                GuideStep.MODEL -> PrimaryButton(R.string.action_next, next, enabled = modelStarted)
                GuideStep.MICROPHONE -> if (setup.microphone) {
                    PrimaryButton(R.string.action_next, next)
                } else {
                    PrimaryButton(R.string.action_allow, {
                        micAsked = true
                        actions.requestMicrophone()
                    })
                }
                GuideStep.OVERLAY -> if (setup.overlay) {
                    PrimaryButton(R.string.action_next, next)
                } else {
                    PrimaryButton(R.string.action_open_settings, actions.openOverlaySettings)
                }
                GuideStep.ACCESSIBILITY -> if (setup.accessibility) {
                    PrimaryButton(R.string.action_next, next)
                } else {
                    TextButton(onClick = next) { Text(stringResource(R.string.action_skip)) }
                    PrimaryButton(R.string.action_open_settings, actions.openAccessibilitySettings)
                }
                GuideStep.DONE -> PrimaryButton(R.string.done_start, callbacks.onFinish)
                else -> PrimaryButton(R.string.action_next, next)
            }
        }
    }
}

/** Moves on only when [granted] flips to true while its step is showing, never on Back. */
@Composable
private fun AutoAdvance(onStep: Boolean, granted: Boolean, advance: () -> Unit) {
    var previous by remember { mutableStateOf(granted) }
    LaunchedEffect(granted) {
        val justGranted = granted && !previous
        previous = granted
        if (justGranted && onStep) {
            delay(700)
            advance()
        }
    }
}

@Composable
private fun PrimaryButton(label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    PrimaryCta(stringResource(label), onClick, enabled = enabled)
}

@Composable
private fun StepTitle(title: Int, body: Int, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    if (icon != null) HeroIcon(icon)
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DoneBanner(done: Boolean) {
    androidx.compose.animation.AnimatedVisibility(
        visible = done,
        enter = fadeIn(Motion.tween()) + androidx.compose.animation.expandVertically(Motion.tween()),
        exit = fadeOut(Motion.fade()) + androidx.compose.animation.shrinkVertically(Motion.fade()),
    ) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Space.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
        ) {
            androidx.compose.material3.Icon(Icons.Rounded.CheckCircle, contentDescription = null)
            Text(stringResource(R.string.status_done), style = MaterialTheme.typography.titleMedium)
        }
    }
    }
}

@Composable
private fun ColumnScope.WelcomeStep() {
    Spacer(Modifier.height(8.dp))
    DemoAnimation(modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineLarge)
    Text(
        stringResource(R.string.welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    IconTextRow(Icons.Rounded.Lock, stringResource(R.string.welcome_point_private))
    IconTextRow(Icons.Rounded.Keyboard, stringResource(R.string.welcome_point_keyboard))
    IconTextRow(Icons.Rounded.CloudOff, stringResource(R.string.welcome_point_offline))
    Text(
        stringResource(R.string.welcome_setup_time),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.HowStep() {
    StepTitle(R.string.how_title, R.string.how_body)
    var phase by remember { mutableIntStateOf(0) }
    DemoAnimation(modifier = Modifier.align(Alignment.CenterHorizontally), onPhase = { phase = it })
    val items = listOf(
        Triple(Icons.Rounded.TouchApp, R.string.how_step1_title, R.string.how_step1_body),
        Triple(Icons.Rounded.Mic, R.string.how_step2_title, R.string.how_step2_body),
        Triple(Icons.Rounded.PauseCircle, R.string.how_step3_title, R.string.how_step3_body),
    )
    items.forEachIndexed { i, (icon, title, body) ->
        val active = demoStepFor(phase) == i
        val rowColor by androidx.compose.animation.animateColorAsState(
            if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            Motion.tween(),
            label = "howRow",
        )
        val rowContent by androidx.compose.animation.animateColorAsState(
            if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            Motion.tween(),
            label = "howRowContent",
        )
        Surface(
            color = rowColor,
            contentColor = rowContent,
            shape = MaterialTheme.shapes.medium,
            tonalElevation = if (active) 0.dp else 1.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(Space.M),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(icon)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${i + 1}. " + stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ModelStep(state: MainUiState, actions: AppActions, callbacks: OnboardingCallbacks) {
    StepTitle(R.string.model_step_title, R.string.model_step_body, Icons.Rounded.Download)
    ModelList(
        selectedId = state.settings.selectedModelId,
        downloads = state.downloads,
        actions = actions,
        onDownload = callbacks.onDownload,
        onCancel = callbacks.onCancelDownload,
        onSelect = callbacks.onSelectModel,
        onDelete = callbacks.onDeleteModel,
    )
    Text(
        stringResource(R.string.model_step_background),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MicrophoneStep(granted: Boolean, asked: Boolean, actions: AppActions) {
    StepTitle(R.string.mic_step_title, R.string.mic_step_body, Icons.Rounded.Mic)
    IconTextRow(Icons.Rounded.Notifications, stringResource(R.string.mic_step_notifications))
    DoneBanner(granted)
    if (!granted && asked) {
        SectionCard(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Text(stringResource(R.string.mic_step_denied), style = MaterialTheme.typography.bodyMedium)
            SecondaryCta(stringResource(R.string.mic_step_open_app_settings), actions.openAppInfo)
        }
    }
}

@Composable
private fun OverlayStep(granted: Boolean) {
    StepTitle(R.string.overlay_step_title, R.string.overlay_step_body, Icons.Rounded.Layers)
    DoneBanner(granted)
    if (!granted) {
        SectionCard {
            listOf(R.string.overlay_step_1, R.string.overlay_step_2, R.string.overlay_step_3, R.string.overlay_step_4)
                .forEachIndexed { i, res -> NumberedStep(i + 1, styledStringResource(res)) }
        }
    }
}

@Composable
private fun AccessibilityStep(enabled: Boolean, actions: AppActions) {
    StepTitle(R.string.a11y_step_title, R.string.a11y_step_body, Icons.Rounded.Accessibility)
    DoneBanner(enabled)
    if (!enabled) {
        SectionCard {
            listOf(R.string.a11y_step_1, R.string.a11y_step_2, R.string.a11y_step_3, R.string.a11y_step_4)
                .forEachIndexed { i, res -> NumberedStep(i + 1, styledStringResource(res)) }
        }
        SectionCard(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
            Text(stringResource(R.string.a11y_restricted_title), style = MaterialTheme.typography.titleMedium)
            Text(styledStringResource(R.string.a11y_restricted_body), style = MaterialTheme.typography.bodyMedium)
            SecondaryCta(stringResource(R.string.a11y_open_app_info), actions.openAppInfo)
        }
        Text(
            stringResource(R.string.a11y_skip_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    IconTextRow(Icons.Rounded.Lock, stringResource(R.string.a11y_step_privacy))
}

@Composable
private fun TryStep(state: MainUiState, level: Float, callbacks: OnboardingCallbacks) {
    StepTitle(R.string.try_step_title, R.string.try_step_body, Icons.Rounded.Edit)
    if (!state.setup.model) ModelProgressLine(state.downloads)
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

@Composable
private fun DoneStep(canRun: Boolean) {
    HeroIcon(Icons.Rounded.CheckCircle)
    Text(stringResource(R.string.done_title), style = MaterialTheme.typography.headlineLarge)
    Text(
        stringResource(R.string.done_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    UsageTips()
    if (!canRun) {
        Text(
            stringResource(R.string.done_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Start,
        )
    }
}

/** The gesture cheat sheet, shared by the last guide step and the home screen. */
@Composable
fun UsageTips() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTextRow(Icons.Rounded.TouchApp, stringResource(R.string.tip_tap))
        IconTextRow(Icons.Rounded.PauseCircle, stringResource(R.string.tip_pause))
        IconTextRow(Icons.Rounded.PanTool, stringResource(R.string.tip_hold))
        IconTextRow(Icons.Rounded.DragIndicator, stringResource(R.string.tip_drag))
        IconTextRow(Icons.Rounded.Spellcheck, styledStringResource(R.string.tip_words))
        IconTextRow(Icons.Rounded.RestartAlt, stringResource(R.string.tip_restart))
    }
}

/** Compact status of whichever model is on its way, for screens past the model step. */
@Composable
private fun ModelProgressLine(downloads: Map<String, DownloadState>) {
    val active = downloads.values.firstOrNull { it is DownloadState.Downloading || it is DownloadState.Extracting }
        ?: return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (active) {
            is DownloadState.Downloading -> {
                if (active.totalBytes > 0 && !active.waitingForNetwork) {
                    LinearProgressIndicator(progress = { active.progressPct / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    if (active.waitingForNetwork) stringResource(R.string.model_waiting_wifi)
                    else stringResource(R.string.model_downloading, active.progressPct),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.model_extracting), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
