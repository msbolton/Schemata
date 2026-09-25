package io.schemata.target

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type

/** The type as a user would write it, shared by every target's diagnostics and lossy notes. */
object TypeText {
    /** The type as a user would write it: `string(max = 254)?`, `list<Line>(min = 1)`. */
    fun of(type: Type, nullable: Boolean = false): String {
        val core =
            when (type) {
                is Scalar ->
                    type.builtin.typeName +
                        args(type.refinements, decimal = type.builtin == Builtin.DECIMAL)
                is ListOf ->
                    "list<${of(type.element, type.nullableElement)}>" + args(type.refinements)
                is MapOf ->
                    "map<${of(type.key)}, ${of(type.value, type.nullableValue)}>" +
                        args(type.refinements)
                is Ref -> type.target.simpleName
            }
        return if (nullable) "$core?" else core
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
