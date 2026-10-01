package io.schemata.importer.xsd

import java.math.BigInteger

/**
 * One lossy-import note: an `SCH24xx` code, the message tail after `<where>: `, and the `.xsd`
 * line.
 */
data class Note(val code: String, val tail: String, val line: Int)

/** Maps XSD builtins and facets to Schemata scalars, per the type table and facet rules. */
object ImportTypes {
    val XS: String = XsdReader.XS

    /** The uuid pattern `§19.3`'s XSD target writes for `uuid`. */
    private const val UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"

    private val numeric = setOf("int32", "int64", "float32", "float64", "decimal")
    private val lengthBearing = setOf("string", "bytes")

    data class Mapped(val type: UnitType.Scalar, val notes: List<String>)

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

    // "any other builtin" per §30.4: widened to string with SCH2404.
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
            "ENTITIES",
            "IDREFS",
            "NMTOKENS",
        )

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
            local in otherBuiltins ->
                Mapped(scalar("string"), listOf("xs:$local imported as string"))
            else -> null
        }

    private fun dropped(f: XFacet) = Note("SCH2404", "facet ${f.name} dropped", f.line)

    /** Applies the facet rules of §30.4 to [base], returning the refined scalar and lossy notes. */
    fun facets(base: UnitType.Scalar, facets: List<XFacet>): Pair<UnitType.Scalar, List<Note>> {
        val notes = mutableListOf<Note>()
        val refinements = mutableListOf<Pair<String, String>>()
        var remaining = facets

        if (base.builtin == "decimal") {
            val total = facets.firstOrNull { it.name == "totalDigits" }
            val fraction = facets.firstOrNull { it.name == "fractionDigits" }
            if (total != null && fraction != null) {
                refinements += "p" to total.value
                refinements += "s" to fraction.value
            } else {
                refinements += "p" to "38"
                refinements += "s" to "9"
                val line = total?.line ?: fraction?.line ?: 0
                notes +=
                    Note(
                        "SCH2403",
                        "decimal without totalDigits and fractionDigits imported as decimal(38, 9)",
                        line,
                    )
            }
            remaining = facets.filterNot { it.name == "totalDigits" || it.name == "fractionDigits" }
        }

        var patternSeen = false
        for (f in remaining) {
            when (f.name) {
                "minInclusive" ->
                    if (base.builtin in numeric) refinements += "min" to f.value
                    else notes += dropped(f)
                "maxInclusive" ->
                    if (base.builtin in numeric) refinements += "max" to f.value
                    else notes += dropped(f)
                "minExclusive" ->
                    if (base.builtin == "int32" || base.builtin == "int64") {
                        refinements += "min" to (BigInteger(f.value) + BigInteger.ONE).toString()
                    } else {
                        notes += dropped(f)
                    }
                "maxExclusive" ->
                    if (base.builtin == "int32" || base.builtin == "int64") {
                        refinements += "max" to (BigInteger(f.value) - BigInteger.ONE).toString()
                    } else {
                        notes += dropped(f)
                    }
                "minLength" ->
                    if (base.builtin in lengthBearing) refinements += "min" to f.value
                    else notes += dropped(f)
                "maxLength" ->
                    if (base.builtin in lengthBearing) refinements += "max" to f.value
                    else notes += dropped(f)
                "length" ->
                    if (base.builtin in lengthBearing) {
                        refinements += "min" to f.value
                        refinements += "max" to f.value
                    } else {
                        notes += dropped(f)
                    }
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
                        refinements += "pattern" to "\"" + unanchor(f.value) + "\""
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
     * Reverses §19.3's anchoring: `.*(X).*` → `X`, `(X).*` → `^X`, `.*(X)` → `X$`, anything else →
     * `^X$`. A literal `$` already in the pattern is escaped so it does not read as an anchor.
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

    /** Quotes strings (escaping `\` and `"`); numbers and booleans pass through as written. */
    fun defaultLiteral(builtin: String, text: String): String =
        when (builtin) {
            "string" -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else -> text
        }
}
