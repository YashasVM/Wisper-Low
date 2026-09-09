package com.wisperlow.mobile.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sin

enum class BubbleMode { DOT, LISTENING, PROCESSING, REVIEW }

@Singleton
class BubbleOverlay @Inject constructor(@ApplicationContext private val context: Context) {

    var onTap: (() -> Unit)? = null
    var onCancelGesture: (() -> Unit)? = null
    var onConfirm: (() -> Unit)? = null

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val modeState: MutableState<BubbleMode> = mutableStateOf(BubbleMode.DOT)
    private val levelState: MutableState<Float> = mutableFloatStateOf(0f)
    private val reviewTextState: MutableState<String> = mutableStateOf("")
    private val posXState: MutableState<Int> = mutableIntStateOf(-1)
    private val posYState: MutableState<Int> = mutableIntStateOf(-1)

    private var view: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    val isVisible: Boolean
        get() = view != null

    fun show(mode: BubbleMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(mode) }
            return
        }
        val previousMode = modeState.value
        modeState.value = mode
        if (!Settings.canDrawOverlays(context)) return

        val existing = view
        if (existing == null) {
            try {
                attach()
            } catch (_: BadTokenException) {
                detachInternal()
            } catch (_: Exception) {
                detachInternal()
            }
        } else {
            updateLayoutForMode(existing, previousMode, mode)
        }
    }

    fun hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::hide)
            return
        }
        detachInternal()
    }

    fun setLevel(level: Float) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setLevel(level) }
            return
        }
        levelState.value = level.coerceIn(0f, 1f)
    }

    fun setReviewText(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setReviewText(text) }
            return
        }
        reviewTextState.value = text
    }

    private fun attach() {
        val owner = OverlayLifecycleOwner().also { it.createAndStart() }
        lifecycleOwner = owner

        val composeView = ComposeView(context)
        view = composeView
        composeView.setViewTreeLifecycleOwner(owner)
        composeView.setViewTreeViewModelStoreOwner(owner)
        composeView.setViewTreeSavedStateRegistryOwner(owner)

        composeView.setContent { BubbleContent() }

        val params = buildLayoutParams()
        applyDefaultPosition(params)

        composeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                owner.moveToResumed()
            }

            override fun onViewDetachedFromWindow(v: View) {
                owner.moveToStopped()
            }
        })

        windowManager.addView(composeView, params)
        posXState.value = params.x
        posYState.value = params.y
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun applyDefaultPosition(params: WindowManager.LayoutParams) {
        val metrics = try {
            windowManager.currentWindowMetrics
        } catch (_: Exception) {
            return
        }
        params.x = metrics.bounds.width() - DEFAULT_X_OFFSET_PX
        params.y = metrics.bounds.height() - DEFAULT_Y_OFFSET_PX
    }

    private fun updateLayoutForMode(
        target: View,
        previousMode: BubbleMode,
        newMode: BubbleMode,
    ) {
        try {
            val lp = target.layoutParams as? WindowManager.LayoutParams ?: return
            val density = context.resources.displayMetrics.density
            val previousWidth = modeWidth(previousMode) * density
            val newWidth = modeWidth(newMode) * density
            val rightEdge = posXState.value + previousWidth.toInt()
            val screenWidth = windowManager.currentWindowMetrics.bounds.width()
            lp.x = (rightEdge - newWidth.toInt()).coerceIn(
                0,
                (screenWidth - newWidth.toInt()).coerceAtLeast(0),
            )
            lp.y = posYState.value
            posXState.value = lp.x
            windowManager.updateViewLayout(target, lp)
        } catch (_: Exception) {
        }
    }

    private fun modeWidth(mode: BubbleMode): Int = when (mode) {
        BubbleMode.DOT -> DOT_SIZE
        BubbleMode.REVIEW -> REVIEW_WIDTH
        BubbleMode.LISTENING, BubbleMode.PROCESSING -> PILL_WIDTH
    }

    private fun detachInternal() {
        val target = view ?: return
        view = null
        try {
            windowManager.removeView(target)
        } catch (_: Exception) {
        }
        try {
            lifecycleOwner?.destroy()
        } catch (_: Exception) {
        }
        lifecycleOwner = null
    }

    private inner class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val viewModelStoreInstance = ViewModelStore()

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = viewModelStoreInstance

        init {
            savedStateRegistryController.performRestore(null)
        }

        fun createAndStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun moveToResumed() {
            if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        fun moveToStopped() {
            if (lifecycleRegistry.currentState > Lifecycle.State.CREATED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }

        fun destroy() {
            moveToStopped()
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                viewModelStoreInstance.clear()
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }

    @Composable
    private fun BubbleContent() {
        val mode by modeState

        val targetWidth = when (mode) {
            BubbleMode.DOT -> DOT_SIZE.dp
            BubbleMode.REVIEW -> REVIEW_WIDTH.dp
            else -> PILL_WIDTH.dp
        }
        val targetHeight = when (mode) {
            BubbleMode.DOT -> DOT_SIZE.dp
            else -> PILL_HEIGHT.dp
        }

        val animatedWidth by animateDpAsState(
            targetValue = targetWidth,
            animationSpec = tween(durationMillis = ANIM_DURATION_MS),
            label = "bubbleWidth"
        )
        val animatedHeight by animateDpAsState(
            targetValue = targetHeight,
            animationSpec = tween(durationMillis = ANIM_DURATION_MS),
            label = "bubbleHeight"
        )
        val cornerRadius by animateDpAsState(
            targetValue = targetWidth.coerceAtMost(targetHeight) / 2f,
            animationSpec = tween(durationMillis = ANIM_DURATION_MS),
            label = "bubbleCorner"
        )

        Box(
            modifier = Modifier
                .width(animatedWidth)
                .height(animatedHeight)
                .background(
                    color = Color(0xFF0A0A0A).copy(alpha = BACKGROUND_ALPHA),
                    shape = RoundedCornerShape(cornerRadius)
                )
                .then(
                    if (mode == BubbleMode.REVIEW) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) { handleGestures() }
                    },
                ),
            contentAlignment = Alignment.Center
        ) {
            when (mode) {
                BubbleMode.DOT -> MicGlyph(modifier = Modifier.size(DOT_SIZE.dp))
                BubbleMode.LISTENING -> Waveform(modifier = Modifier.fillMaxSize())
                BubbleMode.PROCESSING -> PulsingDots()
                BubbleMode.REVIEW -> ReviewControls()
            }
        }
    }

    @Composable
    private fun ReviewControls() {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            ReviewAction(
                contentDescription = context.getString(com.wisperlow.mobile.R.string.bubble_cancel),
                confirm = false,
                onClick = { onCancelGesture?.invoke() },
            )
            Text(
                text = reviewTextState.value,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            ReviewAction(
                contentDescription = context.getString(com.wisperlow.mobile.R.string.bubble_insert),
                confirm = true,
                onClick = { onConfirm?.invoke() },
            )
        }
    }

    @Composable
    private fun ReviewAction(
        contentDescription: String,
        confirm: Boolean,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(PILL_HEIGHT.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(40.dp)) {
                drawCircle(
                    color = if (confirm) Color(0xFF8E78FF) else Color.White.copy(alpha = 0.12f),
                )
                val strokeWidth = 2.5.dp.toPx()
                if (confirm) {
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.27f, size.height * 0.52f),
                        end = Offset(size.width * 0.43f, size.height * 0.68f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.43f, size.height * 0.68f),
                        end = Offset(size.width * 0.74f, size.height * 0.34f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.34f, size.height * 0.34f),
                        end = Offset(size.width * 0.66f, size.height * 0.66f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.66f, size.height * 0.34f),
                        end = Offset(size.width * 0.34f, size.height * 0.66f),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }

    @Composable
    private fun MicGlyph(modifier: Modifier) {
        Canvas(modifier = modifier.alpha(1f)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val strokeW = size.width * 0.075f
            val bodyWidth = size.width * 0.17f
            val bodyHeight = size.height * 0.30f
            val bodyTop = cy - bodyHeight - size.height * 0.06f

            drawRoundRect(
                color = Color.White.copy(alpha = 0.85f),
                topLeft = Offset(cx - bodyWidth / 2f, bodyTop),
                size = Size(bodyWidth, bodyHeight),
                cornerRadius = CornerRadius(bodyWidth / 2f, bodyWidth / 2f)
            )
            val arcRadius = size.width * 0.20f
            drawArc(
                color = Color.White.copy(alpha = 0.85f),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - arcRadius, cy - arcRadius * 0.45f),
                size = Size(arcRadius * 2f, arcRadius * 1.7f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(cx, cy + arcRadius * 1.25f),
                end = Offset(cx, cy + arcRadius * 1.9f),
                strokeWidth = strokeW,
                cap = StrokeCap.Round
            )
        }
    }

    @Composable
    private fun Waveform(modifier: Modifier) {
        val level by levelState
        val transition = rememberInfiniteTransition(label = "waveform")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI.toFloat()) * 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = WAVE_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wavePhase"
        )
        Canvas(modifier = modifier) {
            val barCount = 5
            val slot = size.width / (barCount + 1f)
            val barWidth = slot * 0.42f
            val centerY = size.height / 2f
            val minHeight = size.height * 0.14f
            val maxHeight = size.height * 0.60f
            val amplitude = minHeight + (maxHeight - minHeight) * level.coerceIn(0.12f, 1f)

            repeat(barCount) { index ->
                val wave = 0.5f + 0.5f * sin(phase + index * BAR_PHASE_OFFSET)
                val h = minHeight + (amplitude - minHeight) * wave
                val x = slot * (index + 1) - barWidth / 2f + barWidth / 2f
                drawLine(
                    color = Color.White,
                    start = Offset(x, centerY - h / 2f),
                    end = Offset(x, centerY + h / 2f),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }
    }

    @Composable
    private fun PulsingDots() {
        val transition = rememberInfiniteTransition(label = "dots")
        val pulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PULSE_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotPulse"
        )
        Canvas(modifier = Modifier.size(width = DOTS_WIDTH.dp, height = DOTS_HEIGHT.dp)) {
            val dotRadius = size.height * 0.22f
            val spacing = size.width / 4f
            repeat(3) { index ->
                val wave = sin(pulse * 2f * Math.PI.toFloat() + index * DOT_PHASE_OFFSET)
                val alpha = 0.35f + 0.65f * (0.5f + 0.5f * wave)
                drawCircle(
                    color = Color.White.copy(alpha = alpha),
                    radius = dotRadius,
                    center = Offset(spacing * (index + 1), size.height / 2f)
                )
            }
        }
    }

    private suspend fun PointerInputScope.handleGestures() {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPos = down.position
            val downTime = down.uptimeMillis
            val slop = viewConfiguration.touchSlop
            var dragged = false
            var dragStartLp: WindowManager.LayoutParams? = null
            var lastEventTime = downTime

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                lastEventTime = event.changes.maxOf { it.uptimeMillis }

                if (change.pressed) {
                    if (!dragged && (change.position - downPos).getDistance() > slop) {
                        dragged = true
                        dragStartLp = view?.layoutParams as? WindowManager.LayoutParams
                    }
                    if (dragged) {
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            moveWindowBy(delta.x.toInt(), delta.y.toInt(), dragStartLp)
                            change.consume()
                        }
                    }
                } else {
                    break
                }
            }

            if (!dragged) {
                val elapsed = lastEventTime - downTime
                if (elapsed < viewConfiguration.longPressTimeoutMillis) {
                    onTap?.invoke()
                } else {
                    onCancelGesture?.invoke()
                }
            }
        }
    }

    private fun moveWindowBy(dx: Int, dy: Int, startLp: WindowManager.LayoutParams?) {
        val target = view ?: return
        val baseLp = startLp ?: target.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = try {
            windowManager.currentWindowMetrics
        } catch (_: Exception) {
            return
        }
        val screenW = metrics.bounds.width()
        val screenH = metrics.bounds.height()
        val viewW = if (target.isLaidOut && target.width > 0) target.width else 220
        val viewH = if (target.isLaidOut && target.height > 0) target.height else 128
        val newX = (baseLp.x + dx).coerceIn(0, (screenW - viewW).coerceAtLeast(0))
        val newY = (baseLp.y + dy).coerceIn(0, (screenH - viewH).coerceAtLeast(0))
        posXState.value = newX
        posYState.value = newY
        try {
            baseLp.x = newX
            baseLp.y = newY
            windowManager.updateViewLayout(target, baseLp)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val DOT_SIZE = 56
        private const val PILL_WIDTH = 180
        private const val REVIEW_WIDTH = 320
        private const val PILL_HEIGHT = 64
        private const val DOTS_WIDTH = 90
        private const val DOTS_HEIGHT = 24
        private const val BACKGROUND_ALPHA = 0.95f
        private const val ANIM_DURATION_MS = 250
        private const val WAVE_DURATION_MS = 900
        private const val PULSE_DURATION_MS = 800
        private const val BAR_PHASE_OFFSET = 0.9f
        private const val DOT_PHASE_OFFSET = 0.8f
        private const val DEFAULT_X_OFFSET_PX = 220
        private const val DEFAULT_Y_OFFSET_PX = 420
    }
}
