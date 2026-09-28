package com.wisperlow.mobile.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wisperlow.mobile.PracticeUi
import com.wisperlow.mobile.R
import com.wisperlow.mobile.dictation.DictationError
import com.wisperlow.mobile.dictation.DictationState

/** In-app try-it area: the same engine as the bubble, without leaving Wisperlow. */
@Composable
fun PracticeArea(
    practice: PracticeUi,
    dictation: DictationState,
    level: Float,
    modelReady: Boolean,
    onToggle: () -> Unit,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val listening = dictation is DictationState.Listening
    val finishing = dictation is DictationState.Finishing
    val live = when (dictation) {
        is DictationState.Listening -> dictation.partial
        is DictationState.Finishing -> dictation.partial
        DictationState.Idle -> ""
    }
    val shown = listOf(practice.text, live).filter { it.isNotBlank() }.joinToString(" ")

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = { if (!listening && !finishing) onTextChange(it) },
            label = { Text(stringResource(R.string.try_field_label)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MicButton(listening = listening, finishing = finishing, level = level, enabled = modelReady, onClick = onToggle)
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        !modelReady -> stringResource(R.string.try_needs_model)
                        dictation is DictationState.Listening && dictation.modelLoading -> stringResource(R.string.try_loading)
                        listening -> stringResource(R.string.try_listening)
                        finishing -> stringResource(R.string.try_finishing)
                        else -> stringResource(R.string.try_start)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                practice.error?.let { error ->
                    Text(
                        text = stringResource(errorText(error)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (practice.text.isNotBlank() && !listening && !finishing) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.try_clear)) }
            }
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, finishing: Boolean, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val animate = ValueAnimator.areAnimatorsEnabled()
    val pulse by animateFloatAsState(
        targetValue = if (listening) 1f + (level * 4f).coerceIn(0f, 1f) * 0.35f else 1f,
        animationSpec = tween(if (animate) 90 else 0),
        label = "micPulse",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        if (listening) {
            Box(
                Modifier
                    .size(64.dp)
                    .scale(pulse)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
            )
        }
        FilledIconButton(
            onClick = onClick,
            enabled = enabled && !finishing,
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(),
        ) {
            if (finishing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            } else {
                Icon(
                    if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    contentDescription = stringResource(if (listening) R.string.bubble_insert else R.string.try_start),
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

fun errorText(error: DictationError): Int = when (error) {
    DictationError.NO_MODEL -> R.string.error_no_model
    DictationError.MODEL_LOAD_FAILED -> R.string.error_model_load
    DictationError.MIC_PERMISSION -> R.string.error_mic_permission
    DictationError.MIC_UNAVAILABLE -> R.string.error_mic_busy
    DictationError.NOTHING_HEARD -> R.string.error_nothing_heard
    DictationError.BUSY -> R.string.error_busy
}

/** Things only the activity can do: permission prompts and system screens. */
class AppActions(
    val requestMicrophone: () -> Unit,
    val openOverlaySettings: () -> Unit,
    val openAccessibilitySettings: () -> Unit,
    val openAppInfo: () -> Unit,
    val startBubble: () -> Unit,
    val stopBubble: () -> Unit,
    val copyText: (String) -> Unit,
    val isMetered: () -> Boolean,
)
