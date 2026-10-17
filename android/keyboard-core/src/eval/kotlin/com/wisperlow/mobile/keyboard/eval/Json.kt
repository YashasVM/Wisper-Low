package com.wisperlow.mobile.keyboard.eval

/**
 * Minimal hand-rolled JSON, so the eval adds no dependencies: [encode] understands
 * maps, lists, strings, numbers, booleans and null; [parse] reads the same subset and
 * is enough to diff against a committed baseline.
 */
object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(value.toString())
            is Int, is Long -> out.append(value.toString())
            is Number -> out.append(trimNumber(value.toDouble()))
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(k.toString(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    /** Four decimals is plenty for the eval's rates; whole numbers stay whole. */
    private fun trimNumber(d: Double): String {
        val rounded = Math.round(d * 10_000.0) / 10_000.0
        return if (rounded == Math.floor(rounded) && !rounded.isInfinite() && kotlin.math.abs(rounded) < 1e15) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    /** Parses objects, arrays, strings, numbers, booleans and null; no other syntax. */
    fun parse(text: String): Any? = Parser(text).run {
        val v = value()
        v
    }

    private class Parser(private val s: String) {
        private var at = 0

        fun value(): Any? {
            skip()
            return when (peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            expect("{")
            val map = LinkedHashMap<String, Any?>()
            skip()
            if (peek() == '}') { at++; return map }
            while (true) {
                skip()
                val key = string()
                skip()
                expect(":")
                map[key] = value()
                skip()
                when (peek()) {
                    ',' -> at++
                    '}' -> { at++; return map }
                    else -> throw IllegalArgumentException("bad JSON at $at")
                }
            }
        }

        private fun array(): List<Any?> {
            expect("[")
            val list = ArrayList<Any?>()
            skip()
            if (peek() == ']') { at++; return list }
            while (true) {
                list += value()
                skip()
                when (peek()) {
                    ',' -> at++
                    ']' -> { at++; return list }
                    else -> throw IllegalArgumentException("bad JSON at $at")
                }
            }
        }

        private fun string(): String {
            expect("\"")
            val out = StringBuilder()
            while (true) {
                val c = s[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> when (val e = s[at++]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'u' -> {
                            out.append(s.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> throw IllegalArgumentException("bad escape \\$e")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = at
            while (at < s.length && s[at] in "-+.eE0123456789") at++
            return s.substring(start, at).toDouble()
        }

        private fun peek(): Char {
            skip()
            return s.getOrNull(at) ?: throw IllegalArgumentException("truncated JSON")
        }

        private fun skip() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        private fun expect(token: String) {
            require(s.regionMatches(at, token, 0, token.length)) { "bad JSON at $at, expected $token" }
            at += token.length
        }
    }
}
