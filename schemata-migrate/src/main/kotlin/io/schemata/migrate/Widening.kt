package io.schemata.migrate

import io.schemata.target.sql.ColumnType

/** Column type changes every existing value survives, so `ALTER COLUMN … TYPE … USING` is clean. */
object Widening {
    fun lossless(from: ColumnType, to: ColumnType): Boolean =
        when {
            from == to -> true
            from == ColumnType.INTEGER && to == ColumnType.BIGINT -> true
            from == ColumnType.REAL && to == ColumnType.DOUBLE -> true
            from is ColumnType.VARCHAR && to is ColumnType.VARCHAR -> to.length >= from.length
            from is ColumnType.VARCHAR && to == ColumnType.TEXT -> true
            from == ColumnType.UUID && to == ColumnType.TEXT -> true
            from == ColumnType.UUID && to is ColumnType.VARCHAR -> to.length >= 36
            from is ColumnType.NUMERIC && to is ColumnType.NUMERIC ->
                to.scale == from.scale && to.precision >= from.precision
            from is ColumnType.ARRAY && to is ColumnType.ARRAY -> lossless(from.element, to.element)
            else -> false
        }
}
