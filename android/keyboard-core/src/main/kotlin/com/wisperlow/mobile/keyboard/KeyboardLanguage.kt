package com.wisperlow.mobile.keyboard

/**
 * The language the keyboard types in. The two Indic modes are *romanized*: the board stays
 * QWERTY and the user types Latin, while suggestions and committed words come out in the
 * native script. No InScript or other native-script layout is implemented.
 *
 * [code] is what gets persisted, so stored values stay stable even if the enum is reordered.
 */
enum class KeyboardLanguage(val code: String) {
    ENGLISH("en"),
    HINDI("hi"),
    KANNADA("kn"),
    ;

    /** True for a romanized mode whose suggestions are native script reached through Latin typing. */
    val isIndic: Boolean get() = this != ENGLISH

    companion object {
        /** The language for a stored [code]; English for anything missing or unknown. */
        fun fromCode(code: String?): KeyboardLanguage =
            code?.trim()?.lowercase()?.let { stored -> entries.firstOrNull { it.code == stored } } ?: ENGLISH
    }
}
