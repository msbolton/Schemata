package io.schemata.importer.sql

import io.schemata.importer.UnitType
import io.schemata.lang.SchemataText
import java.math.BigDecimal
import java.math.BigInteger

// Column types and check expressions read as Schemata types and refinements.

internal val INTEGER = Regex("^-?\\d+$")
internal val DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")

/**
 * A column type read as a builtin. [canonical] is the spelling to compare with what the SQL target
 * writes for the result; [foreign] marks a spelling the target never writes, kept with `@sql(type)`
 * and reported as widened; [generated] marks a serial.
 */
internal class Mapped(
    val builtin: String,
    val refinements: List<Pair<String, String>>,
    val canonical: String,
    val foreign: Boolean,
    val generated: Boolean = false,
    val approximated: String? = null,
)

internal fun mapType(written: String): Mapped {
    val head = written.substringBefore('(').trim()
    val args =
        if ('(' in written)
            written.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        else emptyList()
    fun own(
        builtin: String,
        refinements: List<Pair<String, String>> = emptyList(),
        canonical: String = written,
    ) = Mapped(builtin, refinements, canonical, foreign = false)
    fun foreign(
        builtin: String,
        refinements: List<Pair<String, String>> = emptyList(),
        generated: Boolean = false,
    ) = Mapped(builtin, refinements, written, foreign = true, generated = generated)
    val number = args.singleOrNull()?.toIntOrNull()
    return when {
        written == "boolean" -> own("bool")
        written == "integer" -> own("int32")
        written == "bigint" -> own("int64")
        written == "real" -> own("float32")
        written == "double precision" -> own("float64")
        head == "numeric" && args.size == 2 && args.all { it.toIntOrNull() != null } ->
            own(
                "decimal",
                listOf("p" to args[0], "s" to args[1]),
                "numeric(${args[0]}, ${args[1]})",
            )
        head == "numeric" && number != null ->
            own("decimal", listOf("p" to "$number", "s" to "0"), "numeric($number, 0)")
        written == "numeric" ->
            Mapped(
                "decimal",
                listOf("p" to "38", "s" to "9"),
                "numeric(38, 9)",
                foreign = false,
                approximated = "numeric without precision imported as decimal(38, 9)",
            )
        written == "text" -> own("string")
        head == "varchar" && number != null -> own("string", listOf("max" to "$number"))
        written == "bytea" -> own("bytes")
        written == "uuid" -> own("uuid")
        written == "date" -> own("date")
        written == "time" -> own("time")
        written == "timestamptz" -> own("instant")
        written == "interval" -> own("duration")
        written in setOf("smallint", "smallserial", "serial2") ->
            foreign("int32", generated = written != "smallint")
        written in setOf("serial", "serial4") -> foreign("int32", generated = true)
        written in setOf("bigserial", "serial8") -> foreign("int64", generated = true)
        head == "char" ->
            (number ?: 1).let { n -> foreign("string", listOf("min" to "$n", "max" to "$n")) }
        head.startsWith("timestamp") -> foreign("instant")
        head.startsWith("time") -> foreign("time")
        head.startsWith("interval") -> foreign("duration")
        written == "money" -> foreign("decimal", listOf("p" to "19", "s" to "4"))
        else -> foreign("string")
    }
}

/** What the SQL target writes for a scalar field of [type] with no `@sql(type)`. */
internal fun spell(type: UnitType.Scalar): String {
    val r = type.refinements.toMap()
    return when (type.builtin) {
        "bool" -> "boolean"
        "int32" -> "integer"
        "int64" -> "bigint"
        "float32" -> "real"
        "float64" -> "double precision"
        "decimal" -> "numeric(${r["p"]}, ${r["s"]})"
        "string" -> {
            val max = r["max"]?.toBigDecimalOrNull()
            val varchar =
                r.keys == setOf("max") &&
                    max != null &&
                    max >= BigDecimal.ONE &&
                    max <= BigDecimal(10_485_760) &&
                    max.stripTrailingZeros().scale() <= 0
            if (varchar) "varchar(${max!!.toBigInteger()})" else "text"
        }
        "bytes" -> "bytea"
        "instant" -> "timestamptz"
        "duration" -> "interval"
        else -> type.builtin
    }
}

/** One part of a check that a refinement can say, over [column]. */
internal sealed interface Atom {
    val column: String

    /** `measure op n`: [measure] is `value`, `chars`, or `octets`. */
    data class Bound(
        override val column: String,
        val measure: String,
        val op: String,
        val n: String,
    ) : Atom

    data class Range(
        override val column: String,
        val measure: String,
        val low: String,
        val high: String,
    ) : Atom

    data class Pattern(override val column: String, val pattern: String, val insensitive: Boolean) :
        Atom
}

private fun measured(e: SqlExpr): Pair<String, String>? =
    when {
        e is SqlExpr.Col -> e.name to "value"
        e is SqlExpr.Call && e.args.size == 1 && e.args[0] is SqlExpr.Col -> {
            val column = (e.args[0] as SqlExpr.Col).name
            when (e.name) {
                "char_length",
                "character_length",
                "length" -> column to "chars"
                "octet_length" -> column to "octets"
                else -> null
            }
        }
        else -> null
    }

private val FLIPPED = mapOf(">=" to "<=", "<=" to ">=", ">" to "<", "<" to ">")

internal fun atom(e: SqlExpr): Atom? =
    when (e) {
        is SqlExpr.Bin ->
            when (e.op) {
                ">=",
                "<=",
                ">",
                "<" -> {
                    val left = measured(e.left)
                    val right = measured(e.right)
                    when {
                        left != null && e.right is SqlExpr.Num ->
                            Atom.Bound(left.first, left.second, e.op, (e.right as SqlExpr.Num).text)
                        right != null && e.left is SqlExpr.Num ->
                            Atom.Bound(
                                right.first,
                                right.second,
                                FLIPPED.getValue(e.op),
                                (e.left as SqlExpr.Num).text,
                            )
                        else -> null
                    }
                }
                "~",
                "~*" -> {
                    val column = (e.left as? SqlExpr.Col)?.name
                    val pattern = (e.right as? SqlExpr.Str)?.value
                    if (column != null && pattern != null)
                        Atom.Pattern(column, pattern, e.op == "~*")
                    else null
                }
                else -> null
            }
        is SqlExpr.Between -> {
            val m = measured(e.expr)
            val low = (e.low as? SqlExpr.Num)?.text
            val high = (e.high as? SqlExpr.Num)?.text
            if (m != null && low != null && high != null) Atom.Range(m.first, m.second, low, high)
            else null
        }
        else -> null
    }

/** The refinements [atom] gives a [builtin], or null when it does not apply to one. */
internal fun refinement(atom: Atom, builtin: String): List<Pair<String, String>>? {
    val measure =
        when (builtin) {
            "int32",
            "int64",
            "float32",
            "float64",
            "decimal" -> "value"
            "string" -> "chars"
            "bytes" -> "octets"
            else -> return null
        }
    val integral = builtin in setOf("int32", "int64") || measure != "value"
    fun number(n: String) =
        if (integral) n.takeIf { INTEGER.matches(it) } else n.takeIf { DECIMAL.matches(it) }
    return when (atom) {
        is Atom.Bound -> {
            if (atom.measure != measure) return null
            val n = number(atom.n) ?: return null
            when (atom.op) {
                ">=" -> listOf("min" to n)
                "<=" -> listOf("max" to n)
                ">" ->
                    if (integral) listOf("min" to (BigInteger(n) + BigInteger.ONE).toString())
                    else null
                "<" ->
                    if (integral) listOf("max" to (BigInteger(n) - BigInteger.ONE).toString())
                    else null
                else -> null
            }
        }
        is Atom.Range -> {
            if (atom.measure != measure) return null
            listOf(
                "min" to (number(atom.low) ?: return null),
                "max" to (number(atom.high) ?: return null),
            )
        }
        is Atom.Pattern ->
            if (builtin == "string") listOf("pattern" to SchemataText.pattern(atom.pattern))
            else null
    }
}
