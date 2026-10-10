package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.importer.UnitType
import io.schemata.lang.DiagnosticCode
import java.math.BigDecimal
import java.math.BigInteger

/**
 * One lossy-import note: an `SCH24xx` code, the message tail after `<where>: `, and the `.xsd`
 * line.
 */
data class Note(val code: DiagnosticCode, val tail: String, val line: Int)

/** Maps XSD builtins and facets to Schemata scalars, per the type table and facet rules. */
object ImportTypes {
    val XS: String = XsdReader.XS

    /**
     * The pattern the XSD target writes for a `uuid` field; a string restricted by exactly this
     * pattern is recognised as `uuid` on the way back in.
     */
    private const val UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    private val numeric = setOf("int32", "int64", "float32", "float64", "decimal")
    private val lengthBearing = setOf("string", "bytes")

    data class Mapped(val type: UnitType, val notes: List<String>)

    private fun scalar(builtin: String, vararg refinements: Pair<String, String>) =
        UnitType.Scalar(builtin, refinements.toList())

    private val int32Names =
        setOf("int", "integer", "short", "byte", "unsignedShort", "unsignedByte")
    private val int64Names = setOf("long", "unsignedInt")
    private val stringNames =
        setOf(
            "string",
            "normalizedString",
            "token",
            "language",
            "Name",
            "NCName",
            "NMTOKEN",
            "ID",
            "IDREF",
            "anyURI",
        )

    // Every other XSD builtin: widened to string with SCH2404, since Schemata has no equivalent.
    private val otherBuiltins =
        setOf(
            "gYear",
            "gMonth",
            "gDay",
            "gYearMonth",
            "gMonthDay",
            "QName",
            "NOTATION",
            "anyType",
            "anySimpleType",
            "anyAtomicType",
            "ENTITY",
        )

    // The three list builtins: whitespace-separated lists of strings, so a list of string.
    private val listNames = setOf("ENTITIES", "IDREFS", "NMTOKENS")

    /** `null` when [local] is not an XSD builtin local name. */
    fun builtin(local: String): Mapped? =
        when {
            local == "boolean" -> Mapped(scalar("bool"), emptyList())
            local in int32Names -> Mapped(scalar("int32"), emptyList())
            local in int64Names -> Mapped(scalar("int64"), emptyList())
            local == "unsignedLong" || local == "nonNegativeInteger" ->
                Mapped(scalar("int64", "min" to "0"), emptyList())
            local == "positiveInteger" -> Mapped(scalar("int64", "min" to "1"), emptyList())
            local == "negativeInteger" -> Mapped(scalar("int64", "max" to "-1"), emptyList())
            local == "nonPositiveInteger" -> Mapped(scalar("int64", "max" to "0"), emptyList())
            local == "float" -> Mapped(scalar("float32"), emptyList())
            local == "double" -> Mapped(scalar("float64"), emptyList())
            local == "decimal" -> Mapped(scalar("decimal"), emptyList())
            local in stringNames -> Mapped(scalar("string"), emptyList())
            local == "base64Binary" -> Mapped(scalar("bytes"), emptyList())
            local == "hexBinary" ->
                Mapped(scalar("bytes"), listOf("xs:hexBinary imported as bytes"))
            local == "date" -> Mapped(scalar("date"), emptyList())
            local == "time" -> Mapped(scalar("time"), emptyList())
            local == "dateTime" -> Mapped(scalar("instant"), emptyList())
            local == "duration" -> Mapped(scalar("duration"), emptyList())
            local in listNames ->
                Mapped(
                    UnitType.ListOf(scalar("string"), false, emptyList()),
                    listOf("xs:$local imported as a list of string"),
                )
            local in otherBuiltins ->
                Mapped(scalar("string"), listOf("xs:$local imported as string"))
            else -> null
        }

    private fun dropped(f: XFacet) = Note(ImportCodes.WIDENED, "facet ${f.name} dropped", f.line)

    private fun unparsed(f: XFacet) =
        Note(ImportCodes.WIDENED, "facet ${f.name} value '${f.value}' dropped", f.line)

    private val count = Regex("^[0-9]+$")

    /** A length or digit-count facet's value: a non-negative integer, or `null`. */
    private fun countOf(f: XFacet): String? =
        f.value.trim().takeIf { count.matches(it) }?.let { BigInteger(it).toString() }

    /**
     * Applies the XSD facet rules to [base], returning the refined scalar and the lossy notes.
     * [line] is where the facets are declared, for a note that no single facet carries; a facet
     * whose value does not parse as its kind requires is dropped and noted.
     */
    fun facets(
        base: UnitType.Scalar,
        facets: List<XFacet>,
        line: Int,
    ): Pair<UnitType.Scalar, List<Note>> {
        val notes = mutableListOf<Note>()
        val refinements = mutableListOf<Pair<String, String>>()
        var remaining = facets

        if (base.builtin == "decimal") {
            val digits = facets.filter { it.name == "totalDigits" || it.name == "fractionDigits" }
            digits.filter { countOf(it) == null }.forEach { notes += unparsed(it) }
            val total = digits.firstOrNull { it.name == "totalDigits" }?.let(::countOf)
            val fraction = digits.firstOrNull { it.name == "fractionDigits" }?.let(::countOf)
            if (total != null && fraction != null) {
                refinements += "p" to total
                refinements += "s" to fraction
            } else {
                refinements += "p" to "38"
                refinements += "s" to "9"
                val at = digits.firstOrNull()?.line ?: line
                notes +=
                    Note(
                        ImportCodes.APPROXIMATED,
                        "decimal without totalDigits and fractionDigits imported as decimal(38, 9)",
                        at,
                    )
            }
            remaining = facets.filterNot { it.name == "totalDigits" || it.name == "fractionDigits" }
        }

        fun bound(key: String, f: XFacet) {
            val value = plainNumber(f.value)
            if (value == null) notes += unparsed(f) else refinements += key to value
        }

        fun exclusive(key: String, f: XFacet, step: BigInteger) {
            val value =
                try {
                    BigInteger(f.value.trim())
                } catch (e: NumberFormatException) {
                    null
                }
            if (value == null) notes += unparsed(f)
            else refinements += key to (value + step).toString()
        }

        fun length(f: XFacet, vararg keys: String) {
            val value = countOf(f)
            if (value == null) notes += unparsed(f) else keys.forEach { refinements += it to value }
        }

        var patternSeen = false
        for (f in remaining) {
            when (f.name) {
                "minInclusive" ->
                    if (base.builtin in numeric) bound("min", f) else notes += dropped(f)
                "maxInclusive" ->
                    if (base.builtin in numeric) bound("max", f) else notes += dropped(f)
                "minExclusive" ->
                    if (base.builtin == "int32" || base.builtin == "int64") {
                        exclusive("min", f, BigInteger.ONE)
                    } else {
                        notes += dropped(f)
                    }
                "maxExclusive" ->
                    if (base.builtin == "int32" || base.builtin == "int64") {
                        exclusive("max", f, BigInteger.ONE.negate())
                    } else {
                        notes += dropped(f)
                    }
                "minLength" ->
                    if (base.builtin in lengthBearing) length(f, "min") else notes += dropped(f)
                "maxLength" ->
                    if (base.builtin in lengthBearing) length(f, "max") else notes += dropped(f)
                "length" ->
                    if (base.builtin in lengthBearing) length(f, "min", "max")
                    else notes += dropped(f)
                "pattern" ->
                    if (base.builtin != "string") {
                        notes += dropped(f)
                    } else if (patternSeen) {
                        notes += dropped(f)
                    } else {
                        patternSeen = true
                        if (isUuidPattern(f.value)) {
                            return Pair(UnitType.Scalar("uuid", emptyList()), emptyList())
                        }
                        val literal = quotePattern(unanchor(f.value))
                        if (literal == null) notes += unparsed(f)
                        else refinements += "pattern" to literal
                    }
                else -> notes += dropped(f)
            }
        }
        return Pair(base.copy(refinements = refinements), notes)
    }

    fun isUuidPattern(pattern: String): Boolean = pattern == UUID_PATTERN

    private val wrappedBoth = Regex("""\.\*\((.*)\)\.\*""")
    private val wrappedLeft = Regex("""\((.*)\)\.\*""")
    private val wrappedRight = Regex("""\.\*\((.*)\)""")
    private val unescapedDollar = Regex("""(?<!\\)\$""")

    /**
     * The XSD target anchors a partial-match pattern by wrapping each open side in `.*` and
     * capturing the rest in a group; this reverses it: `.*(X).*` → `X`, `(X).*` → `^X`, `.*(X)` →
     * `X$`, anything else (already a full match) → `^X$`. A literal `$` already in the pattern is
     * escaped so it does not read as an anchor.
     */
    fun unanchor(pattern: String): String {
        val (core, left, right) =
            wrappedBoth.matchEntire(pattern)?.let { Triple(it.groupValues[1], false, false) }
                ?: wrappedLeft.matchEntire(pattern)?.let { Triple(it.groupValues[1], true, false) }
                ?: wrappedRight.matchEntire(pattern)?.let { Triple(it.groupValues[1], false, true) }
                ?: Triple(pattern, true, true)
        val escaped = unescapedDollar.replace(core) { "\\$" }
        return (if (left) "^" else "") + escaped + (if (right) "$" else "")
    }

    // An odd run of backslashes right before a quote or line break: once the quote or break is
    // escaped, the run's last backslash would pair with the escape's and the literal would read
    // differently or not at all. No valid XSD regex holds one.
    private val loneBackslash = Regex("""(?<!\\)(\\\\)*\\["\n\r]""")

    /**
     * A pattern as a Schemata string literal: only a quote is escaped, every backslash stays. A
     * line break is written as the regex escape for it, since a string literal cannot span lines.
     * Null when the pattern holds a lone backslash before a quote or line break, which has no
     * literal.
     */
    fun quotePattern(pattern: String): String? {
        if (loneBackslash.containsMatchIn(pattern)) return null
        return "\"" + pattern.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    }

    /**
     * [text] as a Schemata string literal: quoted, with `\` and `"` escaped, and a line break or
     * tab written as its escape, since a string literal cannot span lines.
     */
    fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /**
     * The Schemata literal for an XSD default [text] on a [builtin] scalar, or `null` when there is
     * none: a number prints as a plain decimal (`.5` → `0.5`, `1e5` → `100000`; `INF`, `NaN`, and
     * anything unparseable have no literal); a boolean accepts `true`, `false`, `1`, and `0`; a
     * string is quoted; every other scalar (`uuid`, `date`, `time`, `instant`, `duration`, `bytes`)
     * has no literal form at all.
     */
    fun defaultLiteral(builtin: String, text: String): String? =
        when (builtin) {
            "string" -> quote(text)
            "bool" ->
                when (text.trim()) {
                    "true",
                    "1" -> "true"
                    "false",
                    "0" -> "false"
                    else -> null
                }
            in numeric -> plainNumber(text)
            else -> null
        }

    private fun plainNumber(text: String): String? =
        try {
            BigDecimal(text.trim()).toPlainString()
        } catch (e: NumberFormatException) {
            null
        }
}
