package com.wisperlow.mobile.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.wisperlow.mobile.R
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Script timing for the demo loop, in milliseconds per beat. */
private object Beat {
    const val Idle = 900L
    const val Keyboard = 1300L
    const val Word = 55L
    const val Settle = 500L
    const val Typed = 1800L
    const val Rest = 900L
}

/** Phases: 0 idle, 1 Wisperlow keyboard up, 2 listening, 3 typed, 4 rest. */
fun demoStepFor(phase: Int): Int = when (phase) {
    0, 1 -> 0
    2 -> 1
    else -> 2
}

/**
 * A looping miniature phone that acts out one dictation. It is decorative:
 * the surrounding copy carries the instructions for screen-reader users.
 */
@Composable
fun DemoAnimation(modifier: Modifier = Modifier, onPhase: (Int) -> Unit = {}) {
    var phase by remember { mutableIntStateOf(0) }
    var typed by remember { mutableIntStateOf(0) }
    val report by rememberUpdatedState(onPhase)
    val animate = ValueAnimator.areAnimatorsEnabled()
    val sentence = stringResource(R.string.demo_sentence)
    val messageHint = stringResource(R.string.demo_message_hint)
    val insertedLabel = stringResource(R.string.bubble_inserted)

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Re-keyed on lifecycle so the loop stops while the app is in the background.
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(animate, resumed) {
        if (!resumed) return@LaunchedEffect
        if (!animate) {
            phase = 3
            typed = sentence.length
            report(3)
            return@LaunchedEffect
        }
        while (true) {
            phase = 0; typed = 0; report(0); delay(Beat.Idle)
            phase = 1; report(1); delay(Beat.Keyboard)
            phase = 2; report(2)
            // Words appear on the keyboard as they are "spoken".
            while (typed < sentence.length) {
                typed++
                delay(Beat.Word)
            }
            delay(Beat.Settle)
            phase = 3; report(3); delay(Beat.Typed)
            phase = 4; report(4); delay(Beat.Rest)
        }
    }

    val bubbleColor by animateColorAsState(
        when (phase) {
            2 -> WisperlowColors.BubbleListening
            3 -> WisperlowColors.BubbleSuccess
            else -> WisperlowColors.BubbleSurface
        },
        animationSpec = Motion.tween(),
        label = "demoBubble",
    )
    val fieldText = if (phase >= 3) sentence else ""
    val phoneColor = MaterialTheme.colorScheme.surface
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .size(width = 230.dp, height = 300.dp)
            .clearAndSetSemantics { }
            .background(phoneColor, RoundedCornerShape(30.dp))
            .border(6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), RoundedCornerShape(30.dp))
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            // A fake chat app.
            Box(Modifier.width(90.dp).height(10.dp).background(lineColor, CircleShape))
            Spacer(Modifier.height(6.dp))
            ChatLine(widthFraction = 0.7f, color = lineColor)
            ChatLine(widthFraction = 0.5f, color = lineColor, alignEnd = true, accent = true)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(
                        1.5.dp,
                        if (phase >= 1) MaterialTheme.colorScheme.primary else lineColor,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = Space.S)
                    .animateContentSize(Motion.tween()),
            ) {
                Text(
                    text = fieldText.ifEmpty { messageHint },
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = if (fieldText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            AnimatedVisibility(
                visible = phase in 1..3,
                enter = slideInVertically(Motion.spring()) { it } + fadeIn(Motion.fade()),
                exit = slideOutVertically(Motion.tween()) { it } + fadeOut(Motion.fade()),
            ) {
                VoiceKeyboard(lineColor, bubbleColor, phase, sentence.take(typed), insertedLabel)
            }
        }
    }
}

@Composable
private fun ChatLine(widthFraction: Float, color: Color, alignEnd: Boolean = false, accent: Boolean = false) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth(widthFraction)
                .height(26.dp)
                .background(
                    if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else color,
                    RoundedCornerShape(12.dp),
                ),
        )
    }
}

/** The Wisperlow keyboard in miniature: live words on top, the mic in the middle. */
@Composable
private fun VoiceKeyboard(color: Color, micColor: Color, phase: Int, heard: String, insertedLabel: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when (phase) {
                2 -> heard.takeLast(28)
                3 -> insertedLabel
                else -> " "
            },
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Box(Modifier.size(28.dp, 20.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)))
            Box(
                Modifier
                    .size(44.dp)
                    .background(micColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (phase) {
                        2 -> Icons.Rounded.GraphicEq
                        3 -> Icons.Rounded.Check
                        else -> Icons.Rounded.Mic
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(Modifier.size(28.dp, 20.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)))
        }
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
        )
    }
}
