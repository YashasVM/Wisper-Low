package com.wisperlow.mobile.keyboard

/**
 * The language the keyboard types in. Hindi and Kannada words stay in Latin letters, on
 * the ordinary QWERTY board. These modes suggest regional spellings and mixed English text;
 * they do not convert words to a native script.
 *
 * [code] is what gets persisted, so stored values stay stable even if the enum is reordered.
 */
enum class KeyboardLanguage(val code: String) {
    ENGLISH("en"),
    HINDI("hi"),
    KANNADA("kn"),
    ;

    /** True for a regional language mode that keeps its words in Latin letters. */
    val isIndic: Boolean get() = this != ENGLISH

    companion object {
        /** The language for a stored [code]; English for anything missing or unknown. */
        fun fromCode(code: String?): KeyboardLanguage =
            code?.trim()?.lowercase()?.let { stored -> entries.firstOrNull { it.code == stored } } ?: ENGLISH
    }
}
