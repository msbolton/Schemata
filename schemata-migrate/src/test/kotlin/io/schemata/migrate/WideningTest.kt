package io.schemata.migrate

import io.schemata.target.sql.ColumnType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WideningTest {
    @Test
    fun `wider integers floats strings and numerics are lossless`() {
        assertTrue(Widening.lossless(ColumnType.INTEGER, ColumnType.BIGINT))
        assertTrue(Widening.lossless(ColumnType.REAL, ColumnType.DOUBLE))
        assertTrue(Widening.lossless(ColumnType.VARCHAR(10), ColumnType.VARCHAR(20)))
        assertTrue(Widening.lossless(ColumnType.VARCHAR(10), ColumnType.TEXT))
        assertTrue(Widening.lossless(ColumnType.UUID, ColumnType.TEXT))
        assertTrue(Widening.lossless(ColumnType.NUMERIC(10, 2), ColumnType.NUMERIC(12, 2)))
        assertTrue(
            Widening.lossless(
                ColumnType.ARRAY(ColumnType.INTEGER),
                ColumnType.ARRAY(ColumnType.BIGINT),
            )
        )
    }

    @Test
    fun `narrower or recast types are not`() {
        assertFalse(Widening.lossless(ColumnType.BIGINT, ColumnType.INTEGER))
        assertFalse(Widening.lossless(ColumnType.VARCHAR(20), ColumnType.VARCHAR(10)))
        assertFalse(Widening.lossless(ColumnType.NUMERIC(10, 2), ColumnType.NUMERIC(12, 4)))
        assertFalse(Widening.lossless(ColumnType.TEXT, ColumnType.UUID))
        assertFalse(Widening.lossless(ColumnType.RAW("citext"), ColumnType.TEXT))
        assertFalse(Widening.lossless(ColumnType.INTEGER, ColumnType.TEXT))
    }

    @Test
    fun `an override is judged by the type it spells`() {
        assertTrue(Widening.lossless(ColumnType.RAW("integer"), ColumnType.RAW("bigint")))
        assertTrue(Widening.lossless(ColumnType.INTEGER, ColumnType.RAW("int8")))
        assertTrue(Widening.lossless(ColumnType.RAW("varchar(20)"), ColumnType.RAW("varchar(40)")))
        assertTrue(Widening.lossless(ColumnType.RAW("citext"), ColumnType.RAW("CITEXT")))
        assertFalse(Widening.lossless(ColumnType.RAW("bigint"), ColumnType.RAW("integer")))
        assertFalse(Widening.lossless(ColumnType.RAW("citext"), ColumnType.RAW("text")))
    }
}
