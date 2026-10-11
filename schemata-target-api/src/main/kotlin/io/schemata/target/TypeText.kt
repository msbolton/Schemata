package io.schemata.target

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.lang.SchemataText

/** The type as a user would write it, shared by every target's diagnostics and lossy notes. */
object TypeText {
    /**
     * The type as a user would write it, with the options its bounds are written as: `string? { max
     * 254 }`, `Line[] { minItems 1 }`, `map<string, int32>`. A list's element options share the
     * list's block after its own; a list of lists keeps the outer `list<…>`, since a type takes one
     * `[]`, and so does a list of maps with a size bound of their own, which would otherwise share
     * the list's block with the list's; a map's key and value carry their own. A reference is
     * spelled by [name], the declaration's own simple name unless the caller emits it under
     * another, as a target's lossy note must for its reader to match the note to the type.
     */
    fun of(
        type: Type,
        nullable: Boolean = false,
        name: (QualifiedName) -> String = { it.simpleName },
    ): String {
        val (core, options) = slot(type, nullable, name)
        return core + block(options)
    }

    /** [type] as written, `?` included when [nullable], and the options of its slot. */
    private fun slot(
        type: Type,
        nullable: Boolean,
        name: (QualifiedName) -> String,
    ): Pair<String, List<String>> {
        val (core, options) =
            when (type) {
                is Scalar -> {
                    val r = type.refinements
                    val precision =
                        if (
                            type.builtin == Builtin.DECIMAL &&
                                r.precision != null &&
                                r.scale != null
                        )
                            "(${r.precision}, ${r.scale})"
                        else ""
                    type.builtin.typeName + precision to bounds(r, "min", "max")
                }
                is ListOf -> {
                    val own = bounds(type.refinements, "minItems", "maxItems")
                    val element = type.element
                    if (element is ListOf || (element is MapOf && element.refinements.hasBounds)) {
                        "list<${of(type.element, type.nullableElement, name)}>" to own
                    } else {
                        val (text, elementOptions) = slot(element, type.nullableElement, name)
                        "$text[]" to own + elementOptions
                    }
                }
                is MapOf ->
                    "map<${of(type.key)}, ${of(type.value, type.nullableValue, name)}>" to
                        bounds(type.refinements, "minItems", "maxItems")
                is Ref -> name(type.target) to emptyList()
            }
        return (if (nullable) "$core?" else core) to options
    }

    /** [r]'s bounds as options, its `min` and `max` named [min] and [max]. */
    private fun bounds(r: Refinements, min: String, max: String): List<String> = buildList {
        r.min?.let { add("$min ${it.toPlainString()}") }
        r.max?.let { add("$max ${it.toPlainString()}") }
        r.pattern?.let { add("match ${SchemataText.pattern(it)}") }
    }

    private fun block(options: List<String>): String =
        if (options.isEmpty()) "" else " { ${options.joinToString(", ")} }"
}
