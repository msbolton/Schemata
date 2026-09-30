package io.schemata.target.jsonschema

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import io.schemata.target.json.JsonBool
import io.schemata.target.json.JsonNumber
import io.schemata.target.json.JsonString
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonSchemaTypesTest {
    private val lossy = mutableListOf<String>()

    private fun scalar(builtin: Builtin, refinements: Refinements = Refinements.NONE) =
        JsonSchemaTypes.scalar(Scalar(builtin, refinements)) { m, _ -> lossy += m }

    @Test
    fun `plain scalars`() {
        assertEquals(ScalarSchema("boolean"), scalar(Builtin.BOOL))
        assertEquals(
            ScalarSchema(
                "integer",
                minimum = BigDecimal("-2147483648"),
                maximum = BigDecimal("2147483647"),
            ),
            scalar(Builtin.INT32),
        )
        assertEquals(
            ScalarSchema(
                "integer",
                minimum = BigDecimal("-9223372036854775808"),
                maximum = BigDecimal("9223372036854775807"),
            ),
            scalar(Builtin.INT64),
        )
        assertEquals(ScalarSchema("number"), scalar(Builtin.FLOAT32))
        assertEquals(ScalarSchema("number"), scalar(Builtin.FLOAT64))
        assertEquals(ScalarSchema("string"), scalar(Builtin.STRING))
        assertEquals(ScalarSchema("string", contentEncoding = "base64"), scalar(Builtin.BYTES))
        assertEquals(
            ScalarSchema("string", format = "uuid", pattern = JsonSchemaTypes.UUID_PATTERN),
            scalar(Builtin.UUID),
        )
        assertEquals(ScalarSchema("string", format = "date"), scalar(Builtin.DATE))
        assertEquals(
            ScalarSchema("string", pattern = JsonSchemaTypes.TIME_PATTERN),
            scalar(Builtin.TIME),
        )
        assertEquals(ScalarSchema("string", format = "date-time"), scalar(Builtin.INSTANT))
        assertEquals(ScalarSchema("string", format = "duration"), scalar(Builtin.DURATION))
        assertEquals(emptyList(), lossy)
    }

    @Test
    fun `bounds narrow integers and floats`() {
        assertEquals(
            ScalarSchema("integer", minimum = BigDecimal.ZERO, maximum = BigDecimal("2147483647")),
            scalar(Builtin.INT32, Refinements(min = BigDecimal.ZERO)),
        )
        assertEquals(
            ScalarSchema("number", minimum = BigDecimal("0.5"), maximum = BigDecimal("1.5")),
            scalar(Builtin.FLOAT64, Refinements(min = BigDecimal("0.5"), max = BigDecimal("1.5"))),
        )
    }

    @Test
    fun `strings carry lengths and patterns unchanged`() {
        assertEquals(
            ScalarSchema("string", pattern = "^[^@]+@[^@]+$", minLength = 3, maxLength = 254),
            scalar(
                Builtin.STRING,
                Refinements(min = BigDecimal(3), max = BigDecimal(254), pattern = "^[^@]+@[^@]+$"),
            ),
        )
    }

    @Test
    fun `a pattern with a construct ecma lacks is dropped and reported`() {
        assertEquals(
            ScalarSchema("string", maxLength = 9),
            scalar(Builtin.STRING, Refinements(max = BigDecimal(9), pattern = "a*+b")),
        )
        assertEquals(
            listOf("pattern uses *+, which JSON Schema (ECMA-262) cannot express; dropped"),
            lossy,
        )
    }

    @Test
    fun `decimals are strings with a precision and scale pattern`() {
        assertEquals("^-?[0-9]{1,15}(\\.[0-9]{1,4})?$", JsonSchemaTypes.decimalPattern(19, 4))
        assertEquals("^-?[0-9]{1,5}$", JsonSchemaTypes.decimalPattern(5, 0))
        assertEquals(
            ScalarSchema("string", pattern = "^-?[0-9]{1,15}(\\.[0-9]{1,4})?$"),
            scalar(Builtin.DECIMAL, Refinements(precision = 19, scale = 4)),
        )
    }

    @Test
    fun `decimal bounds are dropped and reported`() {
        assertEquals(
            ScalarSchema("string", pattern = "^-?[0-9]{1,15}(\\.[0-9]{1,4})?$"),
            scalar(Builtin.DECIMAL, Refinements(min = BigDecimal.ZERO, precision = 19, scale = 4)),
        )
        assertEquals(
            listOf("min/max on a decimal has no JSON Schema representation on a string; dropped"),
            lossy,
        )
    }

    @Test
    fun `bytes bounds become base64 lengths and max is reported as approximate`() {
        assertEquals(4, JsonSchemaTypes.base64Length(1))
        assertEquals(4, JsonSchemaTypes.base64Length(3))
        assertEquals(8, JsonSchemaTypes.base64Length(4))
        assertEquals(
            ScalarSchema("string", contentEncoding = "base64", minLength = 4, maxLength = 8),
            scalar(Builtin.BYTES, Refinements(min = BigDecimal(1), max = BigDecimal(6))),
        )
        assertEquals(listOf("max on bytes is approximated as a base64 length of 8"), lossy)
        lossy.clear()
        scalar(Builtin.BYTES, Refinements(min = BigDecimal(1)))
        assertEquals(emptyList(), lossy)
    }

    @Test
    fun `defaults take the json form of the field type`() {
        val name: (EnumRef) -> String = { it.value.uppercase() }
        assertEquals(
            JsonNumber("3"),
            JsonSchemaTypes.defaultValue(IntValue(3), Builtin.INT32, name),
        )
        assertEquals(
            JsonNumber("1.5"),
            JsonSchemaTypes.defaultValue(RealValue(BigDecimal("1.5")), Builtin.FLOAT64, name),
        )
        assertEquals(
            JsonString("1.5000"),
            JsonSchemaTypes.defaultValue(RealValue(BigDecimal("1.5000")), Builtin.DECIMAL, name),
        )
        assertEquals(
            JsonString("0"),
            JsonSchemaTypes.defaultValue(IntValue(0), Builtin.DECIMAL, name),
        )
        assertEquals(
            JsonString("x"),
            JsonSchemaTypes.defaultValue(StringValue("x"), Builtin.STRING, name),
        )
        assertEquals(
            JsonBool(true),
            JsonSchemaTypes.defaultValue(BoolValue(true), Builtin.BOOL, name),
        )
        assertEquals(
            JsonString("PENDING"),
            JsonSchemaTypes.defaultValue(
                EnumRef(QualifiedName("s", listOf("S")), "pending"),
                null,
                name,
            ),
        )
    }
}
