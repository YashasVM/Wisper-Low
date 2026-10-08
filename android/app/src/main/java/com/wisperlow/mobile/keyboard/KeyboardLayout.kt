package com.wisperlow.mobile.keyboard

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, PAGE, EMOJI }

enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBERS, PHONE, EMOJI, CLIPBOARD }

/** What kind of text the focused field takes; picks layouts and turns typing aids on or off. */
enum class FieldKind { TEXT, EMAIL, URI, PASSWORD, NUMBER, PHONE }

data class Key(
    val kind: KeyKind,
    /** Text typed by a CHAR key. */
    val text: String = "",
    val label: String = text,
    /** Small corner hint, usually the first long-press alternate. */
    val hint: String? = null,
    val alternates: List<String> = emptyList(),
    /** Share of the row width, in standard key widths. */
    val width: Float = 1f,
    val page: Page? = null,
    /** Typed by a plain long-press, before any alternates appear: the digit on the top row. */
    val longPress: String? = null,
)

/** A row of keys. [inset] is empty space on each side, in key widths, like the stagger of a real keyboard. */
data class KeyRow(val keys: List<Key>, val inset: Float = 0f) {
    val units: Float get() = keys.sumOf { it.width.toDouble() }.toFloat() + inset * 2
}

object KeyboardLayout {
    private val accents = mapOf(
        "a" to "à á â ä æ ã å ā", "c" to "ç ć č", "e" to "è é ê ë ē ė ę", "i" to "ì í î ï ī į",
        "l" to "ł", "n" to "ñ ń", "o" to "ò ó ô ö õ ø ō œ", "s" to "ß ś š", "u" to "ù ú û ü ū",
        "y" to "ÿ ý", "z" to "ž ź ż", "d" to "ð", "t" to "þ", "g" to "ğ", "r" to "ř",
    ).mapValues { it.value.split(' ') }

    private fun letter(c: Char, digit: Char? = null): Key {
        val s = c.toString()
        val d = digit?.toString()
        return Key(KeyKind.CHAR, s, hint = d, alternates = listOfNotNull(d) + accents[s].orEmpty(), longPress = d)
    }

    private fun chars(s: String, alternates: Map<String, String> = emptyMap()) = s.split(' ').map { c ->
        Key(KeyKind.CHAR, c, alternates = alternates[c]?.split(' ').orEmpty(), hint = alternates[c]?.split(' ')?.firstOrNull())
    }

    private val shift = Key(KeyKind.SHIFT, width = 1.5f)
    private val backspace = Key(KeyKind.BACKSPACE, width = 1.5f)
    private val enter = Key(KeyKind.ENTER, width = 1.5f)
    private val emoji = Key(KeyKind.EMOJI)
    private val period = Key(
        KeyKind.CHAR, ".", hint = "!?",
        alternates = listOf("!", "?", ",", "'", "\"", ":", ";", "-", "(", ")", "/", "@", "&", "#", "…"),
    )

    private fun page(label: String, page: Page, width: Float = 1.5f) = Key(KeyKind.PAGE, label = label, page = page, width = width)

    /** The bottom row; the key left of space adapts to the field ("@" for email, "/" for links). */
    private fun bottom(kind: FieldKind, pageKey: Key): KeyRow {
        val side = when (kind) {
            FieldKind.EMAIL -> Key(KeyKind.CHAR, "@", alternates = listOf(".com", ".net", ".org", "_", "-"), hint = ".com")
            FieldKind.URI -> Key(KeyKind.CHAR, "/", alternates = listOf(".com", ".org", ".net", "https://", "www."), hint = ".com")
            else -> Key(KeyKind.CHAR, ",", alternates = listOf(";", ":", "'", "\""), hint = null)
        }
        val keys = buildList {
            add(pageKey)
            add(side)
            if (kind != FieldKind.PASSWORD) add(emoji)
            add(Key(KeyKind.SPACE, width = if (kind == FieldKind.PASSWORD) 5f else 4f))
            add(period)
            add(enter)
        }
        return KeyRow(keys)
    }

    fun rows(page: Page, kind: FieldKind, numberRow: Boolean): List<KeyRow> = when (page) {
        Page.LETTERS -> buildList {
            if (numberRow) add(KeyRow(chars("1 2 3 4 5 6 7 8 9 0")))
            val digits = "1234567890"
            add(KeyRow("qwertyuiop".mapIndexed { i, c -> letter(c, if (numberRow) null else digits[i]) }))
            add(KeyRow("asdfghjkl".map { letter(it) }, inset = 0.5f))
            add(KeyRow(listOf(shift) + "zxcvbnm".map { letter(it) } + backspace))
            add(bottom(kind, page("?123", Page.SYMBOLS)))
        }
        Page.SYMBOLS -> listOf(
            KeyRow(chars("1 2 3 4 5 6 7 8 9 0", mapOf("1" to "¹ ½ ⅓ ¼", "2" to "² ⅔", "3" to "³ ¾", "0" to "ⁿ ∅"))),
            KeyRow(chars("@ # $ _ & - + ( ) /", mapOf("$" to "€ £ ¥ ₹ ¢", "-" to "— – ·", "+" to "±", "(" to "< { [", ")" to "> } ]"))),
            KeyRow(listOf(page("=\\<", Page.MORE_SYMBOLS)) + chars("* \" ' : ; ! ?", mapOf("\"" to "“ ” « »", "'" to "‘ ’ ‚", "!" to "¡", "?" to "¿")) + backspace),
            bottom(kind, page("ABC", Page.LETTERS)),
        )
        Page.MORE_SYMBOLS -> listOf(
            KeyRow(chars("~ ` | • √ π ÷ × ¶ ∆")),
            KeyRow(chars("£ ¢ € ¥ ^ ° = { } \\")),
            KeyRow(listOf(page("?123", Page.SYMBOLS)) + chars("% © ® ™ ✓ [ ]") + backspace),
            bottom(kind, page("ABC", Page.LETTERS)),
        )
        Page.NUMBERS -> listOf(
            KeyRow(chars("1 2 3 -")),
            KeyRow(chars("4 5 6") + Key(KeyKind.SPACE)),
            KeyRow(chars("7 8 9") + Key(KeyKind.BACKSPACE)),
            KeyRow(chars(", 0 .") + Key(KeyKind.ENTER)),
        )
        Page.PHONE -> listOf(
            KeyRow(chars("1 2 3 -")),
            KeyRow(chars("4 5 6") + Key(KeyKind.SPACE)),
            KeyRow(chars("7 8 9") + Key(KeyKind.BACKSPACE)),
            KeyRow(chars("* 0 #", mapOf("0" to "+", "*" to "( ) /", "#" to ", ;")) + Key(KeyKind.ENTER)),
        )
        Page.EMOJI, Page.CLIPBOARD -> emptyList()
    }
}
