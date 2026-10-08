package com.wisperlow.mobile.keyboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisperlow.mobile.R
import com.wisperlow.mobile.dictation.DictationError
import com.wisperlow.mobile.dictation.DictationState
import com.wisperlow.mobile.ui.AuroraOrb
import com.wisperlow.mobile.ui.MonoFamily
import com.wisperlow.mobile.ui.OrbGlyph
import com.wisperlow.mobile.ui.OrbSpec
import com.wisperlow.mobile.ui.SansFamily
import com.wisperlow.mobile.ui.SerifFamily
import com.wisperlow.mobile.ui.errorText
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Everything the keyboard draws besides the live dictation state. */
data class KeyboardUi(
    val page: Page = Page.LETTERS,
    val shift: ShiftState = ShiftState.OFF,
    val field: FieldKind = FieldKind.TEXT,
    val enter: EnterAction = EnterAction.NEWLINE,
    val numberRow: Boolean = false,
    val suggestions: Suggestions = Suggestions.None,
    val emojiHints: List<String> = emptyList(),
    /** Recently copied text offered for pasting. */
    val clip: String? = null,
    /** Transcript waiting for Insert or Discard ("Review before inserting"). */
    val review: String? = null,
    val error: DictationError? = null,
    /** The last dictation can still be taken back with one tap. */
    val canUndoDictation: Boolean = false,
    val recentEmoji: List<String> = emptyList(),
    val clips: List<ClipEntry> = emptyList(),
    /** The tools row (clipboard, emoji, resize, settings) replaces the suggestions. */
    val tools: Boolean = false,
    val resizing: Boolean = false,
    val heightScale: Float = 1f,
)

class KeyboardActions(
    val keys: KeyListener,
    val onSuggestion: (String) -> Unit,
    val onEmoji: (String) -> Unit,
    val onPaste: () -> Unit,
    val onDismissClip: () -> Unit,
    val onMicTap: () -> Unit,
    val onMicHold: () -> Unit,
    val onMicRelease: () -> Unit,
    val onCancelVoice: () -> Unit,
    val onUndoDictation: () -> Unit,
    val onInsertReview: () -> Unit,
    val onDiscardReview: () -> Unit,
    val onDismissError: () -> Unit,
    val onOpenApp: () -> Unit,
    val onToggleTools: () -> Unit,
    val onOpenPage: (Page) -> Unit,
    val onStartResize: () -> Unit,
    val onResize: (Float) -> Unit,
    val onResetSize: () -> Unit,
    val onEndResize: () -> Unit,
    val onPasteText: (String) -> Unit,
    val onTogglePin: (String) -> Unit,
    val onDeleteClip: (String) -> Unit,
    val onClearClips: () -> Unit,
)

private enum class Panel { KEYS, EMOJI, CLIPBOARD, VOICE }

private enum class StripMode { NORMAL, TOOLS, RESIZE, VOICE, REVIEW, ERROR }

@Composable
fun KeyboardScreen(ui: KeyboardUi, dictation: DictationState, level: () -> Float, actions: KeyboardActions) {
    val colors = keyboardColors()
    CompositionLocalProvider(LocalKeyboardColors provides colors) {
        val voice = dictation !is DictationState.Idle
        // The whole board warms to violet while it is listening, so voice mode is unmistakable.
        val washTop by animateColorAsState(
            if (voice) colors.accent.copy(alpha = 0.28f) else colors.wash.copy(alpha = 0.7f),
            tween(420),
            label = "wash",
        )
        val washReach by animateFloatAsState(if (voice) 0.75f else 0.3f, tween(420), label = "washReach")
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .background(Brush.verticalGradient(0f to washTop, washReach to colors.background))
                .navigationBarsPadding()
                .padding(bottom = 4.dp),
        ) {
            Strip(ui, dictation, level, actions)
            // One height across pages, so switching never makes the app below jump.
            val letterRows = if (ui.numberRow && ui.page != Page.NUMBERS && ui.page != Page.PHONE) 5 else 4
            val rowHeight = BASE_ROW_HEIGHT * ui.heightScale
            val areaHeight = rowHeight * letterRows
            val panel = when {
                voice -> Panel.VOICE
                ui.page == Page.EMOJI -> Panel.EMOJI
                ui.page == Page.CLIPBOARD -> Panel.CLIPBOARD
                else -> Panel.KEYS
            }
            Box(Modifier.fillMaxWidth().height(areaHeight)) {
                AnimatedContent(
                    targetState = panel,
                    transitionSpec = {
                        if (targetState == Panel.VOICE || initialState == Panel.VOICE) {
                            (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 8 }) togetherWith
                                (fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 8 })
                        } else {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(140))
                        }
                    },
                    label = "panel",
                ) { shown ->
                    when (shown) {
                        Panel.VOICE -> VoiceSheet(dictation, level, actions)
                        Panel.EMOJI -> EmojiPanel(ui.recentEmoji, actions)
                        Panel.CLIPBOARD -> ClipboardPanel(ui.clips, actions)
                        Panel.KEYS -> AnimatedContent(
                            targetState = ui.page,
                            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                            label = "page",
                        ) { page ->
                            val rows = remember(page, ui.field, ui.numberRow) { KeyboardLayout.rows(page, ui.field, ui.numberRow) }
                            if (rows.isNotEmpty()) KeyArea(rows, ui.shift, ui.enter, areaHeight / rows.size, actions.keys)
                        }
                    }
                }
                if (ui.resizing) ResizeOverlay()
            }
        }
    }
}

@Composable
private fun Strip(ui: KeyboardUi, dictation: DictationState, level: () -> Float, actions: KeyboardActions) {
    val mode = when {
        dictation !is DictationState.Idle -> StripMode.VOICE
        ui.resizing -> StripMode.RESIZE
        ui.review != null -> StripMode.REVIEW
        ui.error != null -> StripMode.ERROR
        ui.tools -> StripMode.TOOLS
        else -> StripMode.NORMAL
    }
    Row(
        Modifier.fillMaxWidth().height(STRIP_HEIGHT).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mode != StripMode.VOICE && mode != StripMode.RESIZE) ToolsButton(ui.tools, actions.onToggleTools)
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 3 }) togetherWith fadeOut(tween(120))
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = "strip",
        ) { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                when (shown) {
                    StripMode.VOICE -> VoiceStatus(dictation, level)
                    StripMode.RESIZE -> ResizeBar(ui.heightScale, actions)
                    StripMode.REVIEW -> ReviewStrip(ui.review.orEmpty(), actions)
                    StripMode.ERROR -> ui.error?.let { ErrorStrip(it, actions) }
                    StripMode.TOOLS -> ToolsRow(actions)
                    StripMode.NORMAL -> NormalStrip(ui, actions)
                }
            }
        }
        if (mode != StripMode.RESIZE) MicButton(dictation, level, actions)
    }
}

@Composable
private fun NormalStrip(ui: KeyboardUi, actions: KeyboardActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (ui.canUndoDictation) {
            Chip(stringResource(R.string.keyboard_undo_dictation), Icons.AutoMirrored.Rounded.Undo, false, actions.onUndoDictation)
        }
        val words = ui.suggestions.words
        val clip = ui.clip
        when {
            words.isNotEmpty() -> Suggestions(words, ui.suggestions.autoCorrect, ui.emojiHints, actions, Modifier.weight(1f))
            clip != null -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(4.dp))
                ClipChip(clip, actions.onPaste, Modifier.weight(1f, fill = false))
                IconTap(Icons.Rounded.Close, stringResource(R.string.keyboard_cancel), actions.onDismissClip)
            }
            else -> Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReviewStrip(review: String, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            review,
            color = colors.stripText,
            style = TextStyle(fontFamily = SansFamily, fontSize = 14.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        Chip(stringResource(R.string.keyboard_discard), null, false, actions.onDiscardReview)
        Spacer(Modifier.width(6.dp))
        Chip(stringResource(R.string.keyboard_insert), Icons.Rounded.Check, true, actions.onInsertReview)
    }
}

@Composable
private fun ErrorStrip(error: DictationError, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(errorText(error)),
            color = colors.error,
            style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        if (error == DictationError.NO_MODEL || error == DictationError.MIC_PERMISSION) {
            Chip(stringResource(R.string.keyboard_fix_in_app), null, true, actions.onOpenApp)
        }
        IconTap(Icons.Rounded.Close, stringResource(R.string.keyboard_cancel), actions.onDismissError)
    }
}

@Composable
private fun ToolsRow(actions: KeyboardActions) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        Tool(Icons.Outlined.ContentPaste, stringResource(R.string.keyboard_tool_clipboard)) { actions.onOpenPage(Page.CLIPBOARD) }
        Tool(Icons.Outlined.EmojiEmotions, stringResource(R.string.keyboard_tool_emoji)) { actions.onOpenPage(Page.EMOJI) }
        Tool(Icons.Rounded.Height, stringResource(R.string.keyboard_tool_resize), actions.onStartResize)
        Tool(Icons.Outlined.Settings, stringResource(R.string.keyboard_tool_settings), actions.onOpenApp)
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = colors.stripText, modifier = Modifier.size(20.dp))
        Text(
            label.uppercase(),
            color = colors.stripMuted,
            style = TextStyle(fontFamily = MonoFamily, fontSize = 8.sp, letterSpacing = 0.8.sp),
        )
    }
}

/** Drag anywhere on the bar: up for taller keys, down for shorter. */
@Composable
private fun ResizeBar(scale: Float, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    val density = LocalDensity.current
    val current by rememberUpdatedState(scale)
    val resize by rememberUpdatedState(actions.onResize)
    Row(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.accentContainer)
            .pointerInput(Unit) {
                val fullPx = with(density) { (BASE_ROW_HEIGHT * 4).toPx() }
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    resize(current - dy / fullPx)
                }
            }
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.DragHandle, null, tint = colors.onAccentContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.keyboard_resize_hint).uppercase(),
            color = colors.onAccentContainer,
            style = TextStyle(fontFamily = MonoFamily, fontSize = 10.sp, letterSpacing = 1.sp),
            modifier = Modifier.weight(1f),
        )
        Chip(stringResource(R.string.keyboard_resize_reset), null, false, actions.onResetSize)
        Spacer(Modifier.width(6.dp))
        Chip(stringResource(R.string.keyboard_tap_done), Icons.Rounded.Check, true, actions.onEndResize)
    }
}

/** A faint outline over the keys while resizing, so the new size is easy to judge. */
@Composable
private fun ResizeOverlay() {
    val colors = LocalKeyboardColors.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(2.dp)
            .border(1.5.dp, colors.accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
    )
}

@Composable
private fun Suggestions(
    words: List<String>,
    autoCorrect: String?,
    emoji: List<String>,
    actions: KeyboardActions,
    modifier: Modifier,
) {
    val colors = LocalKeyboardColors.current
    Row(modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        words.forEachIndexed { i, word ->
            if (i > 0) Box(Modifier.width(1.dp).height(18.dp).background(colors.divider))
            val strong = word == autoCorrect || (autoCorrect == null && i == 1)
            val label = stringResource(R.string.keyboard_suggestion, word)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = label) { actions.onSuggestion(word) },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = word,
                    transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) },
                    label = "suggestion",
                ) { shown ->
                    Text(
                        shown,
                        color = if (shown == autoCorrect) colors.accent else if (strong) colors.stripText else colors.stripMuted,
                        style = TextStyle(
                            fontFamily = SansFamily,
                            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 16.sp,
                            letterSpacing = (-0.1).sp,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        emoji.take(2).forEach { e ->
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { actions.onEmoji(e) },
                contentAlignment = Alignment.Center,
            ) { Text(e, fontSize = 20.sp) }
        }
    }
}

/** Live waveform, label and timer, so it is obvious the mic is open. */
@Composable
private fun VoiceStatus(dictation: DictationState, level: () -> Float) {
    val colors = LocalKeyboardColors.current
    var started by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(started) }
    LaunchedEffect(Unit) {
        started = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val label = stringResource(
        when {
            dictation is DictationState.Finishing -> R.string.bubble_transcribing
            dictation is DictationState.Listening && dictation.modelLoading -> R.string.bubble_loading_keep_talking
            else -> R.string.keyboard_listening_label
        },
    )
    Row(
        Modifier
            .fillMaxHeight()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.accentContainer)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LevelBars(level, dictation is DictationState.Listening)
        Spacer(Modifier.width(10.dp))
        Text(
            label.uppercase(),
            color = colors.onAccentContainer,
            style = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.2.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(10.dp))
        val seconds = ((now - started) / 1000).coerceAtLeast(0)
        Text(
            "%d:%02d".format(seconds / 60, seconds % 60),
            color = colors.onAccentContainer.copy(alpha = 0.7f),
            style = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp),
        )
    }
}

/** Five bars that dance with the microphone level. */
@Composable
private fun LevelBars(level: () -> Float, live: Boolean) {
    val colors = LocalKeyboardColors.current
    val t by rememberInfiniteTransition(label = "bars").animateFloat(
        0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "barsPhase",
    )
    Canvas(Modifier.size(width = 26.dp, height = 16.dp)) {
        val bars = 5
        val gap = size.width / (bars * 2 - 1)
        val l = if (live) level().coerceIn(0f, 1f) else 0f
        for (i in 0 until bars) {
            val wobble = (sin(t + i * 1.1f) + 1f) / 2f
            val h = size.height * (0.22f + 0.78f * (0.25f * wobble + 0.75f * l * (0.6f + 0.4f * wobble)))
            drawRoundRect(
                colors.accent,
                topLeft = Offset(i * gap * 2, (size.height - h) / 2),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2),
            )
        }
    }
}

@Composable
private fun ToolsButton(open: Boolean, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    val label = stringResource(R.string.keyboard_tools)
    val rotation by animateFloatAsState(if (open) 90f else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "toolsRot")
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (open) colors.accentContainer else Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (open) Icons.Rounded.Close else Icons.Outlined.Tune,
            null,
            tint = if (open) colors.onAccentContainer else colors.stripMuted,
            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
        )
    }
}

/** Tap to dictate; hold to talk and release to finish. A halo follows your voice while listening. */
@Composable
private fun MicButton(dictation: DictationState, level: () -> Float, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    val active = dictation !is DictationState.Idle
    val tap by rememberUpdatedState(actions.onMicTap)
    val hold by rememberUpdatedState(actions.onMicHold)
    val release by rememberUpdatedState(actions.onMicRelease)
    val description = stringResource(if (active) R.string.keyboard_tap_done else R.string.keyboard_mic_description)
    val fill by animateColorAsState(if (active) colors.accent else colors.key, tween(260), label = "micFill")
    val breathe by rememberInfiniteTransition(label = "micHalo").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "micBreathe",
    )
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                val l = level().coerceIn(0f, 1f)
                val base = size.minDimension / 2 * 0.83f
                drawCircle(colors.accent.copy(alpha = 0.16f + 0.1f * breathe), radius = base * (1.08f + 0.12f * breathe + 0.35f * l))
            }
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(fill)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick { tap(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        val up = withTimeoutOrNull(HOLD_TO_TALK_MS) { waitForUpOrCancellation() }
                        if (up != null) {
                            tap()
                        } else {
                            hold()
                            waitForUpOrCancellation()
                            release()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = active,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                label = "micIcon",
            ) { on ->
                Icon(
                    if (on) Icons.Rounded.Stop else Icons.Rounded.Mic,
                    null,
                    tint = if (on) colors.onAccent else colors.onAccentContainer,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceSheet(dictation: DictationState, level: () -> Float, actions: KeyboardActions) {
    val colors = LocalKeyboardColors.current
    val text = when (dictation) {
        is DictationState.Listening -> dictation.partial
        is DictationState.Finishing -> dictation.partial
        DictationState.Idle -> ""
    }
    val finishing = dictation is DictationState.Finishing
    Box(Modifier.fillMaxSize()) {
        // A soft glow that breathes with your voice behind the transcript.
        Canvas(Modifier.fillMaxSize()) {
            val l = level().coerceIn(0f, 1f)
            val center = Offset(size.width / 2, size.height * 0.68f)
            drawCircle(
                Brush.radialGradient(
                    listOf(colors.orbPrimary.copy(alpha = 0.18f + 0.22f * l), Color.Transparent),
                    center = center,
                    radius = size.width * (0.42f + 0.14f * l),
                ),
                radius = size.width,
                center = center,
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = if (text.length > TRANSCRIPT_TAIL) "…" + text.takeLast(TRANSCRIPT_TAIL).substringAfter(' ') else text
                AnimatedContent(
                    targetState = shown.ifBlank { stringResource(if (finishing) R.string.bubble_transcribing else R.string.bubble_listening) },
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "transcript",
                ) { line ->
                    Text(
                        line,
                        color = if (shown.isBlank()) colors.stripMuted else colors.stripText,
                        style = TextStyle(fontFamily = SerifFamily, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(stringResource(R.string.keyboard_cancel), Icons.Rounded.Close, false, actions.onCancelVoice)
                Spacer(Modifier.width(22.dp))
                val spec = if (finishing) {
                    OrbSpec(colors.orbPrimary, colors.orbSecondary, 0.25f)
                } else {
                    OrbSpec(colors.orbPrimary, colors.orbSecondary, 0.55f)
                }
                Box(
                    Modifier.size(ORB_SIZE).clip(CircleShape).clickable(role = Role.Button, onClick = actions.onMicTap),
                    contentAlignment = Alignment.Center,
                ) {
                    AuroraOrb(spec, level, Modifier.size(ORB_SIZE), center = { OrbGlyph(Icons.Rounded.GraphicEq) })
                }
                Spacer(Modifier.width(22.dp))
                Chip(stringResource(R.string.keyboard_tap_done), Icons.Rounded.Check, true, actions.onMicTap)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.keyboard_listening_hint).uppercase(),
                color = colors.stripMuted,
                style = TextStyle(fontFamily = MonoFamily, fontSize = 9.sp, letterSpacing = 1.2.sp),
            )
        }
    }
}

@Composable
internal fun Chip(text: String, icon: ImageVector?, accent: Boolean, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(34.dp)
            .clip(shape)
            .then(if (accent) Modifier.background(colors.accent) else Modifier.border(1.dp, colors.divider, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val tint = if (accent) colors.onAccent else colors.stripText
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, color = tint, style = TextStyle(fontFamily = SansFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp))
    }
}

@Composable
private fun ClipChip(text: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalKeyboardColors.current
    val shape = RoundedCornerShape(50)
    val label = stringResource(R.string.keyboard_paste)
    Row(
        modifier
            .height(34.dp)
            .clip(shape)
            .background(colors.accentContainer)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.ContentPaste, null, tint = colors.onAccentContainer, modifier = Modifier.size(16.dp))
        Text(
            text.replace('\n', ' '),
            color = colors.stripText,
            style = TextStyle(fontFamily = SansFamily, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun IconTap(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = colors.stripMuted, modifier = Modifier.size(18.dp))
    }
}

/** ABC, space and delete under the emoji and clipboard panels. */
@Composable
internal fun PanelBottomRow(actions: KeyboardActions) {
    val bottom = remember {
        listOf(
            KeyRow(
                listOf(
                    Key(KeyKind.PAGE, label = "ABC", page = Page.LETTERS, width = 1.5f),
                    Key(KeyKind.SPACE, width = 7f),
                    Key(KeyKind.BACKSPACE, width = 1.5f),
                ),
            ),
        )
    }
    KeyArea(bottom, ShiftState.OFF, EnterAction.NEWLINE, 46.dp, actions.keys)
}

internal val BASE_ROW_HEIGHT: Dp = 54.dp
private val STRIP_HEIGHT: Dp = 50.dp
private val ORB_SIZE: Dp = 76.dp
private const val HOLD_TO_TALK_MS = 350L
private const val TRANSCRIPT_TAIL = 140
