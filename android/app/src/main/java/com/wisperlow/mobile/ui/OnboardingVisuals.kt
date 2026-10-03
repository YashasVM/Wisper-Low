package com.wisperlow.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What the shared orb should look like for the current step; it animates between specs. */
class OrbSpec(
    val primary: Color,
    val secondary: Color,
    /** 0..1 resting height of the waveform ring. */
    val energy: Float,
    /** 0..1 download progress, or null for no ring. */
    val ringProgress: Float? = null,
    val complete: Boolean = false,
)

/**
 * One persistent orb for the whole flow. Colors, energy, ring and check are animated
 * state, so moving between steps morphs it instead of swapping in a new picture.
 * Infinite motion lives in a single transition created once; draw-only reads keep
 * recomposition out of the frame loop.
 */
@Composable
fun AuroraOrb(spec: OrbSpec, level: Float, modifier: Modifier = Modifier, center: @Composable () -> Unit = {}) {
    val c1 by animateColorAsState(spec.primary, Motion.slow(), label = "orbC1")
    val c2 by animateColorAsState(spec.secondary, Motion.slow(), label = "orbC2")
    val energy by animateFloatAsState(spec.energy, Motion.spring(), label = "orbEnergy")
    val ringTarget = spec.ringProgress
    val ringVisible by animateFloatAsState(if (ringTarget != null) 1f else 0f, Motion.slow(), label = "ringVisible")
    val ring by animateFloatAsState(ringTarget ?: 0f, Motion.slow(), label = "ring")
    val check = remember { Animatable(0f) }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(spec.complete) {
        if (spec.complete) {
            check.animateTo(1f, tween(if (Motion.enabled) 520 else 0, easing = Motion.Easing))
            burst.snapTo(0f)
            burst.animateTo(1f, tween(if (Motion.enabled) 1100 else 0, easing = Motion.Easing))
        } else {
            check.snapTo(0f)
            burst.snapTo(1f)
        }
    }
    val live by rememberUpdatedState(level.coerceIn(0f, 1f))

    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbPhase",
    )
    val pulse by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "orbPulse",
    )
    val motion = Motion.enabled
    val track = MaterialTheme.colorScheme.outline

    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ph = if (motion) phase else 0f
            val pl = if (motion) pulse else 0f
            val cx = size.width / 2
            val cy = size.height / 2
            val r = size.minDimension * 0.30f
            val tau = (2 * PI).toFloat()
            val breathe = 1f + 0.03f * sin(pl * tau)

            // Soft halo.
            drawCircle(
                Brush.radialGradient(listOf(c1.copy(alpha = 0.32f), Color.Transparent), Offset(cx, cy), r * 1.9f),
                radius = r * 1.9f,
                center = Offset(cx, cy),
            )
            // Waveform ring: each bar rides its own phase so the ring ripples, and breathes with mic level.
            val bars = 36
            val amp = (energy + live * 0.9f).coerceIn(0f, 1.2f)
            for (i in 0 until bars) {
                val a = i * tau / bars
                val wave = 0.5f + 0.5f * sin(ph * tau * 3 + i * 0.55f)
                val wave2 = 0.5f + 0.5f * sin(ph * tau * 2 - i * 0.9f)
                val len = r * (0.06f + amp * 0.34f * (0.35f * wave + 0.65f * wave2))
                val r0 = r * 1.1f * breathe
                drawLine(
                    color = c1.copy(alpha = 0.85f * (1f - 0.7f * ringVisible)),
                    start = Offset(cx + cos(a) * r0, cy + sin(a) * r0),
                    end = Offset(cx + cos(a) * (r0 + len), cy + sin(a) * (r0 + len)),
                    strokeWidth = size.minDimension * 0.014f,
                    cap = StrokeCap.Round,
                )
            }
            // Orb body with two drifting lights.
            val body = Offset(cx, cy)
            drawCircle(Brush.linearGradient(listOf(c1, c2), Offset(cx - r, cy - r), Offset(cx + r, cy + r)), r * breathe, body)
            val l1 = Offset(cx + cos(ph * tau) * r * 0.35f, cy + sin(ph * tau) * r * 0.35f)
            val l2 = Offset(cx + cos(-ph * tau * 1.5f + 2f) * r * 0.4f, cy + sin(-ph * tau * 1.5f + 2f) * r * 0.4f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent), l1, r * 0.75f), r * 0.75f, l1)
            drawCircle(Brush.radialGradient(listOf(c2.copy(alpha = 0.55f), Color.Transparent), l2, r * 0.7f), r * 0.7f, l2)

            // Download ring.
            if (ringVisible > 0.01f) {
                val rr = r * 1.55f
                val st = Stroke(size.minDimension * 0.03f, cap = StrokeCap.Round)
                val tl = Offset(cx - rr, cy - rr)
                val sz = androidx.compose.ui.geometry.Size(rr * 2, rr * 2)
                drawArc(track.copy(alpha = 0.5f * ringVisible), 0f, 360f, false, tl, sz, style = st)
                drawArc(c1.copy(alpha = ringVisible), -90f, 360f * ring.coerceAtLeast(0.015f), false, tl, sz, style = st)
            }
            // Completion: ripple, restrained confetti and a drawn-in check.
            if (burst.value < 1f) {
                val t = burst.value
                drawCircle(c1.copy(alpha = 0.45f * (1f - t)), r * (1f + 0.9f * t), body, style = Stroke(size.minDimension * 0.012f))
                for (i in 0 until 14) {
                    val a = i * tau / 14 + 0.3f
                    val dist = r * (1.3f + 0.9f * t * (0.7f + 0.3f * ((i * 7) % 5) / 4f))
                    val p = Offset(cx + cos(a) * dist, cy + sin(a) * dist + t * t * r * 0.35f)
                    drawCircle((if (i % 2 == 0) c1 else c2).copy(alpha = 1f - t), size.minDimension * 0.011f, p)
                }
            }
            if (check.value > 0f) {
                val path = Path().apply {
                    moveTo(cx - r * 0.38f, cy + r * 0.02f)
                    lineTo(cx - r * 0.1f, cy + r * 0.3f)
                    lineTo(cx + r * 0.4f, cy - r * 0.28f)
                }
                val seg = Path()
                PathMeasure().apply { setPath(path, false) }.let { pm ->
                    pm.getSegment(0f, pm.length * check.value, seg, true)
                }
                drawPath(seg, Color.White, style = Stroke(size.minDimension * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Box(Modifier.graphicsLayer { alpha = 1f - check.value }) { center() }
    }
}


/** Orb glyph that scales and fades when the step changes. */
@Composable
fun OrbGlyph(icon: ImageVector) {
    androidx.compose.animation.AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(Motion.tween()) + androidx.compose.animation.scaleIn(Motion.spring(), initialScale = 0.6f)) togetherWith
                (androidx.compose.animation.fadeOut(Motion.fade()) + androidx.compose.animation.scaleOut(Motion.fade(), targetScale = 0.6f))
        },
        label = "orbGlyph",
    ) { ic ->
        Icon(ic, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

/** Segmented progress: the current step stretches into a pill, finished steps fill in. */
@Composable
fun StepPills(index: Int, count: Int, description: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val w by animateDpAsState(if (i == index) 26.dp else 8.dp, Motion.spring(), label = "pillW")
            val color by animateColorAsState(
                if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                Motion.tween(),
                label = "pillC",
            )
            Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(color))
        }
    }
}

/** Permission status: "Needed" until granted, then springs into a green check. */
@Composable
fun StatusChip(granted: Boolean, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        Motion.tween(),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        if (granted) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        Motion.tween(),
        label = "chipFg",
    )
    val scale by animateFloatAsState(if (granted) 1f else 0f, Motion.bouncy(), label = "chipCheck")
    val pop by animateFloatAsState(if (granted) 1f else 0.96f, Motion.bouncy(), label = "chipPop")
    Surface(
        modifier = modifier.graphicsLayer { scaleX = pop; scaleY = pop },
        color = bg,
        contentColor = fg,
        shape = CircleShape,
    ) {
        Row(
            Modifier.padding(horizontal = Space.M, vertical = Space.S),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((18 * scale.coerceIn(0f, 1f)).dp + 0.dp)) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale.coerceIn(0f, 1f) },
                )
            }
            Box(Modifier.width((6 * scale.coerceIn(0f, 1f)).dp))
            Text(
                androidx.compose.ui.res.stringResource(
                    if (granted) com.wisperlow.mobile.R.string.status_done else com.wisperlow.mobile.R.string.status_needed,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
