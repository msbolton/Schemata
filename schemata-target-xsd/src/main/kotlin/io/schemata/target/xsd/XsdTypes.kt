package io.schemata.target.xsd

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Value

/** Builtin names, facets from refinements, pattern conversion, and default text. */
object XsdTypes {
    const val UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    /**
     * A converted pattern: [value] to emit (null when dropped), [unsupported] the construct that
     * forced the drop.
     */
    data class Pattern(val value: String?, val unsupported: String?)

    fun xsName(builtin: Builtin): String =
        when (builtin) {
            Builtin.BOOL -> "xs:boolean"
            Builtin.INT32 -> "xs:int"
            Builtin.INT64 -> "xs:long"
            Builtin.FLOAT32 -> "xs:float"
            Builtin.FLOAT64 -> "xs:double"
            Builtin.DECIMAL -> "xs:decimal"
            Builtin.STRING,
            Builtin.UUID -> "xs:string"
            Builtin.BYTES -> "xs:base64Binary"
            Builtin.DATE -> "xs:date"
            Builtin.TIME -> "xs:time"
            Builtin.INSTANT -> "xs:dateTime"
            Builtin.DURATION -> "xs:duration"
        }

    /**
     * Facets for [builtin] under [refinements]; the caller reports an unsupported pattern before
     * calling this with it.
     */
    fun facets(builtin: Builtin, refinements: Refinements): List<XsdFacet> {
        val out = mutableListOf<XsdFacet>()
        when (builtin) {
            Builtin.STRING,
            Builtin.BYTES -> {
                refinements.min?.let { out += XsdFacet("minLength", it.toPlainString()) }
                refinements.max?.let { out += XsdFacet("maxLength", it.toPlainString()) }
                refinements.pattern?.let { p ->
                    pattern(p).value?.let { out += XsdFacet("pattern", it) }
                }
            }
            Builtin.UUID -> out += XsdFacet("pattern", UUID_PATTERN)
            Builtin.DECIMAL -> {
                refinements.precision?.let { out += XsdFacet("totalDigits", it.toString()) }
                refinements.scale?.let { out += XsdFacet("fractionDigits", it.toString()) }
                refinements.min?.let { out += XsdFacet("minInclusive", it.toPlainString()) }
                refinements.max?.let { out += XsdFacet("maxInclusive", it.toPlainString()) }
            }
            Builtin.INT32,
            Builtin.INT64,
            Builtin.FLOAT32,
            Builtin.FLOAT64 -> {
                refinements.min?.let { out += XsdFacet("minInclusive", it.toPlainString()) }
                refinements.max?.let { out += XsdFacet("maxInclusive", it.toPlainString()) }
            }
            Builtin.BOOL,
            Builtin.DATE,
            Builtin.TIME,
            Builtin.INSTANT,
            Builtin.DURATION -> Unit
        }
        return out
    }

    /**
     * A Schemata pattern matches anywhere in the string unless anchored; an XSD pattern always
     * matches the whole string. An unescaped leading `^` and trailing `$` are stripped, and each
     * side without one is opened with `.*`, the body parenthesised so an alternation stays whole:
     * `a|b` becomes `.*(a|b).*`, `^abc` becomes `(abc).*`, and `^abc$` stays `abc`. The body is
     * then screened for the first construct XSD regexes lack.
     */
    fun pattern(pattern: String): Pattern {
        val start = pattern.startsWith("^")
        val end = pattern.endsWith("$") && !escapedAt(pattern, pattern.length - 1)
        val body = pattern.substring(if (start) 1 else 0, pattern.length - (if (end) 1 else 0))
        PatternScanner(body).firstUnsupported()?.let {
            return Pattern(null, it)
        }
        if (start && end) return Pattern(body, null)
        return Pattern((if (start) "" else ".*") + "($body)" + (if (end) "" else ".*"), null)
    }

    /** Whether the character at [index] follows an odd run of backslashes. */
    private fun escapedAt(s: String, index: Int): Boolean {
        var slashes = 0
        while (index - slashes - 1 >= 0 && s[index - slashes - 1] == '\\') slashes++
        return slashes % 2 == 1
    }

    fun text(value: Value): String =
        when (value) {
            is IntValue -> value.value.toString()
            is RealValue -> value.value.toPlainString()
            is StringValue -> value.value
            is BoolValue -> value.value.toString()
            is EnumRef -> value.value
        }
}

/**
 * Walks a pattern once, token by token (escapes, character classes, groups, quantifiers), and names
 * the first construct outside XSD 1.0's regex language, exactly as it is written.
 */
private class PatternScanner(private val s: String) {
    private var i = 0

    fun firstUnsupported(): String? {
        while (i < s.length) {
            val bad =
                when (s[i]) {
                    '\\' -> escape()
                    '[' -> charClass()
                    '(' -> group()
                    '?',
                    '*',
                    '+' -> quantifier(i + 1)
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
     * `(` opens a plain group; any `(?` construct (lookaround, flags, non-capturing) is not XSD.
     */
    private fun group(): String? {
        if (s.getOrNull(i + 1) == '?') return "(?"
        i++
        return null
    }

    /**
     * A quantifier ending just before [next]; a following `?` (lazy) or `+` (possessive) is
     * reported together with it.
     */
    private fun quantifier(next: Int): String? {
        val text = s.substring(i, next)
        i = next
        val after = s.getOrNull(next)
        if (after == '?' || after == '+') return text + after
        return null
    }

    /** `{n}`, `{n,}`, or `{n,m}`; any other brace run is reported as written. */
    private fun braces(): String? {
        val close = s.indexOf('}', i)
        if (close < 0) return s.substring(i)
        if (!BOUNDS.matches(s.substring(i + 1, close))) return s.substring(i, close + 1)
        return quantifier(close + 1)
    }

    /**
     * A character class up to its closing `]`: escapes are screened as outside one, `-[` opens an
     * XSD subtraction, and `&&` (intersection) or a bare nested `[` (union) are reported.
     */
    private fun charClass(): String? {
        i++
        if (s.getOrNull(i) == '^') i++
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
                s.startsWith("-[", i) -> {
                    i++
                    charClass()?.let {
                        return it
                    }
                }
                s[i] == '[' -> return "["
                else -> i++
            }
        }
        return null
    }

    /** An escape: a single-character escape, a category or block escape, or a reported one. */
    private fun escape(): String? {
        val c = s.getOrNull(i + 1) ?: return "\\".also { i++ }
        return when {
            c in SINGLE_CHAR_ESCAPES -> {
                i += 2
                null
            }
            c == 'p' || c == 'P' -> property()
            c == 'x' -> reported(if (s.getOrNull(i + 2) == '{') braced() else digits(2, HEX))
            c == 'u' -> reported(digits(4, HEX))
            c == '0' -> reported(digits(3, "01234567"))
            else -> reported(i + 2)
        }
    }

    /** `\p{X}` or `\P{X}` with a Unicode general category or an `Is` block name. */
    private fun property(): String? {
        if (s.getOrNull(i + 2) != '{') return reported(minOf(i + 3, s.length))
        val close = s.indexOf('}', i + 3)
        if (close < 0) return reported(s.length)
        val name = s.substring(i + 3, close)
        if (name in CATEGORIES || isBlock(name)) {
            i = close + 1
            return null
        }
        return reported(close + 1)
    }

    /**
     * `Is` followed by a Unicode block name such as `BasicLatin`; Java's own `Is` names for scripts
     * and binary properties (`IsLatin`, `IsAlphabetic`) are not blocks and fail here.
     */
    private fun isBlock(name: String): Boolean =
        BLOCK.matches(name) &&
            runCatching { Character.UnicodeBlock.forName(name.removePrefix("Is")) }.isSuccess

    /** The end of the escape at [i] followed by up to [max] characters from [allowed]. */
    private fun digits(max: Int, allowed: String): Int {
        var end = i + 2
        while (end < s.length && end < i + 2 + max && s[end] in allowed) end++
        return end
    }

    /** The end of an escape at [i] written with a braced argument, such as `\x{41}`. */
    private fun braced(): Int = s.indexOf('}', i).let { if (it < 0) s.length else it + 1 }

    private fun reported(end: Int): String = s.substring(i, end)

    private companion object {
        /** XSD 1.0's single-character and multi-character escapes, plus `$` as a literal. */
        const val SINGLE_CHAR_ESCAPES = "nrt\\|.-^?*+{}()[]\$sSiIcCdDwW"
        const val HEX = "0123456789abcdefABCDEF"
        val BOUNDS = Regex("[0-9]+(,[0-9]*)?")
        val BLOCK = Regex("Is[A-Za-z0-9\\-]+")
        /** Unicode general categories; a letter alone names its whole group. */
        val CATEGORIES =
            ("L Lu Ll Lt Lm Lo M Mn Mc Me N Nd Nl No P Pc Pd Ps Pe Pi Pf Po " +
                    "Z Zs Zl Zp S Sm Sc Sk So C Cc Cf Co Cn")
                .split(' ')
                .toSet()
    }
}
