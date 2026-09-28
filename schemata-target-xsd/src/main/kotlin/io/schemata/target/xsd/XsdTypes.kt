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

    private val unsupported =
        listOf("(?", "\\b", "\\B", "\\A", "\\z", "\\Z", "*+", "++", "?+", "}+") +
            (1..9).map { "\\$it" }

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

    /** Strips the implicit anchors and names the first construct XSD regexes lack. */
    fun pattern(pattern: String): Pattern {
        val bad = unsupported.firstOrNull { it in pattern.removePrefix("^").removeSuffix("$") }
        if (bad != null) return Pattern(null, bad)
        return Pattern(pattern.removePrefix("^").removeSuffix("$"), null)
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
