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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val DEMO_SENTENCE = "Running ten minutes late, see you soon!"

/** Phases: 0 idle, 1 keyboard up with bubble, 2 listening, 3 typed, 4 rest. */
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

    LaunchedEffect(animate) {
        if (!animate) {
            phase = 3
            typed = DEMO_SENTENCE.length
            report(3)
            return@LaunchedEffect
        }
        while (true) {
            phase = 0; typed = 0; report(0); delay(900)
            phase = 1; report(1); delay(1300)
            phase = 2; report(2)
            // Words appear in the bubble as they are "spoken".
            while (typed < DEMO_SENTENCE.length) {
                typed++
                delay(55)
            }
            delay(500)
            phase = 3; report(3); delay(1800)
            phase = 4; report(4); delay(900)
        }
    }

    val bubbleColor by animateColorAsState(
        when (phase) {
            2 -> WisperlowColors.BubbleListening
            3 -> WisperlowColors.BubbleSuccess
            else -> WisperlowColors.BubbleSurface
        },
        label = "demoBubble",
    )
    val fieldText = if (phase >= 3) DEMO_SENTENCE else ""
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
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .animateContentSize(),
            ) {
                Text(
                    text = fieldText.ifEmpty { "Message" },
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = if (fieldText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            AnimatedVisibility(
                visible = phase in 1..3,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Keyboard(lineColor)
            }
        }

        // The Wisperlow bubble, docked at the edge just above the keyboard.
        AnimatedVisibility(
            visible = phase in 1..3,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 132.dp),
        ) {
            Row(
                modifier = Modifier
                    .background(bubbleColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .animateContentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = when (phase) {
                        2 -> Icons.Rounded.GraphicEq
                        3 -> Icons.Rounded.Check
                        else -> Icons.Rounded.Mic
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                if (phase == 2) {
                    Text(
                        DEMO_SENTENCE.take(typed).takeLast(18),
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.width(96.dp),
                    )
                }
                if (phase == 3) {
                    Text(LocalContext.current.getString(com.wisperlow.mobile.R.string.bubble_inserted), color = Color.White, fontSize = 10.sp)
                }
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

@Composable
private fun Keyboard(color: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(horizontal = (row * 6).dp)) {
                repeat(10 - row * 2) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(16.dp)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(16.dp)
                .align(Alignment.CenterHorizontally)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp)),
        )
    }
}
