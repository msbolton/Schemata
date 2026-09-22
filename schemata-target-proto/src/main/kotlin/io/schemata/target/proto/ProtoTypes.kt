package io.schemata.target.proto

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Type
import io.schemata.core.ir.Value

/** Text for lossy notes and the scalar keyword table. */
object ProtoTypes {
    /** The type as a user would write it: `string(max = 254)?`, `list<Line>(min = 1)`. */
    fun text(type: Type, nullable: Boolean = false): String {
        val core =
            when (type) {
                is Scalar ->
                    type.builtin.typeName +
                        args(type.refinements, decimal = type.builtin == Builtin.DECIMAL)
                is ListOf ->
                    "list<${text(type.element, type.nullableElement)}>" + args(type.refinements)
                is MapOf ->
                    "map<${text(type.key)}, ${text(type.value, type.nullableValue)}>" +
                        args(type.refinements)
                is Ref -> type.target.simpleName
            }
        return if (nullable) "$core?" else core
    }

    fun text(value: Value): String =
        when (value) {
            is IntValue -> value.value.toString()
            is RealValue -> value.value.toPlainString()
            is StringValue -> "\"" + value.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            is BoolValue -> value.value.toString()
            is EnumRef -> value.value
        }

    /** The proto keyword for a builtin that maps to a plain scalar; null for the others. */
    fun keyword(builtin: Builtin): String? =
        when (builtin) {
            Builtin.BOOL -> "bool"
            Builtin.INT32 -> "int32"
            Builtin.INT64 -> "int64"
            Builtin.FLOAT32 -> "float"
            Builtin.FLOAT64 -> "double"
            Builtin.STRING -> "string"
            Builtin.BYTES -> "bytes"
            Builtin.UUID,
            Builtin.DECIMAL,
            Builtin.DATE,
            Builtin.TIME,
            Builtin.INSTANT,
            Builtin.DURATION -> null
        }

    private fun args(r: Refinements, decimal: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (decimal && r.precision != null && r.scale != null)
            parts += listOf("${r.precision}", "${r.scale}")
        r.min?.let { parts += "min = ${it.toPlainString()}" }
        r.max?.let { parts += "max = ${it.toPlainString()}" }
        r.pattern?.let { parts += "pattern = \"$it\"" }
        return if (parts.isEmpty()) "" else parts.joinToString(", ", "(", ")")
    }
}
