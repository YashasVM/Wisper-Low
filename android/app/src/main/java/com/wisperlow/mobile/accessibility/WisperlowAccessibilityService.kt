package com.wisperlow.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import java.util.ArrayDeque

class WisperlowAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var ref: WeakReference<WisperlowAccessibilityService>? = null

        val isReady: Boolean
            get() = ref?.get() != null

        fun findEditableTarget(): AccessibilityNodeInfo? =
            ref?.get()?.findEditableTargetImpl()

        /** Captures the focused app field before the overlay becomes active. */
        fun captureEditableTarget(): Boolean =
            ref?.get()?.captureEditableTargetImpl() ?: false

        fun pasteText(text: String): Boolean =
            ref?.get()?.pasteTextImpl(text) ?: false

        fun pasteNewline(): Boolean =
            ref?.get()?.pasteTextImpl("\n") ?: false

        fun pressEnter(): Boolean =
            ref?.get()?.pressEnterImpl() ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref = WeakReference(this)
    }

    override fun onDestroy() {
        if (ref?.get() === this) {
            ref = null
        }
        capturedTarget?.recycle()
        capturedTarget = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private var capturedTarget: AccessibilityNodeInfo? = null

    private fun captureEditableTargetImpl(): Boolean {
        val target = findEditableTargetImpl() ?: return false
        capturedTarget?.recycle()
        capturedTarget = AccessibilityNodeInfo.obtain(target)
        target.recycle()
        return true
    }

    private fun findEditableTargetImpl(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            val focused = findFocusTarget(root)
            if (focused != null) {
                focused
            } else {
                findBfsEditableTarget(root)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findFocusTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        }
        while (current != null) {
            val candidate = current
            if (candidate.isEditable && candidate.isFocusable) return candidate
            current = try { candidate.parent } catch (_: Exception) { null }
        }
        return null
    }

    private fun findBfsEditableTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = try {
                queue.removeFirst()
            } catch (_: Exception) {
                break
            }
            if (node.isEditable && isEditTextLike(node)) {
                return node
            }
            if (fallback == null && node.isEditable && node.isFocused) {
                fallback = node
            }
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { queue.add(it) }
                } catch (_: Exception) {
                }
            }
        }
        return fallback
    }

    private fun isEditTextLike(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: return false
        return className == "android.widget.EditText" ||
            className == "android.widget.AutoCompleteTextView" ||
            className.contains("EditText", ignoreCase = true)
    }

    private fun pasteTextImpl(text: String): Boolean {
        return try {
            val target = capturedTarget ?: findEditableTargetImpl() ?: return false

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("wisperlow", text))

            val pasted = if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                true
            } else {
                setTextAtSelection(target, text)
            }
            if (!pasted && target === capturedTarget) {
                // The app may have recreated its editor while inference ran.
                // Retry once against the current focused field.
                capturedTarget = null
                return pasteTextImpl(text)
            }
            pasted
        } catch (_: Exception) {
            false
        }
    }

    private fun pressEnterImpl(): Boolean {
        val target = findEditableTargetImpl() ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (_: Exception) {
            false
        }
    }

    private fun setTextAtSelection(target: AccessibilityNodeInfo, inserted: String): Boolean {
        val current = target.text?.toString().orEmpty()
        val selectionStart = target.textSelectionStart.takeIf { it in 0..current.length }
            ?: current.length
        val selectionEnd = target.textSelectionEnd.takeIf { it in selectionStart..current.length }
            ?: selectionStart
        val replacement = current.replaceRange(selectionStart, selectionEnd, inserted)
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                replacement,
            )
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }
}
