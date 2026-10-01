package io.schemata.evolution

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypeCompatTest {
    private fun scalar(
        builtin: Builtin,
        max: BigDecimal? = null,
        precision: Int? = null,
        scale: Int? = null,
    ) = Scalar(builtin, Refinements(max = max, precision = precision, scale = scale))

    private val recordRef = Ref(QualifiedName("s", listOf("Record")))
    private val enumRef = Ref(QualifiedName("s", listOf("Status")))

    @Test
    fun `proto treats int32 and int64 as compatible`() {
        assertEquals(
            Verdict.Compatible,
            TypeCompat.proto(scalar(Builtin.INT32), scalar(Builtin.INT64)),
        )
    }

    @Test
    fun `proto notes a string becoming bytes`() {
        val note =
            assertIs<Verdict.Note>(TypeCompat.proto(scalar(Builtin.STRING), scalar(Builtin.BYTES)))
        assertEquals("old values must be valid UTF-8", note.message)
    }

    @Test
    fun `proto breaks int32 becoming a string`() {
        assertIs<Verdict.Breaking>(TypeCompat.proto(scalar(Builtin.INT32), scalar(Builtin.STRING)))
    }

    @Test
    fun `proto treats a uuid and a string as the same wire keyword`() {
        assertEquals(
            Verdict.Compatible,
            TypeCompat.proto(scalar(Builtin.UUID), scalar(Builtin.STRING)),
        )
    }

    @Test
    fun `proto breaks an instant becoming a string`() {
        assertIs<Verdict.Breaking>(
            TypeCompat.proto(scalar(Builtin.INSTANT), scalar(Builtin.STRING))
        )
    }

    @Test
    fun `proto treats an enum reference and int32 as compatible`() {
        assertEquals(Verdict.Compatible, TypeCompat.proto(enumRef, scalar(Builtin.INT32)))
    }

    @Test
    fun `proto breaks a scalar becoming a record reference`() {
        assertIs<Verdict.Breaking>(TypeCompat.proto(scalar(Builtin.BOOL), recordRef))
    }

    @Test
    fun `sql widens int32 to int64`() {
        assertTrue(TypeCompat.sqlWidening(scalar(Builtin.INT32), scalar(Builtin.INT64)))
    }

    @Test
    fun `sql widens float32 to float64`() {
        assertTrue(TypeCompat.sqlWidening(scalar(Builtin.FLOAT32), scalar(Builtin.FLOAT64)))
    }

    @Test
    fun `sql widens a shorter string into a longer one`() {
        assertTrue(
            TypeCompat.sqlWidening(
                scalar(Builtin.STRING, max = BigDecimal(5)),
                scalar(Builtin.STRING, max = BigDecimal(10)),
            )
        )
    }

    @Test
    fun `sql widens a bounded string into an unbounded one`() {
        assertTrue(
            TypeCompat.sqlWidening(
                scalar(Builtin.STRING, max = BigDecimal(10)),
                scalar(Builtin.STRING),
            )
        )
    }

    @Test
    fun `sql widens a decimal's precision at the same scale`() {
        assertTrue(
            TypeCompat.sqlWidening(
                scalar(Builtin.DECIMAL, precision = 10, scale = 2),
                scalar(Builtin.DECIMAL, precision = 12, scale = 2),
            )
        )
    }

    @Test
    fun `sql rejects a decimal's scale change`() {
        assertFalse(
            TypeCompat.sqlWidening(
                scalar(Builtin.DECIMAL, precision = 10, scale = 2),
                scalar(Builtin.DECIMAL, precision = 10, scale = 3),
            )
        )
    }

    @Test
    fun `sql rejects narrowing int64 to int32`() {
        assertFalse(TypeCompat.sqlWidening(scalar(Builtin.INT64), scalar(Builtin.INT32)))
    }

    @Test
    fun `instance widening accepts everything sql does`() {
        assertTrue(TypeCompat.instanceWidening(scalar(Builtin.INT32), scalar(Builtin.INT64)))
        assertTrue(TypeCompat.instanceWidening(scalar(Builtin.FLOAT32), scalar(Builtin.FLOAT64)))
        assertFalse(TypeCompat.instanceWidening(scalar(Builtin.INT64), scalar(Builtin.INT32)))
    }

    @Test
    fun `instance widening also relaxes a uuid into a string but not back`() {
        assertTrue(TypeCompat.instanceWidening(scalar(Builtin.UUID), scalar(Builtin.STRING)))
        assertFalse(TypeCompat.instanceWidening(scalar(Builtin.STRING), scalar(Builtin.UUID)))
    }
}
