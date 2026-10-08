package com.wisperlow.mobile.keyboard

import org.json.JSONArray
import org.json.JSONObject

data class ClipEntry(val text: String, val time: Long, val pinned: Boolean = false)

/**
 * Recently copied text, newest first. Unpinned items expire after a day and only the
 * newest few are kept; pinned items stay until the user removes them.
 */
class ClipboardHistory(entries: List<ClipEntry> = emptyList()) {
    var entries: List<ClipEntry> = entries
        private set

    fun add(text: String, now: Long) {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > MAX_LENGTH) return
        val existing = entries.firstOrNull { it.text == clean }
        val rest = entries.filter { it.text != clean }
        entries = listOf(ClipEntry(clean, now, existing?.pinned == true)) + rest
        prune(now)
    }

    fun togglePin(text: String) {
        entries = entries.map { if (it.text == text) it.copy(pinned = !it.pinned) else it }
    }

    fun remove(text: String) {
        entries = entries.filter { it.text != text }
    }

    /** Clears everything except pinned items. */
    fun clear() {
        entries = entries.filter { it.pinned }
    }

    fun prune(now: Long) {
        val pinned = entries.filter { it.pinned }
        val recent = entries.filter { !it.pinned && now - it.time < MAX_AGE_MS }.take(MAX_UNPINNED)
        entries = entries.filter { it in pinned || it in recent }
    }

    fun toJson(): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("t", it.text).put("at", it.time).put("p", it.pinned)) }
    }.toString()

    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60_000L
        const val MAX_UNPINNED = 20
        const val MAX_LENGTH = 5_000

        fun fromJson(json: String?): ClipboardHistory {
            if (json.isNullOrBlank()) return ClipboardHistory()
            return runCatching {
                val array = JSONArray(json)
                ClipboardHistory(
                    (0 until array.length()).map { i ->
                        val o = array.getJSONObject(i)
                        ClipEntry(o.getString("t"), o.optLong("at"), o.optBoolean("p"))
                    },
                )
            }.getOrDefault(ClipboardHistory())
        }
    }
}
