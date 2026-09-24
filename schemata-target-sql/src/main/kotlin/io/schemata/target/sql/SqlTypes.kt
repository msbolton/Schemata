package io.schemata.target.sql

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.target.sql.Naming.literal
import io.schemata.target.sql.Naming.quote

/** Builtins to column types, and refinements to CHECK expressions over a quoted column. */
object SqlTypes {
    /** [checks] pairs a name suffix (`min`, `max`, `pattern`, `enum`) with its expression. */
    class Mapped(val type: ColumnType, val checks: List<Pair<String, String>>)

    /**
     * [overridden] is true when an `@sql(type)` override will replace [Mapped.type]: a string's
     * `max`-only bound can no longer ride on `VARCHAR(n)`, so it is spelled out as a CHECK instead.
     */
    fun scalar(scalar: Scalar, column: String, overridden: Boolean = false): Mapped {
        val q = quote(column)
        val r = scalar.refinements
        return when (scalar.builtin) {
            Builtin.BOOL -> Mapped(ColumnType.BOOLEAN, emptyList())
            Builtin.INT32 -> Mapped(ColumnType.INTEGER, bounds(q, r))
            Builtin.INT64 -> Mapped(ColumnType.BIGINT, bounds(q, r))
            Builtin.FLOAT32 -> Mapped(ColumnType.REAL, bounds(q, r))
            Builtin.FLOAT64 -> Mapped(ColumnType.DOUBLE, bounds(q, r))
            Builtin.DECIMAL -> Mapped(ColumnType.NUMERIC(r.precision!!, r.scale!!), bounds(q, r))
            Builtin.STRING -> string(q, r, overridden)
            Builtin.BYTES -> Mapped(ColumnType.BYTEA, lengths("octet_length($q)", r))
            Builtin.UUID -> Mapped(ColumnType.UUID, emptyList())
            Builtin.DATE -> Mapped(ColumnType.DATE, emptyList())
            Builtin.TIME -> Mapped(ColumnType.TIME, emptyList())
            Builtin.INSTANT -> Mapped(ColumnType.TIMESTAMPTZ, emptyList())
            Builtin.DURATION -> Mapped(ColumnType.INTERVAL, emptyList())
        }
    }

    /** An enum reference: text constrained to the value names. */
    fun enum(values: List<String>, column: String): Mapped =
        Mapped(
            ColumnType.TEXT,
            listOf("enum" to "${quote(column)} IN (${values.joinToString(", ") { literal(it) }})"),
        )

    private fun string(q: String, r: Refinements, overridden: Boolean): Mapped {
        val max = r.max
        if (!overridden && max != null && r.min == null && r.pattern == null)
            return Mapped(ColumnType.VARCHAR(max.toInt()), emptyList())
        val checks =
            lengths("char_length($q)", r) +
                listOfNotNull(r.pattern?.let { "pattern" to "$q ~ ${literal(it)}" })
        return Mapped(ColumnType.TEXT, checks)
    }

    private fun bounds(q: String, r: Refinements): List<Pair<String, String>> =
        listOfNotNull(
            r.min?.let { "min" to "$q >= ${it.toPlainString()}" },
            r.max?.let { "max" to "$q <= ${it.toPlainString()}" },
        )

    private fun lengths(measure: String, r: Refinements): List<Pair<String, String>> =
        listOfNotNull(
            r.min?.let { "min" to "$measure >= ${it.toPlainString()}" },
            r.max?.let { "max" to "$measure <= ${it.toPlainString()}" },
        )
}
