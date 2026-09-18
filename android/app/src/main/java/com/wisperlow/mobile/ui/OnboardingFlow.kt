package com.wisperlow.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Resource-backed copy supplied by the integration layer and Android string resources. */
data class OnboardingStepCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val actionRes: Int,
    @StringRes val readyRes: Int,
    @StringRes val deniedRes: Int,
)

data class OnboardingModelCopy(
    val step: OnboardingStepCopy,
    @StringRes val retryRes: Int,
    @StringRes val downloadingRes: Int,
    @StringRes val progressRes: Int,
    @StringRes val failureRes: Int,
)

data class OnboardingPracticeCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val startRes: Int,
    @StringRes val stopRes: Int,
    @StringRes val listeningRes: Int,
    @StringRes val transcribingRes: Int,
    @StringRes val polishingRes: Int,
    @StringRes val failureRes: Int,
    @StringRes val retryRes: Int,
    @StringRes val skipRes: Int,
    @StringRes val resumeRes: Int,
    @StringRes val originalRes: Int,
    @StringRes val polishedRes: Int,
    @StringRes val quickInsertRes: Int,
    @StringRes val reviewFirstRes: Int,
)

data class OnboardingCompletionCopy(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    @StringRes val completeRes: Int,
    @StringRes val doneRes: Int,
)

data class OnboardingCopy(
    @StringRes val eyebrowRes: Int,
    @StringRes val welcomeTitleRes: Int,
    @StringRes val welcomeBodyRes: Int,
    @StringRes val beginRes: Int,
    @StringRes val stepProgressRes: Int,
    val microphone: OnboardingStepCopy,
    val overlay: OnboardingStepCopy,
    val accessibility: OnboardingStepCopy,
    val model: OnboardingModelCopy,
    val practice: OnboardingPracticeCopy,
    val completion: OnboardingCompletionCopy,
    @StringRes val optionalRes: Int,
    @StringRes val modelReadyRes: Int,
)

/**
 * Effects owned by MainActivity/MainViewModel. Callers dispatch the matching
 * [OnboardingEvent] into [reduceOnboarding] alongside each effect.
 */
data class OnboardingCallbacks(
    val onBegin: () -> Unit = {},
    val onRequestMicrophone: () -> Unit = {},
    val onRequestOverlay: () -> Unit = {},
    val onRequestAccessibility: () -> Unit = {},
    val onDownloadModel: () -> Unit = {},
    val onRetryModel: () -> Unit = {},
    val onStartPractice: () -> Unit = {},
    val onStopPractice: () -> Unit = {},
    val onRetryPractice: () -> Unit = {},
    val onSkipPractice: () -> Unit = {},
    val onResumePractice: () -> Unit = {},
    val onSelectDeliveryMode: (DeliveryMode) -> Unit = {},
    val onCompleteSetup: () -> Unit = {},
)

private const val ONBOARDING_STEP_COUNT = 5

/**
 * First-launch prototype: one focused action per screen. Keep this route for
 * onboarding, where reducing simultaneous decisions matters more than density.
 */
@Composable
fun OnboardingStepFlow(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
) {
    OnboardingShell(
        state = state,
        copy = copy,
        modifier = modifier,
        showProgress = true,
    ) {
        when (state.step) {
            OnboardingStep.Welcome -> WelcomeStep(copy, callbacks)
            OnboardingStep.Microphone -> PermissionStep(
                status = state.readiness.microphone,
                copy = copy.microphone,
                onAction = callbacks.onRequestMicrophone,
            )
            OnboardingStep.Overlay -> OverlayStep(state, copy, callbacks)
            OnboardingStep.Model -> ModelStep(state, copy, callbacks)
            OnboardingStep.Practice -> PracticeStep(state, copy, callbacks)
            OnboardingStep.Complete -> CompletionStep(state, copy, callbacks)
        }
    }
}

/**
 * Settings prototype: one resumable card exposes only the next required action.
 * It is intentionally a separate layout so settings can adopt it without
 * changing the first-launch step flow.
 */
@Composable
fun OnboardingSetupCard(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
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
                Text(
                    text = stringResource(copy.eyebrowRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(
                        copy.stepProgressRes,
                        state.readiness.requiredCompletedCount,
                        REQUIRED_STEP_COUNT,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = {
                    state.readiness.requiredCompletedCount /
                        REQUIRED_STEP_COUNT.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            when (state.step) {
                OnboardingStep.Welcome -> WelcomeStep(copy, callbacks, compact = true)
                OnboardingStep.Microphone -> PermissionStep(
                    status = state.readiness.microphone,
                    copy = copy.microphone,
                    onAction = callbacks.onRequestMicrophone,
                    compact = true,
                )
                OnboardingStep.Overlay -> OverlayStep(state, copy, callbacks, compact = true)
                OnboardingStep.Model -> ModelStep(state, copy, callbacks, compact = true)
                OnboardingStep.Practice -> PracticeStep(state, copy, callbacks, compact = true)
                OnboardingStep.Complete -> CompletionStep(state, copy, callbacks, compact = true)
            }
        }
    }
}

@Composable
private fun OnboardingShell(
    state: OnboardingState,
    copy: OnboardingCopy,
    modifier: Modifier,
    showProgress: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(copy.eyebrowRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (showProgress) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        copy.stepProgressRes,
                        state.step.progressNumber(),
                        ONBOARDING_STEP_COUNT,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { state.step.progressNumber() / ONBOARDING_STEP_COUNT.toFloat() },
                    modifier = Modifier.width(120.dp),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        content()
    }
}

@Composable
private fun WelcomeStep(
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    compact: Boolean = false,
) {
    OnboardingPanel {
        Text(
            text = stringResource(copy.welcomeTitleRes),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(copy.welcomeBodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryAction(stringResource(copy.beginRes), callbacks.onBegin)
    }
}

@Composable
private fun PermissionStep(
    status: PermissionStatus,
    copy: OnboardingStepCopy,
    onAction: () -> Unit,
    compact: Boolean = false,
) {
    OnboardingPanel {
        Text(
            text = stringResource(copy.titleRes),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(copy.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionStatusText(status, copy)
        if (status != PermissionStatus.Granted) {
            PrimaryAction(stringResource(copy.actionRes), onAction)
        }
    }
}

@Composable
private fun OverlayStep(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    compact: Boolean = false,
) {
    OnboardingPanel {
        Text(
            text = stringResource(copy.overlay.titleRes),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(copy.overlay.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionStatusText(state.readiness.overlay, copy.overlay)
        if (state.readiness.overlay != PermissionStatus.Granted) {
            PrimaryAction(stringResource(copy.overlay.actionRes), callbacks.onRequestOverlay)
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(copy.accessibility.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(copy.accessibility.bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(copy.optionalRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (state.readiness.accessibility == PermissionStatus.Granted) {
                    Text(
                        text = stringResource(copy.accessibility.readyRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    OutlinedButton(
                        onClick = callbacks.onRequestAccessibility,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(copy.accessibility.actionRes))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelStep(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    compact: Boolean = false,
) {
    OnboardingPanel {
        Text(
            text = stringResource(copy.model.step.titleRes),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(copy.model.step.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val model = state.readiness.model) {
            ModelSetupStatus.NotStarted -> PrimaryAction(
                stringResource(copy.model.step.actionRes),
                callbacks.onDownloadModel,
            )
            is ModelSetupStatus.Downloading -> {
                Text(
                    text = stringResource(copy.model.downloadingRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                model.progressPercent?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(copy.model.progressRes, progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ModelSetupStatus.Ready -> Text(
                text = stringResource(copy.modelReadyRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            is ModelSetupStatus.Failed -> {
                Text(
                    text = stringResource(copy.model.failureRes, model.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = callbacks.onRetryModel,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(copy.model.retryRes))
                }
            }
        }
    }
}

@Composable
private fun PracticeStep(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    compact: Boolean = false,
) {
    OnboardingPanel {
        Text(
            text = stringResource(copy.practice.titleRes),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(copy.practice.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.practice) {
            PracticeStage.NotStarted -> {
                PrimaryAction(stringResource(copy.practice.startRes), callbacks.onStartPractice)
                TextButton(onClick = callbacks.onSkipPractice) {
                    Text(stringResource(copy.practice.skipRes))
                }
            }
            PracticeStage.Listening -> PracticeBusy(
                stringResource(copy.practice.listeningRes),
                stringResource(copy.practice.stopRes),
                callbacks.onStopPractice,
            )
            PracticeStage.Transcribing -> PracticeBusy(
                stringResource(copy.practice.transcribingRes),
                stringResource(copy.practice.stopRes),
                callbacks.onStopPractice,
            )
            PracticeStage.Polishing -> PracticeBusy(
                stringResource(copy.practice.polishingRes),
                stringResource(copy.practice.stopRes),
                callbacks.onStopPractice,
            )
            PracticeStage.Failed -> {
                Text(
                    text = stringResource(copy.practice.failureRes, state.practiceError.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!state.practiceResult?.original.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = callbacks.onRetryPractice,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(copy.practice.retryRes))
                    }
                } else {
                    PrimaryAction(stringResource(copy.practice.startRes), callbacks.onStartPractice)
                }
                TextButton(onClick = callbacks.onSkipPractice) {
                    Text(stringResource(copy.practice.skipRes))
                }
            }
            PracticeStage.Succeeded -> PracticeResultPanel(state, copy, callbacks)
            PracticeStage.Skipped -> {
                Text(
                    text = stringResource(copy.practice.resumeRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = callbacks.onResumePractice,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(copy.practice.startRes))
                }
            }
        }
    }
}

@Composable
private fun PracticeBusy(statusText: String, stopText: String, onStop: () -> Unit) {
    Text(
        text = statusText,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.secondary,
    )
    OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stopText)
    }
}

@Composable
private fun PracticeResultPanel(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
) {
    val result = state.practiceResult ?: return
    TranscriptPreview(
        labelRes = copy.practice.originalRes,
        text = result.original,
    )
    TranscriptPreview(
        labelRes = copy.practice.polishedRes,
        text = result.polished ?: result.original,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val quickSelected = state.deliveryMode == DeliveryMode.QuickInsert
        OutlinedButton(
            onClick = { callbacks.onSelectDeliveryMode(DeliveryMode.QuickInsert) },
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (quickSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(copy.practice.quickInsertRes), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val reviewSelected = state.deliveryMode == DeliveryMode.ReviewFirst
        OutlinedButton(
            onClick = { callbacks.onSelectDeliveryMode(DeliveryMode.ReviewFirst) },
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (reviewSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        ) {
            Text(stringResource(copy.practice.reviewFirstRes), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    PrimaryAction(stringResource(copy.completion.completeRes), callbacks.onCompleteSetup)
}

@Composable
private fun TranscriptPreview(@StringRes labelRes: Int, text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun CompletionStep(
    state: OnboardingState,
    copy: OnboardingCopy,
    callbacks: OnboardingCallbacks,
    compact: Boolean = false,
) {
    OnboardingPanel {
        Text(
            text = stringResource(copy.completion.titleRes),
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(copy.completion.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.setupCompleted) {
            Text(
                text = stringResource(copy.completion.doneRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            PrimaryAction(stringResource(copy.completion.completeRes), callbacks.onCompleteSetup)
            if (state.practice == PracticeStage.Skipped) {
                TextButton(onClick = callbacks.onResumePractice) {
                    Text(stringResource(copy.practice.resumeRes))
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusText(status: PermissionStatus, copy: OnboardingStepCopy) {
    when (status) {
        PermissionStatus.Unknown -> Unit
        PermissionStatus.Granted -> Text(
            text = stringResource(copy.readyRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
        )
        PermissionStatus.Denied -> Text(
            text = stringResource(copy.deniedRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun OnboardingPanel(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

private fun OnboardingStep.progressNumber(): Int = when (this) {
    OnboardingStep.Welcome -> 1
    OnboardingStep.Microphone -> 2
    OnboardingStep.Overlay -> 3
    OnboardingStep.Model -> 4
    OnboardingStep.Practice -> 5
    OnboardingStep.Complete -> ONBOARDING_STEP_COUNT
}

private const val REQUIRED_STEP_COUNT = 3
