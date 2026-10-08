package com.wisperlow.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InsertResult {
    /** Text went straight into the field. */
    INSERTED,

    /** Text was pasted via the clipboard, which is restored when Android allows reading it. */
    PASTED,

    /** No usable text field; nothing was inserted. */
    NO_TARGET,
}

/**
 * Finds the text field the user is typing in and puts dictated text into it.
 * It also reports whether a keyboard is open so the bubble can appear only
 * while the user is typing. It reads no screen content beyond the focused field.
 */
class WisperlowAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: WisperlowAccessibilityService? = null

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _keyboardVisible = MutableStateFlow(false)
        /** True while a keyboard is open for a text field in another app. */
        val keyboardVisible: StateFlow<Boolean> = _keyboardVisible.asStateFlow()

        private val _keyboardTop = MutableStateFlow<Int?>(null)
        /** Screen y of the open keyboard's top edge, so the bubble can dock just above it. */
        val keyboardTop: StateFlow<Int?> = _keyboardTop.asStateFlow()

        /** Whether the user switched the service on in system settings, even if not yet bound. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = ComponentName(context, WisperlowAccessibilityService::class.java)
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == component
            }
        }

        /**
         * Remembers the focused field so later insertion cannot land in our own review box.
         * Safe off the main thread; the lookup is several IPC round trips into the other app.
         */
        fun captureTarget(): Boolean = instance?.captureTargetImpl() ?: false

        fun insert(text: String): InsertResult = instance?.insertImpl(text) ?: InsertResult.NO_TARGET
    }

    private val handler = Handler(Looper.getMainLooper())
    // Written from a background thread by captureTarget(), read on the main thread by insert().
    @Volatile private var capturedTarget: AccessibilityNodeInfo? = null
    private val recomputeKeyboard = Runnable { updateKeyboardVisibility() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        updateKeyboardVisibility()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _connected.value = false
            _keyboardVisible.value = false
            _keyboardTop.value = null
        }
        handler.removeCallbacks(recomputeKeyboard)
        capturedTarget = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window changes arrive in bursts; coalesce them into one cheap check.
        handler.removeCallbacks(recomputeKeyboard)
        handler.postDelayed(recomputeKeyboard, Timing.KEYBOARD_DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    private fun updateKeyboardVisibility() {
        val current = try {
            windows
        } catch (_: Exception) {
            emptyList()
        }
        val ime = current.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val focusedPackage = current
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?.let { window -> runCatching { window.root?.packageName?.toString() }.getOrNull() }
        val visible = ime != null && focusedPackage != packageName
        _keyboardTop.value = if (visible) Rect().also { ime!!.getBoundsInScreen(it) }.top.takeIf { it > 0 } else null
        _keyboardVisible.value = visible
    }

    private fun captureTargetImpl(): Boolean {
        capturedTarget = findFocusedEditable()
        return capturedTarget != null
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val current = try {
            windows
        } catch (_: Exception) {
            return null
        }
        // Our bubble is an overlay and can be the active window. Prefer another
        // app's focused editor so insertion never targets our own review field.
        return current.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { (if (it.isFocused) 2 else 0) + (if (it.isActive) 1 else 0) }
            .mapNotNull { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
                if (root.packageName?.toString() == packageName) return@mapNotNull null
                val focus = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                focus?.takeIf { it.isEditable && it.isVisibleToUser && !it.isPassword }
            }
            .firstOrNull()
    }

    private fun insertImpl(text: String): InsertResult {
        val target = capturedTarget?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }
            ?: findFocusedEditable()
        capturedTarget = null
        if (target == null || !target.isEditable || target.isPassword ||
            target.packageName?.toString() == packageName
        ) {
            return InsertResult.NO_TARGET
        }
        return try {
            val current = if (target.isShowingHintText) "" else target.text?.toString().orEmpty()
            val start = target.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
            val end = target.textSelectionEnd.takeIf { it in 0..current.length } ?: start
            val lower = minOf(start, end)
            val upper = maxOf(start, end)
            val insertion = SmartSpacing.fit(current.substring(0, lower), current.substring(upper), text)
            if (insertion.isEmpty()) return InsertResult.NO_TARGET
            val terminal = InsertionVerifier.isTerminal(target.packageName?.toString(), target.className?.toString())
            if (!terminal && setTextAt(target, current, lower, upper, insertion)) {
                InsertResult.INSERTED
            } else if (!appliedLate(target, current, insertion) && paste(target, insertion)) {
                InsertResult.PASTED
            } else {
                InsertResult.NO_TARGET
            }
        } catch (_: Exception) {
            InsertResult.NO_TARGET
        }
    }

    /** Writes the field directly, leaving the user's clipboard untouched. */
    private fun setTextAt(
        target: AccessibilityNodeInfo,
        current: String,
        start: Int,
        end: Int,
        insertion: String,
    ): Boolean {
        val replacement = current.replaceRange(start, end, insertion)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        // Some apps accept the action but ignore it; confirm before trusting it.
        target.refresh()
        val updated = target.text?.toString().orEmpty()
        if (InsertionVerifier.check(current, updated, insertion) != InsertionVerifier.Outcome.APPLIED) return false
        val cursor = (start + insertion.length).coerceAtMost(updated.length)
        target.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return true
    }

    /** Some apps apply SET_TEXT after we re-read; pasting then would insert twice. */
    private fun appliedLate(target: AccessibilityNodeInfo, original: String, insertion: String): Boolean {
        if (!runCatching { target.refresh() }.getOrDefault(false)) return false
        val now = if (target.isShowingHintText) "" else target.text?.toString().orEmpty()
        return InsertionVerifier.check(original, now, insertion) == InsertionVerifier.Outcome.APPLIED
    }

    /** Fallback for fields (often web views) that ignore direct text replacement. */
    private fun paste(target: AccessibilityNodeInfo, insertion: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // Android only lets focused apps read the clipboard, so this is often
        // null; when it is readable, put the user's own clip back afterwards.
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        val clip = ClipData.newPlainText("Wisperlow", insertion)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keeps the system clipboard preview from flashing dictated text.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        val pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (previous != null) {
            // The target app reads the clip asynchronously; restore only after it has.
            handler.postDelayed(
                { runCatching { clipboard.setPrimaryClip(previous) } },
                Timing.CLIPBOARD_RESTORE_MS,
            )
        }
        return pasted
    }

    private object Timing {
        const val KEYBOARD_DEBOUNCE_MS = 120L
        const val CLIPBOARD_RESTORE_MS = 600L
    }
}
