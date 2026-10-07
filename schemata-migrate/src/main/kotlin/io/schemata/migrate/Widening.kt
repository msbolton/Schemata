package io.schemata.migrate

import io.schemata.target.sql.ColumnType

/**
 * Column type changes every existing value survives, so `ALTER COLUMN … TYPE … USING` is clean. An
 * `@sql(type)` override is judged by its spelling: one that spells a type the SQL target knows
 * (`bigint`, `varchar(40)`, `numeric(12, 2)`, `int8`) widens like that type does; any other
 * spelling only survives a change to itself.
 */
object Widening {
    fun lossless(from: ColumnType, to: ColumnType): Boolean {
        val a = known(from)
        val b = known(to)
        return when {
            a == b -> true
            a == ColumnType.INTEGER && b == ColumnType.BIGINT -> true
            a == ColumnType.REAL && b == ColumnType.DOUBLE -> true
            a is ColumnType.VARCHAR && b is ColumnType.VARCHAR -> b.length >= a.length
            a is ColumnType.VARCHAR && b == ColumnType.TEXT -> true
            a == ColumnType.UUID && b == ColumnType.TEXT -> true
            a == ColumnType.UUID && b is ColumnType.VARCHAR -> b.length >= 36
            a is ColumnType.NUMERIC && b is ColumnType.NUMERIC ->
                b.scale == a.scale && b.precision >= a.precision
            a is ColumnType.ARRAY && b is ColumnType.ARRAY -> lossless(a.element, b.element)
            else -> false
        }
    }

    private val NAMED: Map<String, ColumnType> =
        mapOf(
            "boolean" to ColumnType.BOOLEAN,
            "bool" to ColumnType.BOOLEAN,
            "integer" to ColumnType.INTEGER,
            "int" to ColumnType.INTEGER,
            "int4" to ColumnType.INTEGER,
            "bigint" to ColumnType.BIGINT,
            "int8" to ColumnType.BIGINT,
            "real" to ColumnType.REAL,
            "float4" to ColumnType.REAL,
            "double precision" to ColumnType.DOUBLE,
            "float8" to ColumnType.DOUBLE,
            "text" to ColumnType.TEXT,
            "bytea" to ColumnType.BYTEA,
            "uuid" to ColumnType.UUID,
            "date" to ColumnType.DATE,
            "time" to ColumnType.TIME,
            "timestamptz" to ColumnType.TIMESTAMPTZ,
            "timestamp with time zone" to ColumnType.TIMESTAMPTZ,
            "interval" to ColumnType.INTERVAL,
            "jsonb" to ColumnType.JSONB,
        )

    private val VARCHAR = Regex("""(?:varchar|character varying)\s*\(\s*(\d+)\s*\)""")
    private val NUMERIC = Regex("""(?:numeric|decimal)\s*\(\s*(\d+)\s*,\s*(\d+)\s*\)""")

    /** [type] with an override spelling a known type read as that type; anything else as is. */
    private fun known(type: ColumnType): ColumnType {
        if (type !is ColumnType.RAW) return type
        val spelling = type.spelling.trim().lowercase().replace(Regex("\\s+"), " ")
        if (spelling.endsWith("[]")) {
            val element = known(ColumnType.RAW(spelling.removeSuffix("[]")))
            return if (element is ColumnType.RAW) type else ColumnType.ARRAY(element)
        }
        NAMED[spelling]?.let {
            return it
        }
        VARCHAR.matchEntire(spelling)?.let {
            return ColumnType.VARCHAR(it.groupValues[1].toInt())
        }
        NUMERIC.matchEntire(spelling)?.let {
            return ColumnType.NUMERIC(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return ColumnType.RAW(spelling)
    }
}
