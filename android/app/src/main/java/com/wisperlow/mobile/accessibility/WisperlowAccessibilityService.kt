package com.wisperlow.mobile.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

        fun pasteText(text: String): Boolean =
            ref?.get()?.pasteTextImpl(text) ?: false

        fun pasteNewline(): Boolean =
            ref?.get()?.pasteTextImpl("\n") ?: false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref = WeakReference(this)
    }

    override fun onDestroy() {
        if (ref?.get() === this) {
            ref = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun findEditableTargetImpl(): AccessibilityNodeInfo? {
        return try {
            val root = rootInActiveWindow ?: return null
            findFocusTarget(root) ?: findBfsEditableTarget(root)
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
            if (current.isEditable && current.isFocusable) return current
            current = current.parent
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
            val target = findEditableTargetImpl() ?: return false

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("wisperlow", text))

            return target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }
}
