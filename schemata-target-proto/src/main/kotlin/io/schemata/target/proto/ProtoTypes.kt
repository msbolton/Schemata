package io.schemata.target.proto

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Type
import io.schemata.core.ir.Value
import io.schemata.target.TypeText

/** Text for lossy notes and the scalar keyword table. */
object ProtoTypes {
    /** The type as a user would write it: `string(max = 254)?`, `list<Line>(min = 1)`. */
    fun text(type: Type, nullable: Boolean = false): String = TypeText.of(type, nullable)

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
}
