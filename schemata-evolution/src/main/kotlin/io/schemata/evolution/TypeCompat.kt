package io.schemata.evolution

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type

/**
 * Whether data shaped by one type can still be read once a field's type becomes another,
 * independent of any particular pair of schemas or the member the type belongs to.
 */
object TypeCompat {
    /**
     * Protobuf's own wire-compatibility rules: the same keyword, `int32` widened to `int64`, or a
     * string-shaped keyword (`string`, `uuid`, `date`, `time`, `decimal`) becoming `bytes`. A bare
     * reference paired with `int32` is treated as an enum, since only an enum's wire format
     * (`int32`) is ever compatible with a scalar.
     */
    fun proto(from: Type, to: Type): Verdict {
        if (isEnumRefAndInt32(from, to)) return Verdict.Compatible
        val fromKeyword = protoKeyword(from)
        val toKeyword = protoKeyword(to)
        val keywords = setOf(fromKeyword, toKeyword)
        return when {
            fromKeyword == toKeyword -> Verdict.Compatible
            keywords == setOf("int32", "int64") -> Verdict.Compatible
            keywords == setOf("string", "bytes") ->
                Verdict.Note(
                    "old values must be valid UTF-8",
                    "store and validate the value as UTF-8 bytes",
                )
            else ->
                Verdict.Breaking(
                    "the wire types are incompatible",
                    "add a new field instead of changing this one's type",
                )
        }
    }

    /**
     * The Postgres widenings: a wider integer or float, a string with the same or no bound becoming
     * a longer or unbounded one, or a decimal keeping its scale while gaining precision.
     */
    fun sqlWidening(from: Type, to: Type): Boolean {
        if (from !is Scalar || to !is Scalar) return false
        return when (from.builtin to to.builtin) {
            Builtin.INT32 to Builtin.INT64 -> true
            Builtin.FLOAT32 to Builtin.FLOAT64 -> true
            Builtin.STRING to Builtin.STRING -> stringWidened(from.refinements, to.refinements)
            Builtin.DECIMAL to Builtin.DECIMAL -> decimalWidened(from.refinements, to.refinements)
            else -> from.builtin == to.builtin && from.refinements == to.refinements
        }
    }

    /** [sqlWidening]'s list, plus a uuid relaxing into a plain string (never the other way). */
    fun instanceWidening(from: Type, to: Type): Boolean {
        if (
            from is Scalar &&
                to is Scalar &&
                from.builtin == Builtin.UUID &&
                to.builtin == Builtin.STRING
        ) {
            return true
        }
        return sqlWidening(from, to)
    }

    private fun isEnumRefAndInt32(from: Type, to: Type): Boolean =
        (from is Ref && to is Scalar && to.builtin == Builtin.INT32) ||
            (to is Ref && from is Scalar && from.builtin == Builtin.INT32)

    private fun protoKeyword(type: Type): String =
        when (type) {
            is Scalar -> scalarKeyword(type.builtin)
            is ListOf -> "list<${protoKeyword(type.element)}>"
            is MapOf -> "map<${protoKeyword(type.key)}, ${protoKeyword(type.value)}>"
            is Ref -> "ref:${type.target}"
        }

    private fun scalarKeyword(builtin: Builtin): String =
        when (builtin) {
            Builtin.BOOL -> "bool"
            Builtin.INT32 -> "int32"
            Builtin.INT64 -> "int64"
            Builtin.FLOAT32 -> "float"
            Builtin.FLOAT64 -> "double"
            Builtin.STRING,
            Builtin.UUID,
            Builtin.DATE,
            Builtin.TIME,
            Builtin.DECIMAL -> "string"
            Builtin.BYTES -> "bytes"
            Builtin.INSTANT -> "google.protobuf.Timestamp"
            Builtin.DURATION -> "google.protobuf.Duration"
        }

    private fun stringWidened(from: Refinements, to: Refinements): Boolean {
        val toMax = to.max ?: return true
        val fromMax = from.max ?: return false
        return toMax >= fromMax
    }

    private fun decimalWidened(from: Refinements, to: Refinements): Boolean {
        val fromPrecision = from.precision ?: return false
        val toPrecision = to.precision ?: return false
        return from.scale == to.scale && toPrecision >= fromPrecision
    }
}
