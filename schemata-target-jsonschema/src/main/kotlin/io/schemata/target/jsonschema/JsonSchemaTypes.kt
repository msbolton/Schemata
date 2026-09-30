package io.schemata.target.jsonschema

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Value
import io.schemata.target.json.JsonBool
import io.schemata.target.json.JsonNumber
import io.schemata.target.json.JsonString
import io.schemata.target.json.JsonValue
import java.math.BigDecimal

/** Builtin mappings, refinements as constraints, and default values. */
object JsonSchemaTypes {
    const val UUID_PATTERN =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

    /** RFC 3339's `time` needs a zone offset; a Schemata time has none, so a pattern stands in. */
    const val TIME_PATTERN = "^[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?$"

    private val INT32_MIN = BigDecimal("-2147483648")
    private val INT32_MAX = BigDecimal("2147483647")
    private val INT64_MIN = BigDecimal("-9223372036854775808")
    private val INT64_MAX = BigDecimal("9223372036854775807")

    /**
     * [scalar] with its refinements as constraints. [lossy] is called once per refinement that is
     * dropped or approximated, with the message tail (after the location) and the help text.
     */
    fun scalar(scalar: Scalar, lossy: (String, String) -> Unit): ScalarSchema {
        val r = scalar.refinements
        return when (scalar.builtin) {
            Builtin.BOOL -> ScalarSchema("boolean")
            Builtin.INT32 ->
                ScalarSchema("integer", minimum = r.min ?: INT32_MIN, maximum = r.max ?: INT32_MAX)
            Builtin.INT64 ->
                ScalarSchema("integer", minimum = r.min ?: INT64_MIN, maximum = r.max ?: INT64_MAX)
            Builtin.FLOAT32,
            Builtin.FLOAT64 -> ScalarSchema("number", minimum = r.min, maximum = r.max)
            Builtin.DECIMAL -> {
                if (r.min != null || r.max != null) {
                    lossy(
                        "min/max on a decimal has no JSON Schema representation on a string; dropped",
                        "enforce the bound in application code",
                    )
                }
                ScalarSchema("string", pattern = decimalPattern(r.precision ?: 38, r.scale ?: 0))
            }
            Builtin.STRING -> {
                var pattern = r.pattern
                if (pattern != null) {
                    val bad = EcmaPattern.firstUnsupported(pattern)
                    if (bad != null) {
                        lossy(
                            "pattern uses $bad, which JSON Schema (ECMA-262) cannot express; dropped",
                            "rewrite the pattern without $bad, or enforce it in application code",
                        )
                        pattern = null
                    }
                }
                ScalarSchema(
                    "string",
                    pattern = pattern,
                    minLength = r.min?.toLong(),
                    maxLength = r.max?.toLong(),
                )
            }
            Builtin.BYTES -> {
                val max = r.max?.toLong()?.let(::base64Length)
                if (max != null) {
                    lossy(
                        "max on bytes is approximated as a base64 length of $max",
                        "enforce the exact byte length in application code",
                    )
                }
                ScalarSchema(
                    "string",
                    contentEncoding = "base64",
                    minLength = r.min?.toLong()?.let(::base64Length),
                    maxLength = max,
                )
            }
            Builtin.UUID -> ScalarSchema("string", format = "uuid", pattern = UUID_PATTERN)
            Builtin.DATE -> ScalarSchema("string", format = "date")
            Builtin.TIME -> ScalarSchema("string", pattern = TIME_PATTERN)
            Builtin.INSTANT -> ScalarSchema("string", format = "date-time")
            Builtin.DURATION -> ScalarSchema("string", format = "duration")
        }
    }

    /**
     * `^-?[0-9]{1,p-s}(\.[0-9]{1,s})?$`, without the fraction group when [scale] is 0; when
     * [precision] equals [scale] the integer part can only be `0`.
     */
    fun decimalPattern(precision: Int, scale: Int): String {
        val whole = if (precision == scale) "^-?0" else "^-?[0-9]{1,${precision - scale}}"
        return if (scale == 0) "$whole$" else "$whole(\\.[0-9]{1,$scale})?$"
    }

    /** The base64 text length of [bytes] bytes: `4 * ceil(bytes / 3)`. */
    fun base64Length(bytes: Long): Long = 4 * ((bytes + 2) / 3)

    /**
     * A default's JSON form: numbers as numbers except on a decimal, where the string form keeps
     * exactness; an enum default is its value name in the schema, via [enumValueName].
     */
    fun defaultValue(
        value: Value,
        builtin: Builtin?,
        enumValueName: (EnumRef) -> String,
    ): JsonValue =
        when (value) {
            is IntValue ->
                if (builtin == Builtin.DECIMAL) JsonString(value.value.toString())
                else JsonNumber(value.value.toString())
            is RealValue ->
                if (builtin == Builtin.DECIMAL) JsonString(value.value.toPlainString())
                else JsonNumber(value.value.toPlainString())
            is StringValue -> JsonString(value.value)
            is BoolValue -> JsonBool(value.value)
            is EnumRef -> JsonString(enumValueName(value))
        }
}
