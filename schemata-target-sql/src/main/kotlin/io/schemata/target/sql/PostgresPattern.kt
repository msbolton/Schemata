package io.schemata.target.sql

/**
 * Walks a Java pattern once and names, exactly as it is written, the first construct it knows
 * Postgres's ARE (advanced regular expression) dialect lacks or reads differently; null when it
 * finds none. It reports Unicode properties, possessive quantifiers, named groups, class
 * intersections and nested classes, embedded options anywhere but a leading `(?imnsx)` group,
 * repetition counts above 255, and the escapes `\b\B\Q\E\h\H\R\X\G\z\Z\v\V\k`. Everything else
 * passes unexamined, among it lookahead, lookbehind, lazy quantifiers, back-references, `\d\s\w`,
 * `\a\e`, `\A`, `\x..`, `\u....`, character classes, and POSIX bracket expressions (`[[:alpha:]]`).
 */
object PostgresPattern {
    fun firstUnsupported(pattern: String): String? = Scanner(pattern).firstUnsupported()

    private class Scanner(private val s: String) {
        private var i = 0

        fun firstUnsupported(): String? {
            while (i < s.length) {
                val bad =
                    when (s[i]) {
                        '\\' -> escape()
                        '[' -> charClass()
                        '(' -> group()
                        '*',
                        '+',
                        '?' -> quantifier(i + 1)
                        '{' -> braces()
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
         * A quantifier ending just before [next]; a following `+` (possessive) is reported with it.
         */
        private fun quantifier(next: Int): String? {
            val text = s.substring(i, next)
            i = next
            if (s.getOrNull(next) == '+') return text + "+"
            if (s.getOrNull(next) == '?') i++
            return null
        }

        /**
         * `{n,m}` passes when neither count exceeds ARE's limit of 255, else it is reported whole;
         * a possessive `{n,m}+` is reported as just `}+`, as it is written there.
         */
        private fun braces(): String? {
            val close = s.indexOf('}', i)
            val bounds = if (close < 0) null else BOUNDS.matchEntire(s.substring(i + 1, close))
            if (bounds == null) {
                i++
                return null
            }
            val counts = bounds.groupValues.drop(1).filter { it.isNotEmpty() }
            if (counts.any { it.toBigInteger() > MAX_COUNT }) return reported(close + 1)
            i = close + 1
            if (s.getOrNull(i) == '+') {
                i++
                return "}+"
            }
            if (s.getOrNull(i) == '?') i++
            return null
        }

        /**
         * `(?:`, `(?=`, `(?!`, `(?<=`, `(?<!` are ARE groups; `(?<letter` and `(?P<` are named
         * groups ARE lacks; anything else after `(?` is an embedded option group.
         */
        private fun group(): String? {
            if (s.getOrNull(i + 1) != '?') {
                i++
                return null
            }
            val afterQ = i + 2
            return when {
                s.getOrNull(afterQ) == ':' -> {
                    i = afterQ + 1
                    null
                }
                s.getOrNull(afterQ) == '=' -> {
                    i = afterQ + 1
                    null
                }
                s.getOrNull(afterQ) == '!' -> {
                    i = afterQ + 1
                    null
                }
                s.getOrNull(afterQ) == '<' && s.getOrNull(afterQ + 1) == '=' -> {
                    i = afterQ + 2
                    null
                }
                s.getOrNull(afterQ) == '<' && s.getOrNull(afterQ + 1) == '!' -> {
                    i = afterQ + 2
                    null
                }
                s.getOrNull(afterQ) == '<' -> namedGroup(afterQ + 1)
                s.getOrNull(afterQ) == 'P' && s.getOrNull(afterQ + 1) == '<' ->
                    namedGroup(afterQ + 2)
                else -> embeddedOptions(afterQ)
            }
        }

        /**
         * `(?<name>` or `(?P<name>`, reported through the closing `>`, or to the end without one.
         */
        private fun namedGroup(nameStart: Int): String {
            val close = s.indexOf('>', nameStart)
            return reported(if (close < 0) s.length else close + 1)
        }

        /**
         * `(?letters)` is the director form ARE accepts only as the whole pattern's prefix, and
         * there only with letters from `imnsx`; `(?letters:` (a Perl-style scoped flag group) is
         * never accepted. Neither is a group ARE knows some other way (already handled by [group]
         * before this is reached), so no letters at all means this is just a plain `(` and `?`, not
         * a report.
         */
        private fun embeddedOptions(lettersStart: Int): String? {
            var j = lettersStart
            while (j < s.length && (s[j] in 'a'..'z' || s[j] in 'A'..'Z')) j++
            if (j == lettersStart) {
                i++
                return null
            }
            return when (s.getOrNull(j)) {
                ')' ->
                    if (i == 0 && s.substring(lettersStart, j).all { it in LEADING_OPTIONS }) {
                        i = j + 1
                        null
                    } else reported(j + 1)
                ':' -> reported(j + 1)
                else -> {
                    i++
                    null
                }
            }
        }

        /**
         * A class up to `]`; `&&`, a bare nested `[`, and `[:...:]`/`[.*.]`/`[=*=]` are checked.
         */
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
                        escape()?.let {
                            return it
                        }
                    s.startsWith("&&", i) -> return "&&"
                    s[i] == '[' -> if (!posixClass()) return "["
                    else -> i++
                }
            }
            return null
        }

        /**
         * `[:name:]`, `[.symbol.]`, or `[=equiv=]`; consumes it and returns true when it is one.
         */
        private fun posixClass(): Boolean {
            val marker = s.getOrNull(i + 1)
            if (marker != ':' && marker != '.' && marker != '=') return false
            val close = s.indexOf("$marker]", i + 2)
            if (close < 0) return false
            i = close + 2
            return true
        }

        /**
         * `\p`/`\P` and `\b\B\Q\E\h\H\R\X\G\z\k` have no ARE meaning, `\V` does not exist there,
         * and `\Z` (end of string, not before a final line terminator) and `\v` (a vertical-tab
         * character, not a class) mean something else; all are reported as written. A named group
         * opening is handled by [group], so `\k<name>`-style backreferences never reach here.
         * Everything else — digits, `\d\s\w`, `\m\M\y\Y`, `\a\e`, `\A`, `\x..`, `\u....`, an
         * escaped syntax character — is simply consumed.
         */
        private fun escape(): String? {
            val c = s.getOrNull(i + 1) ?: return reported(i + 1)
            if (c == 'p' || c == 'P') return property()
            if (c in UNSUPPORTED) return reported(i + 2)
            i += 2
            return null
        }

        /**
         * `\p{X}` or `\P{X}`, reported through the closing `}`, or just the two letters without
         * one.
         */
        private fun property(): String {
            if (s.getOrNull(i + 2) != '{') return reported(i + 2)
            val close = s.indexOf('}', i + 3)
            return reported(if (close < 0) s.length else close + 1)
        }

        private fun reported(end: Int): String = s.substring(i, end)

        private companion object {
            const val UNSUPPORTED = "bBQEhHRXGzZvVk"
            const val LEADING_OPTIONS = "imnsx"
            val MAX_COUNT = 255.toBigInteger()
            val BOUNDS = Regex("([0-9]+)(?:,([0-9]*))?")
        }
    }
}
