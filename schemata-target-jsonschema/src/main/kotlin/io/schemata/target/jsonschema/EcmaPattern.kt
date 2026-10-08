package io.schemata.target.jsonschema

/**
 * Walks a Java pattern once and names the first construct ECMA-262 regexes lack, exactly as it is
 * written; null when every construct exists in both dialects with the same meaning. The ECMA side
 * is the Unicode (`u`) dialect JSON Schema validators assume, which rejects identity escapes of
 * ordinary characters and lone `}` or `]`.
 */
object EcmaPattern {
    fun firstUnsupported(pattern: String): String? = Scanner(pattern).firstUnsupported()

    private class Scanner(private val s: String) {
        private var i = 0

        fun firstUnsupported(): String? {
            while (i < s.length) {
                val bad =
                    when (s[i]) {
                        '\\' -> escape(inClass = false)
                        '[' -> charClass()
                        '(' -> group()
                        '?',
                        '*',
                        '+' -> quantifier(i + 1)
                        '{' -> braces()
                        '}',
                        ']' -> reported(i + 1)
                        else -> {
                            i++
                            null
                        }
                    }
                if (bad != null) return bad
            }
            return null
        }

        /**
         * `(?` followed by `:`, `=`, `!`, `<=`, `<!`, or `<name>` exists in ECMA; inline flags do
         * not.
         */
        private fun group(): String? {
            if (s.getOrNull(i + 1) != '?') {
                i++
                return null
            }
            val rest = s.substring(i + 2)
            val known =
                rest.startsWith(":") ||
                    rest.startsWith("=") ||
                    rest.startsWith("!") ||
                    rest.startsWith("<=") ||
                    rest.startsWith("<!") ||
                    NAMED.containsMatchIn(rest)
            if (known) {
                i += 2
                return null
            }
            PYTHON_NAMED.find(rest)?.let {
                val name = it.groupValues[1]
                return "(?P<$name> (a Python-style named group; ECMAScript writes it (?<$name>...))"
            }
            val close = s.indexOfAny(charArrayOf(')', ':'), i)
            return s.substring(i, if (close < 0) s.length else close + 1)
        }

        /**
         * A quantifier ending just before [next]; a following `+` (possessive) is reported with it.
         */
        private fun quantifier(next: Int): String? {
            val text = s.substring(i, next)
            i = next
            if (s.getOrNull(next) == '+') return text + "+"
            if (s.getOrNull(next) == '?') i++
            return null
        }

        private fun braces(): String? {
            val close = s.indexOf('}', i)
            if (close < 0 || !BOUNDS.matches(s.substring(i + 1, close))) {
                i++
                return null
            }
            return quantifier(close + 1)
        }

        /** A class up to `]`; `&&` and a bare nested `[` mean something else in ECMA. */
        private fun charClass(): String? {
            i++
            if (s.getOrNull(i) == '^') i++
            if (s.getOrNull(i) == ']') i++
            while (i < s.length) {
                when {
                    s[i] == ']' -> {
                        i++
                        return null
                    }
                    s[i] == '\\' ->
                        escape(inClass = true)?.let {
                            return it
                        }
                    s.startsWith("&&", i) -> return "&&"
                    s[i] == '[' -> return "["
                    else -> i++
                }
            }
            return null
        }

        /**
         * An escape is kept when it escapes a syntax character, is one ECMA knows, or is `\-`
         * inside a class; `\0` must not precede a digit, `\c` needs a letter, and `\1`…`\9` are
         * backreferences outside a class only. Inside a class `\B` and `\k` are errors too: a class
         * escape is `\b` (backspace), `\-`, or a character class or character escape, and `\k` is
         * an identity escape only without the Unicode flag.
         */
        private fun escape(inClass: Boolean): String? {
            val c = s.getOrNull(i + 1) ?: return "\\".also { i++ }
            return when {
                c in JAVA_ONLY -> reported(i + 2)
                c == '0' && s.getOrNull(i + 2) in '0'..'9' -> reported(i + 3)
                c == 'c' -> {
                    val letter = s.getOrNull(i + 2)
                    if (letter != null && (letter in 'a'..'z' || letter in 'A'..'Z')) {
                        i += 3
                        null
                    } else reported(i + 2)
                }
                c in '1'..'9' && inClass -> reported(i + 2)
                c == 'B' && inClass -> reported(i + 2)
                c == 'k' && inClass -> {
                    val close = s.indexOf('>', i)
                    reported(if (s.getOrNull(i + 2) == '<' && close >= 0) close + 1 else i + 2)
                }
                c == 'p' || c == 'P' -> property()
                c == 'x' && s.getOrNull(i + 2) == '{' ->
                    reported(s.indexOf('}', i).let { if (it < 0) s.length else it + 1 })
                c == 'k' -> {
                    if (s.getOrNull(i + 2) == '<') {
                        i = s.indexOf('>', i).let { if (it < 0) s.length else it + 1 }
                        null
                    } else reported(i + 2)
                }
                c in SYNTAX || c in KNOWN || c in '1'..'9' || (inClass && c == '-') -> {
                    i += 2
                    null
                }
                else -> reported(i + 2)
            }
        }

        /**
         * `\p{X}` with a Unicode general category passes; `Is…`, `In…`, `java…`, and POSIX names do
         * not.
         */
        private fun property(): String? {
            if (s.getOrNull(i + 2) != '{') return reported(minOf(i + 3, s.length))
            val close = s.indexOf('}', i + 3)
            if (close < 0) return reported(s.length)
            val name = s.substring(i + 3, close)
            if (name in CATEGORIES) {
                i = close + 1
                return null
            }
            return reported(close + 1)
        }

        private fun reported(end: Int): String = s.substring(i, end)

        private companion object {
            const val JAVA_ONLY = "AzZGQEhHRXea"
            const val SYNTAX = "^$\\.*+?()[]{}|/"
            const val KNOWN = "dDwWsSbBnrtvf0xu"
            val BOUNDS = Regex("[0-9]+(,[0-9]*)?")
            val NAMED = Regex("^<[A-Za-z][A-Za-z0-9]*>")
            val PYTHON_NAMED = Regex("^P<([A-Za-z][A-Za-z0-9]*)>")
            val CATEGORIES =
                ("L Lu Ll Lt Lm Lo M Mn Mc Me N Nd Nl No P Pc Pd Ps Pe Pi Pf Po " +
                        "Z Zs Zl Zp S Sm Sc Sk So C Cc Cf Co Cn")
                    .split(' ')
                    .toSet()
        }
    }
}
