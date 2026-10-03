package com.wisperlow.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.sp
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

private val Violet = Color(0xFF6C4DF0)
private val VioletSoft = Color(0xFFB794FF)
private val Blue = Color(0xFF3F7BEA)
private val BlueSoft = Color(0xFF7ED0FF)
private val Magenta = Color(0xFFB04FD8)
private val MagentaSoft = Color(0xFFFF8AC4)
private val Teal = Color(0xFF1E9E9E)
private val TealSoft = Color(0xFF6EE7C8)
private val Green = Color(0xFF1F9D6B)
private val GreenSoft = Color(0xFF6FE0A8)

private fun orbHeight(step: GuideStep) = when (step) {
    GuideStep.WELCOME -> 250.dp
    GuideStep.HOW -> 250.dp
    GuideStep.MODEL -> 210.dp
    GuideStep.MICROPHONE, GuideStep.OVERLAY, GuideStep.ACCESSIBILITY -> 190.dp
    GuideStep.TRY -> 130.dp
    GuideStep.DONE -> 230.dp
}

private fun orbIcon(step: GuideStep): ImageVector = when (step) {
    GuideStep.WELCOME, GuideStep.MICROPHONE -> Icons.Rounded.Mic
    GuideStep.HOW -> Icons.Rounded.GraphicEq
    GuideStep.MODEL -> Icons.Rounded.Download
    GuideStep.OVERLAY -> Icons.Rounded.Layers
    GuideStep.ACCESSIBILITY -> Icons.Rounded.Accessibility
    GuideStep.TRY -> Icons.Rounded.Edit
    GuideStep.DONE -> Icons.Rounded.Check
}

@Composable
fun OnboardingScreen(
    state: MainUiState,
    level: Float,
    actions: AppActions,
    callbacks: OnboardingCallbacks,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var micAsked by rememberSaveable { mutableStateOf(false) }
    var demoPhase by remember { mutableIntStateOf(0) }
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
    val active = state.downloads.values.filterIsInstance<DownloadState.Downloading>().firstOrNull()
    val unpacking = state.downloads.values.any { it is DownloadState.Extracting }
    val ready = state.downloads.values.any { it is DownloadState.Completed }

    val granted = when (step) {
        GuideStep.MICROPHONE -> setup.microphone
        GuideStep.OVERLAY -> setup.overlay
        GuideStep.ACCESSIBILITY -> setup.accessibility
        else -> false
    }
    val spec = when {
        step == GuideStep.DONE -> OrbSpec(Green, GreenSoft, 0.2f, complete = true)
        granted -> OrbSpec(Green, GreenSoft, 0.25f)
        step == GuideStep.MODEL -> OrbSpec(
            Blue, BlueSoft, 0.3f,
            ringProgress = when {
                ready || unpacking -> 1f
                active != null -> active.progressPct / 100f
                else -> null
            },
        )
        step == GuideStep.MICROPHONE -> OrbSpec(Magenta, MagentaSoft, 0.7f)
        step == GuideStep.OVERLAY -> OrbSpec(Violet, BlueSoft, 0.45f)
        step == GuideStep.ACCESSIBILITY -> OrbSpec(Teal, TealSoft, 0.45f)
        step == GuideStep.TRY -> OrbSpec(Violet, VioletSoft, 0.15f)
        else -> OrbSpec(Violet, VioletSoft, 0.55f)
    }
    val tint by animateColorAsState(spec.primary.copy(alpha = 0.16f), Motion.slow(), label = "guideTint")
    val bg = MaterialTheme.colorScheme.background
    // The illustration trails the text slightly, which reads as depth between steps.
    val pos by animateFloatAsState(index.toFloat(), Motion.spring(), label = "guidePos")
    val slotHeight by animateDpAsState(orbHeight(step), Motion.slow(), label = "slotHeight")
    val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val showDemo = step == GuideStep.HOW
    val orbAlpha by animateFloatAsState(if (showDemo) 0f else 1f, Motion.tween(), label = "orbAlpha")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint, bg), endY = 1400f)),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val backAlpha by animateFloatAsState(if (index > 0) 1f else 0f, Motion.fade(), label = "backAlpha")
                if (index > 0 || backAlpha > 0f) {
                    TextButton(onClick = { index-- }, modifier = Modifier.graphicsLayer { alpha = backAlpha }) {
                        Text(stringResource(R.string.action_back), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            StepPills(index, steps.size, stringResource(R.string.onboarding_step, index + 1, steps.size))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                callbacks.onClose?.let { close ->
                    TextButton(onClick = close) {
                        Text(stringResource(R.string.action_done), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(slotHeight)
                .graphicsLayer { translationX = (index - pos) * widthPx * 0.12f },
            contentAlignment = Alignment.Center,
        ) {
            AuroraOrb(
                spec = spec,
                level = if (step == GuideStep.TRY) level else 0f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = orbAlpha },
                center = {
                    if (spec.ringProgress != null && active != null && step == GuideStep.MODEL) {
                        Text(
                            "${active.progressPct}%",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                    } else {
                        OrbGlyph(orbIcon(step))
                    }
                },
            )
            val demoT by animateFloatAsState(if (showDemo) 1f else 0f, Motion.spring(), label = "demoT")
            if (showDemo || demoT > 0.01f) {
                Box(
                    Modifier
                        .size(196.dp, 250.dp)
                        .graphicsLayer {
                            alpha = demoT.coerceIn(0f, 1f)
                            val sc = 0.85f + 0.15f * demoT
                            scaleX = sc
                            scaleY = sc
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    DemoAnimation(
                        modifier = Modifier
                            .requiredSize(230.dp, 300.dp)
                            .graphicsLayer { scaleX = 0.83f; scaleY = 0.83f },
                        onPhase = { demoPhase = it },
                    )
                }
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (
                    slideInHorizontally(Motion.slow()) { if (forward) it / 3 else -it / 3 } +
                        fadeIn(Motion.tween(), initialAlpha = 0f)
                    ) togetherWith
                    (
                        slideOutHorizontally(Motion.tween()) { if (forward) -it / 4 else it / 4 } +
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
                verticalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                when (current) {
                    GuideStep.WELCOME -> WelcomeStep()
                    GuideStep.HOW -> HowStep(demoPhase)
                    GuideStep.MODEL -> ModelStep(state, actions, callbacks, active)
                    GuideStep.MICROPHONE -> MicrophoneStep(setup.microphone, micAsked, actions)
                    GuideStep.OVERLAY -> OverlayStep(setup.overlay)
                    GuideStep.ACCESSIBILITY -> AccessibilityStep(setup.accessibility, actions)
                    GuideStep.TRY -> TryStep(state, level, callbacks)
                    GuideStep.DONE -> DoneStep(setup.canRunBubble)
                }
            }
        }

        // One pinned primary action per step.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.L)
                .padding(top = Space.S, bottom = Space.M),
            verticalArrangement = Arrangement.spacedBy(Space.S),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (step == GuideStep.ACCESSIBILITY && !setup.accessibility) {
                TextButton(onClick = next) { Text(stringResource(R.string.action_skip)) }
            }
            val cta: Pair<Int, () -> Unit> = when (step) {
                GuideStep.WELCOME -> R.string.welcome_start to next
                GuideStep.MICROPHONE -> if (setup.microphone) {
                    R.string.action_next to next
                } else {
                    R.string.action_allow to {
                        micAsked = true
                        actions.requestMicrophone()
                    }
                }
                GuideStep.OVERLAY -> if (setup.overlay) R.string.action_next to next
                else R.string.action_open_settings to actions.openOverlaySettings
                GuideStep.ACCESSIBILITY -> if (setup.accessibility) R.string.action_next to next
                else R.string.action_open_settings to actions.openAccessibilitySettings
                GuideStep.DONE -> R.string.done_start to callbacks.onFinish
                else -> R.string.action_next to next
            }
            PrimaryCta(
                stringResource(cta.first),
                cta.second,
                modifier = Modifier.fillMaxWidth(),
                enabled = step != GuideStep.MODEL || modelStarted,
            )
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

/** Display headline plus one short supporting sentence. */
@Composable
private fun Headline(title: Int, body: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.displaySmall)
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Letters rise in one after another, once, when the welcome screen first appears. */
@Composable
private fun Wordmark(text: String) {
    Row {
        text.forEachIndexed { i, ch ->
            val t = remember { Animatable(if (Motion.enabled) 0f else 1f) }
            LaunchedEffect(Unit) {
                delay(i * 45L)
                t.animateTo(1f, Motion.bouncy())
            }
            Text(
                ch.toString(),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer {
                    alpha = t.value.coerceIn(0f, 1f)
                    translationY = (1f - t.value) * 24f
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep() {
    Wordmark(stringResource(R.string.app_name).uppercase())
    Headline(R.string.welcome_title, R.string.welcome_body)
    IconTextRow(Icons.Rounded.Lock, stringResource(R.string.welcome_point_private))
}

@Composable
private fun ColumnScope.HowStep(phase: Int) {
    Headline(R.string.how_title, R.string.how_body)
    val items = listOf(
        Triple(Icons.Rounded.TouchApp, R.string.how_step1_title, R.string.how_step1_body),
        Triple(Icons.Rounded.Mic, R.string.how_step2_title, R.string.how_step2_body),
        Triple(Icons.Rounded.PauseCircle, R.string.how_step3_title, R.string.how_step3_body),
    )
    items.forEachIndexed { i, (icon, title, body) ->
        val on = demoStepFor(phase) == i
        val rowColor by animateColorAsState(
            if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            Motion.tween(),
            label = "howRow",
        )
        val lift by animateFloatAsState(if (on) 1f else 0.97f, Motion.spring(), label = "howLift")
        Surface(
            color = rowColor,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.graphicsLayer { scaleX = lift; scaleY = lift },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(Space.Sm),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(icon)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${i + 1}. " + stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelStep(state: MainUiState, actions: AppActions, callbacks: OnboardingCallbacks, active: DownloadState.Downloading?) {
    Headline(R.string.model_step_title, R.string.model_step_body)
    if (active != null && active.totalBytes > 0) {
        Text(
            if (active.waitingForNetwork) stringResource(R.string.model_waiting_wifi)
            else stringResource(R.string.model_ring_size, (active.downloadedBytes / 1_000_000L).toInt(), (active.totalBytes / 1_000_000L).toInt()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
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
    Headline(R.string.mic_step_title, R.string.mic_step_body)
    StatusChip(granted)
    if (!granted) IconTextRow(Icons.Rounded.Notifications, stringResource(R.string.mic_step_notifications))
    if (!granted && asked) {
        SectionCard(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Text(stringResource(R.string.mic_step_denied), style = MaterialTheme.typography.bodyMedium)
            SecondaryCta(stringResource(R.string.mic_step_open_app_settings), actions.openAppInfo)
        }
    }
}

@Composable
private fun OverlayStep(granted: Boolean) {
    Headline(R.string.overlay_step_title, R.string.overlay_step_body)
    StatusChip(granted)
    if (!granted) {
        SectionCard {
            listOf(R.string.overlay_step_1, R.string.overlay_step_2, R.string.overlay_step_3, R.string.overlay_step_4)
                .forEachIndexed { i, res -> NumberedStep(i + 1, styledStringResource(res)) }
        }
    }
}

@Composable
private fun AccessibilityStep(enabled: Boolean, actions: AppActions) {
    Headline(R.string.a11y_step_title, R.string.a11y_step_body)
    StatusChip(enabled)
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
    Headline(R.string.try_step_title, R.string.try_step_body)
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
    Headline(R.string.done_title, R.string.done_body)
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
