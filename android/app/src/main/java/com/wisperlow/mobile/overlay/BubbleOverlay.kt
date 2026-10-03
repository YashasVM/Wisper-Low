package com.wisperlow.mobile.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wisperlow.mobile.R
import com.wisperlow.mobile.ui.WisperlowColors
import com.wisperlow.mobile.ui.WisperlowTheme
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the bubble is showing. The service owns transitions; the overlay only renders. */
sealed interface BubbleUi {
    data object Idle : BubbleUi
    data class Listening(val partial: String, val modelLoading: Boolean) : BubbleUi
    data class Finishing(val partial: String) : BubbleUi
    data class Review(val text: String) : BubbleUi
    data class Flash(val message: String, val success: Boolean) : BubbleUi
}

interface BubbleActions {
    fun onTap()
    fun onLongPress()
    fun onDismissDrop()
    /** [side] is 0 for the left edge, 1 for the right; [y] is in screen pixels. */
    fun onMoved(side: Int, y: Int)
    fun onReviewInsert(text: String)
    fun onReviewCopy(text: String)
    fun onReviewCancel()
}

/**
 * The floating dictation bubble. It is a tiny overlay window that never takes
 * focus except while the optional review editor is open, so the app below
 * keeps its keyboard and cursor.
 */
class BubbleOverlay(
    private val context: Context,
    private val actions: BubbleActions,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density

    private var ui by mutableStateOf<BubbleUi>(BubbleUi.Idle)
    private var micLevel by mutableFloatStateOf(0f)
    private var reviewText by mutableStateOf("")
    private var overDismiss by mutableStateOf(false)

    private var bubbleView: ComposeView? = null
    private var dismissView: ComposeView? = null
    private val owner = OverlayLifecycleOwner()
    private var params: WindowManager.LayoutParams? = null

    // Placement: the bubble docks to a side edge (so wider states grow inward)
    // at a height the user chose, or just above the keyboard by default.
    private var rightSide by mutableStateOf(true)
    private var lastTapAt = 0L
    private var userY: Int? = null
    private var keyboardTop: Int? = null
    private var dragging = false

    val isShowing: Boolean get() = bubbleView != null

    fun render(state: BubbleUi) {
        val wasReview = ui is BubbleUi.Review
        ui = state
        if (state is BubbleUi.Review && !wasReview) reviewText = state.text
        val view = bubbleView ?: return
        val lp = params ?: return
        val flags = windowFlags(focusable = state is BubbleUi.Review)
        val width = windowWidthFor(state)
        val height = windowHeightFor(state)
        view.removeCallbacks(shrinkWindow)
        if (state is BubbleUi.Idle && lp.width != WindowManager.LayoutParams.WRAP_CONTENT) {
            // Let the pill finish collapsing inside the big window, then shrink it.
            if (lp.flags != flags) {
                lp.flags = flags
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            view.postDelayed(shrinkWindow, SHRINK_DELAY_MS)
            return
        }
        // One layout update per state change; the window never resizes per frame.
        if (lp.flags != flags || lp.width != width || lp.height != height) {
            lp.flags = flags
            lp.width = width
            lp.height = height
            applyPlacement()
        }
    }

    private val shrinkWindow = Runnable {
        val view = bubbleView ?: return@Runnable
        val lp = params ?: return@Runnable
        if (ui !is BubbleUi.Idle) return@Runnable
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        applyPlacement()
    }

    private fun windowWidthFor(state: BubbleUi): Int = when (state) {
        is BubbleUi.Idle -> WindowManager.LayoutParams.WRAP_CONTENT
        is BubbleUi.Review -> dp(REVIEW_WIDTH_DP + 16)
        else -> dp(PILL_DP + 16)
    }

    private fun windowHeightFor(state: BubbleUi): Int = when (state) {
        is BubbleUi.Idle, is BubbleUi.Review -> WindowManager.LayoutParams.WRAP_CONTENT
        else -> dp(DOT_DP + 8)
    }

    fun setLevel(value: Float) {
        micLevel = value.coerceIn(0f, 1f)
    }

    /** Top edge of the open keyboard in screen pixels, or null when it is closed. */
    fun setKeyboardTop(top: Int?) {
        if (keyboardTop == top) return
        keyboardTop = top
        applyPlacement()
    }

    /** [saved] is (side, y): side 0 = left, 1 = right. Null uses the default spot. */
    fun show(saved: Pair<Int, Int>?) {
        if (bubbleView != null || !Settings.canDrawOverlays(context)) return
        rightSide = saved?.first != SIDE_LEFT
        userY = saved?.second
        owner.start()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { WisperlowTheme(darkTheme = true) { Bubble() } }
        }
        val lp = baseParams(windowFlags(focusable = ui is BubbleUi.Review))
        lp.width = windowWidthFor(ui)
        lp.height = windowHeightFor(ui)
        placeInto(lp)
        try {
            windowManager.addView(view, lp)
            bubbleView = view
            params = lp
        } catch (_: RuntimeException) {
            bubbleView = null
            params = null
        }
    }

    fun resetPlacement() {
        rightSide = true
        userY = null
        applyPlacement()
    }

    fun hide() {
        hideDismissTarget()
        dragging = false
        bubbleView?.let {
            it.removeCallbacks(shrinkWindow)
            runCatching { windowManager.removeView(it) }
        }
        bubbleView = null
        params = null
    }

    fun destroy() {
        hide()
        owner.destroy()
    }

    // ---- window plumbing ----

    private fun windowFlags(focusable: Boolean): Int {
        // LAYOUT_IN_SCREEN makes y match the screen coordinates reported for the keyboard.
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return flags
    }

    private fun baseParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    )

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun placeInto(lp: WindowManager.LayoutParams) {
        val (_, h) = screenSize()
        val dot = dp(DOT_DP)
        val keyboard = keyboardTop
        var y = userY ?: keyboard?.let { it - dot - dp(KEYBOARD_GAP_DP) } ?: (h * DEFAULT_HEIGHT_FRACTION).roundToInt()
        // Never sit on top of the keys, even at a user-chosen height.
        if (keyboard != null && y + dot > keyboard) y = keyboard - dot - dp(KEYBOARD_GAP_DP)
        if (ui is BubbleUi.Review) y = minOf(y, h - dp(REVIEW_HEIGHT_DP) - dp(BOTTOM_MARGIN_DP))
        lp.gravity = Gravity.TOP or if (rightSide) Gravity.END else Gravity.START
        lp.x = dp(EDGE_MARGIN_DP)
        lp.y = y.coerceIn(dp(TOP_MARGIN_DP), (h - dot - dp(BOTTOM_MARGIN_DP)).coerceAtLeast(dp(TOP_MARGIN_DP)))
    }

    private fun applyPlacement() {
        if (dragging) return
        val view = bubbleView ?: return
        val lp = params ?: return
        placeInto(lp)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val view = bubbleView ?: return
        val lp = params ?: return
        val (w, h) = screenSize()
        // With END gravity, x grows toward the left.
        lp.x = (lp.x + if (rightSide) -dx.roundToInt() else dx.roundToInt()).coerceIn(0, w - dp(DOT_DP))
        lp.y = (lp.y + dy.roundToInt()).coerceIn(0, h - dp(DOT_DP))
        runCatching { windowManager.updateViewLayout(view, lp) }
        overDismiss = isOverDismiss(lp)
    }

    private fun absoluteLeft(lp: WindowManager.LayoutParams): Int {
        val (w, _) = screenSize()
        return if (rightSide) w - lp.x - dp(DOT_DP) else lp.x
    }

    private fun isOverDismiss(lp: WindowManager.LayoutParams): Boolean {
        val (w, h) = screenSize()
        val centerX = absoluteLeft(lp) + dp(DOT_DP) / 2
        val centerY = lp.y + dp(DOT_DP) / 2
        return kotlin.math.abs(centerX - w / 2) < dp(DISMISS_RADIUS_DP) &&
            centerY > h - dp(DISMISS_ZONE_DP)
    }

    private fun startDrag() {
        dragging = true
        showDismissTarget()
    }

    private fun endDrag() {
        dragging = false
        val view = bubbleView ?: return
        val lp = params ?: return
        hideDismissTarget()
        if (overDismiss) {
            overDismiss = false
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
            )
            applyPlacement()
            actions.onDismissDrop()
            return
        }
        // Snap to the nearest side so the bubble never covers the middle of a field.
        val (w, _) = screenSize()
        rightSide = absoluteLeft(lp) + dp(DOT_DP) / 2 >= w / 2
        userY = lp.y
        applyPlacement()
        actions.onMoved(if (rightSide) SIDE_RIGHT else SIDE_LEFT, lp.y)
    }

    private fun showDismissTarget() {
        if (dismissView != null) return
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { WisperlowTheme(darkTheme = true) { DismissTarget() } }
        }
        val lp = baseParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(DISMISS_BOTTOM_DP)
        }
        runCatching { windowManager.addView(view, lp) }.onSuccess { dismissView = view }
    }

    private fun hideDismissTarget() {
        dismissView?.let { runCatching { windowManager.removeView(it) } }
        dismissView = null
    }

    // ---- UI ----

    @Composable
    private fun Bubble() {
        val state = ui
        val animations = ValueAnimator.areAnimatorsEnabled()
        val sizeSpec = if (animations) {
            spring<androidx.compose.ui.unit.IntSize>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
        } else {
            snap()
        }
        val shape = RoundedCornerShape(28.dp)
        val target = when (state) {
            is BubbleUi.Listening -> WisperlowColors.BubbleListening
            is BubbleUi.Flash -> if (state.success) WisperlowColors.BubbleSuccess else WisperlowColors.BubbleSurface
            else -> WisperlowColors.BubbleSurface
        }
        val color by animateColorAsState(target, if (animations) tween(260) else snap(), label = "bubbleColor")
        val anchor = if (rightSide) Alignment.TopEnd else Alignment.TopStart
        // The window is fixed-size while active; this box morphs inside it, anchored to the docked edge.
        Box(Modifier.fillMaxSize(), contentAlignment = anchor) {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .animateContentSize(animationSpec = sizeSpec, alignment = anchor)
                    .background(color.copy(alpha = 0.96f), shape)
                    .border(1.dp, WisperlowColors.BubbleOutline, shape)
                    .then(if (state is BubbleUi.Review) Modifier else Modifier.pointerInput(Unit) { gestures() }),
            ) {
                AnimatedContent(
                    targetState = state::class,
                    transitionSpec = {
                        fadeIn(tween(160, delayMillis = 60)) togetherWith fadeOut(tween(100)) using null
                    },
                    contentAlignment = anchor,
                    label = "bubbleContent",
                ) { _ -> BubbleBody(state, animations) }
            }
        }
    }

    @Composable
    private fun BubbleBody(state: BubbleUi, animations: Boolean) {
        when (state) {
            BubbleUi.Idle -> IdleDot()
            is BubbleUi.Listening -> Pill(
                text = when {
                    state.partial.isNotBlank() -> state.partial
                    state.modelLoading -> context.getString(R.string.bubble_loading_keep_talking)
                    else -> context.getString(R.string.bubble_listening)
                },
                hint = context.getString(R.string.bubble_tap_to_finish),
                description = context.getString(R.string.bubble_listening_description),
            ) { Waveform(animations) }
            is BubbleUi.Finishing -> Pill(
                text = state.partial.ifBlank { context.getString(R.string.bubble_transcribing) },
                hint = null,
                description = context.getString(R.string.bubble_transcribing),
            ) { PulsingDots(animations) }
            is BubbleUi.Review -> ReviewPanel()
            is BubbleUi.Flash -> Pill(
                text = state.message,
                hint = null,
                description = state.message,
            ) {
                Icon(
                    imageVector = if (state.success) Icons.Rounded.Check else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }

    @Composable
    private fun IdleDot() {
        Box(
            modifier = Modifier
                .size(DOT_DP.dp)
                .semantics {
                    contentDescription = context.getString(R.string.bubble_tap_to_start)
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    @Composable
    private fun Pill(
        text: String,
        hint: String?,
        description: String,
        visual: @Composable () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = DOT_DP.dp)
                .width(PILL_DP.dp)
                .padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 40.dp, height = 36.dp), contentAlignment = Alignment.Center) { visual() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    // Show the most recent words; the start is already on its way.
                    text = text.takeLast(MAX_PILL_CHARS).let { if (text.length > MAX_PILL_CHARS) "…$it" else it },
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    @Composable
    private fun ReviewPanel() {
        Column(
            modifier = Modifier
                .width(REVIEW_WIDTH_DP.dp)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = context.getString(R.string.bubble_review_title),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelMedium,
            )
            BasicTextField(
                value = reviewText,
                onValueChange = { reviewText = it },
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Color.White),
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = context.getString(R.string.bubble_review_text) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundAction(Icons.Rounded.Close, R.string.bubble_cancel, filled = false) { actions.onReviewCancel() }
                RoundAction(Icons.Rounded.ContentCopy, R.string.bubble_copy, filled = false) {
                    actions.onReviewCopy(reviewText)
                }
                RoundAction(Icons.Rounded.Check, R.string.bubble_insert, filled = true) {
                    actions.onReviewInsert(reviewText)
                }
            }
        }
    }

    @Composable
    private fun RoundAction(icon: ImageVector, labelRes: Int, filled: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(if (filled) Color.White else Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable(onClickLabel = context.getString(labelRes), onClick = onClick)
                .semantics { contentDescription = context.getString(labelRes) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (filled) WisperlowColors.BubbleSurface else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }

    @Composable
    private fun DismissTarget() {
        val scale by animateFloatAsState(if (overDismiss) 1.25f else 1f, label = "dismissScale")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .scale(scale)
                    .size(60.dp)
                    .background(
                        if (overDismiss) WisperlowColors.Danger else WisperlowColors.BubbleSurface.copy(alpha = 0.9f),
                        CircleShape,
                    )
                    .border(1.dp, WisperlowColors.BubbleOutline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.bubble_drop_to_hide),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }

    @Composable
    private fun Waveform(animations: Boolean) {
        val phase = if (animations) {
            val transition = rememberInfiniteTransition(label = "wave")
            val value by transition.animateFloat(
                initialValue = 0f,
                targetValue = (2f * Math.PI).toFloat(),
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "wavePhase",
            )
            value
        } else {
            0f
        }
        val loudness by animateFloatAsState(micLevel, animationSpec = tween(90), label = "level")
        Canvas(Modifier.size(width = 36.dp, height = 32.dp)) {
            val bars = 5
            val slot = size.width / bars
            val barWidth = slot * 0.5f
            val minH = size.height * 0.16f
            // Speech RMS rarely exceeds ~0.25, so boost it to use the full height.
            val amp = minH + (size.height * 0.9f - minH) * (loudness * 4f).coerceIn(0.08f, 1f)
            repeat(bars) { i ->
                val wave = 0.55f + 0.45f * sin(phase + i * 0.9f)
                val h = minH + (amp - minH) * wave
                val x = slot * i + slot / 2
                drawLine(
                    color = Color.White,
                    start = Offset(x, size.height / 2 - h / 2),
                    end = Offset(x, size.height / 2 + h / 2),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    @Composable
    private fun PulsingDots(animations: Boolean) {
        val pulse = if (animations) {
            val transition = rememberInfiniteTransition(label = "dots")
            val value by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "dotsPhase",
            )
            value
        } else {
            0.5f
        }
        Canvas(Modifier.size(width = 36.dp, height = 16.dp)) {
            repeat(3) { i ->
                val wave = sin((pulse * 2f * Math.PI).toFloat() - i * 0.9f)
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f + 0.65f * (0.5f + 0.5f * wave)),
                    radius = size.height * 0.28f,
                    center = Offset(size.width / 4f * (i + 1), size.height / 2),
                )
            }
        }
    }

    private suspend fun PointerInputScope.gestures() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var dragged = false
            var total = Offset.Zero
            var released = false
            var longPressed = false
            // While dictating, a slow tap must not cancel: require a deliberate, longer hold.
            val holdFactor = if (ui is BubbleUi.Idle) 1L else 2L
            val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis * holdFactor
            while (!released) {
                val remaining = longPressAt - SystemClock.uptimeMillis()
                val event = if (!dragged && !longPressed && remaining > 0) {
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    // The finger stayed still past the long-press timeout.
                    longPressed = true
                    bubbleView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    actions.onLongPress()
                    continue
                }
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) {
                    released = true
                    break
                }
                val delta = change.position - change.previousPosition
                total += delta
                if (!dragged && !longPressed && total.getDistance() > slop && ui is BubbleUi.Idle) {
                    dragged = true
                    startDrag()
                }
                if (dragged) {
                    moveBy(delta.x, delta.y)
                    change.consume()
                }
            }
            when {
                dragged -> endDrag()
                longPressed -> Unit
                else -> {
                    val now = SystemClock.uptimeMillis()
                    val acts = ui is BubbleUi.Idle || ui is BubbleUi.Listening
                    if (acts && now - lastTapAt > TAP_DEBOUNCE_MS) {
                        lastTapAt = now
                        // One short tick for start and for stop; nothing for ignored taps.
                        bubbleView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        actions.onTap()
                    }
                }
            }
        }
    }

    private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this).apply { performRestore(null) }

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            if (registry.currentState == Lifecycle.State.INITIALIZED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
            if (registry.currentState == Lifecycle.State.CREATED) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        fun destroy() {
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }

    private companion object {
        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1
        const val KEYBOARD_GAP_DP = 12
        const val DEFAULT_HEIGHT_FRACTION = 0.4f
        const val DOT_DP = 56
        const val EDGE_MARGIN_DP = 6
        const val TOP_MARGIN_DP = 48
        const val BOTTOM_MARGIN_DP = 24
        const val REVIEW_WIDTH_DP = 320
        const val REVIEW_HEIGHT_DP = 240
        const val DISMISS_RADIUS_DP = 72
        const val DISMISS_ZONE_DP = 190
        const val DISMISS_BOTTOM_DP = 56
        const val MAX_PILL_CHARS = 70
        const val PILL_DP = 240
        const val SHRINK_DELAY_MS = 450L
        const val TAP_DEBOUNCE_MS = 400L
    }
}
