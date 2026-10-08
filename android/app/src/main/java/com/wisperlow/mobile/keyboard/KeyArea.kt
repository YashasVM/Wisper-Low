package com.wisperlow.mobile.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisperlow.mobile.R
import com.wisperlow.mobile.ui.MonoFamily
import com.wisperlow.mobile.ui.SansFamily
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ShiftState { OFF, ONCE, LOCKED }

enum class EnterAction { NEWLINE, GO, SEARCH, SEND, NEXT, PREVIOUS, DONE }

/** What the key area reports; the keyboard service turns these into edits. */
interface KeyListener {
    fun onDown(key: Key)
    fun onText(text: String)
    fun onKey(key: Key)
    fun onBackspace(repeats: Int)
    fun onCursor(steps: Int)
    fun onSpaceLongPress()
}

private class KeyRect(val key: Key, val rect: Rect)

private enum class Mode { TAP, PRIMARY, ALTERNATES, CURSOR, REPEAT, DONE }

private class Track(val key: KeyRect, val start: Offset) {
    var mode = Mode.TAP
    var job: Job? = null
    var cursorSteps = 0
}

private class Alternates(val key: KeyRect, val options: List<String>, val selected: Int)

/** Rows of keycaps with one touch handler for the whole board, so fast typing never drops a key. */
@Composable
fun KeyArea(
    rows: List<KeyRow>,
    shift: ShiftState,
    enter: EnterAction,
    rowHeight: androidx.compose.ui.unit.Dp,
    listener: KeyListener,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis.coerceAtMost(LONG_PRESS_MS)
    val events by rememberUpdatedState(listener)
    val upper = shift != ShiftState.OFF
    // Read through state so toggling shift mid-gesture does not restart touch handling.
    val upperNow by rememberUpdatedState(upper)
    BoxWithConstraints(modifier.fillMaxWidth().height(rowHeight * rows.size)) {
        val widthPx = constraints.maxWidth.toFloat()
        val rowPx = with(density) { rowHeight.toPx() }
        val sidePx = with(density) { SIDE_PADDING.toPx() }
        val rects = remember(rows, widthPx, rowPx) { layoutKeys(rows, widthPx, rowPx, sidePx) }
        val pressed = remember { mutableStateMapOf<PointerId, KeyRect>() }
        var alternates by remember { mutableStateOf<Alternates?>(null) }
        /** A key held long enough to type its long-press character (the top-row digit). */
        var primary by remember { mutableStateOf<KeyRect?>(null) }
        val view = LocalView.current
        val cursorStepPx = with(density) { CURSOR_STEP.toPx() }
        val slopPx = with(density) { CURSOR_SLOP.toPx() }

        fun casedOptions(key: Key): List<String> =
            if (upperNow) key.alternates.map { it.uppercase() } else key.alternates

        fun hit(p: Offset): KeyRect? {
            if (rects.isEmpty()) return null
            val row = (p.y / rowPx).toInt().coerceIn(0, rows.lastIndex)
            val inRow = rects.filter { (it.rect.top / rowPx).roundToInt() == row }
            return inRow.firstOrNull { p.x >= it.rect.left && p.x < it.rect.right }
                ?: inRow.minByOrNull { abs(it.rect.center.x - p.x) }
        }

        fun release(t: Track) {
            t.job?.cancel()
            when (t.mode) {
                Mode.TAP -> when (t.key.key.kind) {
                    KeyKind.CHAR -> events.onText(t.key.key.text.let { if (upperNow) it.uppercase() else it })
                    KeyKind.SPACE, KeyKind.ENTER, KeyKind.PAGE, KeyKind.EMOJI -> events.onKey(t.key.key)
                    else -> Unit
                }
                Mode.PRIMARY -> t.key.key.longPress?.let(events::onText)
                Mode.ALTERNATES -> alternates?.let { a -> a.options.getOrNull(a.selected)?.let(events::onText) }
                else -> Unit
            }
            if (t.mode == Mode.ALTERNATES) alternates = null
            if (primary == t.key) primary = null
            t.mode = Mode.DONE
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(rects) {
                    coroutineScope {
                        val tracks = HashMap<PointerId, Track>()
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (change in event.changes) {
                                    val id = change.id
                                    when {
                                        change.changedToDownIgnoreConsumed() -> {
                                            // Rollover: a second finger landing commits the key still held by the first.
                                            tracks.values.filter { it.mode == Mode.TAP && it.key.key.kind == KeyKind.CHAR }.forEach {
                                                release(it)
                                            }
                                            val target = hit(change.position) ?: continue
                                            val t = Track(target, change.position)
                                            tracks[id] = t
                                            pressed[id] = target
                                            events.onDown(target.key)
                                            when (target.key.kind) {
                                                KeyKind.SHIFT -> {
                                                    events.onKey(target.key)
                                                    t.mode = Mode.DONE
                                                }
                                                KeyKind.BACKSPACE -> {
                                                    t.mode = Mode.REPEAT
                                                    events.onBackspace(0)
                                                    t.job = launch {
                                                        delay(REPEAT_START_MS)
                                                        var n = 1
                                                        while (true) {
                                                            events.onBackspace(n++)
                                                            delay(if (n > WORD_DELETE_AFTER) WORD_REPEAT_MS else REPEAT_MS)
                                                        }
                                                    }
                                                }
                                                KeyKind.CHAR -> if (target.key.alternates.isNotEmpty() || target.key.longPress != null) {
                                                    t.job = launch {
                                                        delay(longPress)
                                                        if (t.mode != Mode.TAP) return@launch
                                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                                        val digit = target.key.longPress
                                                        if (digit != null) {
                                                            // The number comes first; keep holding for accents.
                                                            t.mode = Mode.PRIMARY
                                                            primary = target
                                                            val more = target.key.alternates.size > 1
                                                            if (!more) return@launch
                                                            delay(ACCENT_DELAY_MS)
                                                            if (t.mode != Mode.PRIMARY) return@launch
                                                            primary = null
                                                        }
                                                        t.mode = Mode.ALTERNATES
                                                        alternates = Alternates(target, casedOptions(target.key), 0)
                                                    }
                                                }
                                                KeyKind.SPACE -> t.job = launch {
                                                    delay(longPress * 2)
                                                    if (t.mode == Mode.TAP) {
                                                        t.mode = Mode.DONE
                                                        pressed.remove(id)
                                                        events.onSpaceLongPress()
                                                    }
                                                }
                                                else -> Unit
                                            }
                                        }
                                        change.changedToUpIgnoreConsumed() -> {
                                            pressed.remove(id)
                                            tracks.remove(id)?.let(::release)
                                        }
                                        change.positionChanged() -> {
                                            val t = tracks[id] ?: continue
                                            val dx = change.position.x - t.start.x
                                            when {
                                                t.mode == Mode.ALTERNATES -> alternates?.let { a ->
                                                    val cell = altCellWidth(a.key.rect)
                                                    val left = altLeft(a, cell, widthPx)
                                                    val index = ((change.position.x - left) / cell).toInt().coerceIn(0, a.options.lastIndex)
                                                    if (index != a.selected) alternates = Alternates(a.key, a.options, index)
                                                }
                                                t.key.key.kind == KeyKind.SPACE && (t.mode == Mode.CURSOR || abs(dx) > slopPx) -> {
                                                    // Drag along space to move the cursor, one step per few millimetres.
                                                    t.job?.cancel()
                                                    t.mode = Mode.CURSOR
                                                    val steps = (dx / cursorStepPx).toInt()
                                                    if (steps != t.cursorSteps) {
                                                        events.onCursor(steps - t.cursorSteps)
                                                        t.cursorSteps = steps
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            val pressedKeys = pressed.values.toSet()
            for (r in rects) {
                KeyCap(r, r in pressedKeys, upper, shift, enter, density)
            }
            // Preview bubble over a pressed letter, the way your thumb would hide it.
            for (r in pressedKeys) {
                if (r.key.kind == KeyKind.CHAR && alternates?.key != r) {
                    KeyPreview(r, if (primary == r) r.key.longPress.orEmpty() else r.key.label.let { if (upper) it.uppercase() else it }, density)
                }
            }
            alternates?.let { AlternatesPopup(it, widthPx, density) }
        }
    }
}

private fun layoutKeys(rows: List<KeyRow>, width: Float, rowHeight: Float, side: Float): List<KeyRect> {
    val out = ArrayList<KeyRect>()
    rows.forEachIndexed { r, row ->
        val unit = (width - side * 2) / row.units
        var x = side + row.inset * unit
        for (key in row.keys) {
            val w = key.width * unit
            out += KeyRect(key, Rect(x, r * rowHeight, x + w, (r + 1) * rowHeight))
            x += w
        }
    }
    return out
}

@Composable
private fun KeyCap(
    r: KeyRect,
    pressed: Boolean,
    upper: Boolean,
    shift: ShiftState,
    enter: EnterAction,
    density: androidx.compose.ui.unit.Density,
) {
    val colors = LocalKeyboardColors.current
    val key = r.key
    val isMod = key.kind != KeyKind.CHAR && key.kind != KeyKind.SPACE
    val shiftOn = key.kind == KeyKind.SHIFT && shift != ShiftState.OFF
    val fill = when {
        key.kind == KeyKind.ENTER -> if (pressed) colors.accent.copy(alpha = 0.85f) else colors.accent
        shiftOn -> colors.accentContainer
        pressed -> colors.keyPressed
        isMod -> colors.modKey
        else -> colors.key
    }
    val content = when {
        key.kind == KeyKind.ENTER -> colors.onAccent
        shiftOn -> colors.onAccentContainer
        isMod -> colors.modText
        else -> colors.keyText
    }
    val description = when (key.kind) {
        KeyKind.SHIFT -> stringResource(
            when (shift) {
                ShiftState.OFF -> R.string.keyboard_shift
                ShiftState.ONCE -> R.string.keyboard_shift_on
                ShiftState.LOCKED -> R.string.keyboard_caps_lock
            },
        )
        KeyKind.BACKSPACE -> stringResource(R.string.keyboard_backspace)
        KeyKind.SPACE -> stringResource(R.string.keyboard_space)
        KeyKind.ENTER -> stringResource(R.string.keyboard_enter)
        KeyKind.EMOJI -> stringResource(R.string.keyboard_emoji)
        else -> if (upper) key.label.uppercase() else key.label
    }
    val animatedFill by animateColorAsState(fill, tween(if (pressed) 40 else 160), label = "keyFill")
    val press by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 1400f), label = "keyPress")
    with(density) {
        Box(
            Modifier
                .offset { IntOffset(r.rect.left.roundToInt(), r.rect.top.roundToInt()) }
                .size(r.rect.width.toDp(), r.rect.height.toDp())
                .padding(horizontal = KEY_GAP_H, vertical = KEY_GAP_V)
                .graphicsLayer {
                    scaleX = press
                    scaleY = press
                }
                .semantics { contentDescription = description },
        ) {
            // A one-pixel shadow under each cap, just enough to lift it off the canvas.
            if (!isMod && key.kind != KeyKind.ENTER) {
                Box(Modifier.fillMaxSize().padding(top = 1.dp).background(colors.keyShadow, KeyShape))
            }
            Box(
                Modifier.fillMaxSize().padding(bottom = 1.dp).clip(KeyShape).background(animatedFill),
                contentAlignment = Alignment.Center,
            ) {
                when (key.kind) {
                    KeyKind.CHAR -> {
                        val letter = key.label.length == 1 && key.label[0].isLetter()
                        Text(
                            if (upper) key.label.uppercase() else key.label,
                            color = content,
                            style = TextStyle(
                                fontFamily = SansFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = if (letter) 21.sp else if (key.label.length > 2) 14.sp else 19.sp,
                            ),
                        )
                        key.hint?.let {
                            Text(
                                it,
                                color = colors.keyHint,
                                style = TextStyle(fontFamily = MonoFamily, fontSize = 8.sp),
                                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 5.dp),
                            )
                        }
                    }
                    KeyKind.SHIFT -> ShiftGlyph(shift, content)
                    KeyKind.BACKSPACE -> Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = content, modifier = Modifier.size(20.dp))
                    KeyKind.ENTER -> Icon(enterIcon(enter), null, tint = content, modifier = Modifier.size(20.dp))
                    KeyKind.EMOJI -> Icon(Icons.Outlined.EmojiEmotions, null, tint = content, modifier = Modifier.size(20.dp))
                    KeyKind.PAGE -> Text(
                        key.label,
                        color = content,
                        style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp),
                    )
                    KeyKind.SPACE -> Unit
                }
            }
        }
    }
}

/** Outline arrow when off, filled for one capital, filled with a bar for caps lock. */
@Composable
private fun ShiftGlyph(shift: ShiftState, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val arrow = Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            lineTo(w * 0.95f, h * 0.55f)
            lineTo(w * 0.7f, h * 0.55f)
            lineTo(w * 0.7f, h * 0.78f)
            lineTo(w * 0.3f, h * 0.78f)
            lineTo(w * 0.3f, h * 0.55f)
            lineTo(w * 0.05f, h * 0.55f)
            close()
        }
        if (shift == ShiftState.OFF) {
            drawPath(arrow, tint, style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round))
        } else {
            drawPath(arrow, tint, style = Fill)
        }
        if (shift == ShiftState.LOCKED) {
            drawRect(tint, topLeft = Offset(w * 0.3f, h * 0.88f), size = androidx.compose.ui.geometry.Size(w * 0.4f, h * 0.1f))
        }
    }
}

private fun enterIcon(action: EnterAction) = when (action) {
    EnterAction.NEWLINE -> Icons.AutoMirrored.Rounded.KeyboardReturn
    EnterAction.GO, EnterAction.NEXT -> Icons.AutoMirrored.Rounded.ArrowForward
    EnterAction.PREVIOUS -> Icons.AutoMirrored.Rounded.ArrowBack
    EnterAction.SEARCH -> Icons.Rounded.Search
    EnterAction.SEND -> Icons.AutoMirrored.Rounded.Send
    EnterAction.DONE -> Icons.Rounded.Check
}

@Composable
private fun KeyPreview(r: KeyRect, label: String, density: androidx.compose.ui.unit.Density) {
    val colors = LocalKeyboardColors.current
    val pop = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 900f)) }
    with(density) {
        val w = r.rect.width + 8.dp.toPx()
        val h = r.rect.height * 1.05f
        Box(
            Modifier
                .offset { IntOffset((r.rect.center.x - w / 2).roundToInt(), (r.rect.top - h + 4.dp.toPx()).roundToInt()) }
                .size(w.toDp(), h.toDp())
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .shadow(10.dp, PopupShape, ambientColor = colors.keyShadow, spotColor = colors.keyShadow)
                .background(colors.popup, PopupShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = colors.popupText,
                style = TextStyle(fontFamily = SansFamily, fontSize = 28.sp),
            )
        }
    }
}

private fun altCellWidth(key: Rect) = key.width * 0.92f

private fun altLeft(a: Alternates, cell: Float, width: Float): Float {
    val total = cell * a.options.size
    return (a.key.rect.center.x - cell / 2).coerceAtMost(width - total - 4f).coerceAtLeast(4f)
}

@Composable
private fun AlternatesPopup(a: Alternates, width: Float, density: androidx.compose.ui.unit.Density) {
    val colors = LocalKeyboardColors.current
    with(density) {
        val cell = altCellWidth(a.key.rect)
        val left = altLeft(a, cell, width)
        val h = a.key.rect.height * 0.95f
        Row(
            Modifier
                .offset { IntOffset(left.roundToInt(), (a.key.rect.top - h).roundToInt()) }
                .height(h.toDp())
                .shadow(12.dp, PopupShape, ambientColor = colors.keyShadow, spotColor = colors.keyShadow)
                .background(colors.popup, PopupShape)
                .padding(4.dp),
        ) {
            a.options.forEachIndexed { i, option ->
                val selected = i == a.selected
                Box(
                    Modifier
                        .size((cell - 8.dp.toPx() / a.options.size).toDp(), (h - 8.dp.toPx()).toDp())
                        .background(if (selected) colors.accent else Color.Transparent, KeyShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        color = if (selected) colors.onAccent else colors.popupText,
                        style = TextStyle(fontFamily = SansFamily, fontSize = if (option.length > 2) 13.sp else 20.sp),
                    )
                }
            }
        }
    }
}

private val KeyShape = RoundedCornerShape(8.dp)
private val PopupShape = RoundedCornerShape(14.dp)
private val SIDE_PADDING = 4.dp
private val KEY_GAP_H = 3.dp
private val KEY_GAP_V = 5.dp
private val CURSOR_STEP = 9.dp
private val CURSOR_SLOP = 14.dp
private const val LONG_PRESS_MS = 320L
private const val REPEAT_START_MS = 380L
private const val REPEAT_MS = 55L
private const val WORD_REPEAT_MS = 160L
private const val ACCENT_DELAY_MS = 450L

/** After this many single-character repeats, held backspace deletes whole words. */
const val WORD_DELETE_AFTER = 20
