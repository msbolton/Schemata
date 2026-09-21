package io.schemata.core.ir

import io.schemata.lang.Span

/** The whole compilation after analysis. Namespaces are sorted by name. */
data class Schema(val namespaces: List<Namespace>)

/**
 * [span] is the `namespace` declaration of the first file (in sorted-path order) that declares it.
 */
data class Namespace(val name: String, val records: List<RecordType>, val span: Span)

data class RecordType(val name: String, val fields: List<Field>, val span: Span)

/**
 * [ordinal] is the field's stable identity. In this phase it is always declaration order; explicit
 * `#n` syntax (SCH-22) will set it instead.
 */
data class Field(
    val ordinal: Int,
    val name: String,
    val type: Type,
    val nullable: Boolean,
    val span: Span,
)

sealed interface Type

enum class Builtin(val typeName: String) : Type {
    BOOL("bool"),
    INT32("int32"),
    STRING("string"),
    UUID("uuid");

    companion object {
        fun byName(name: String): Builtin? = entries.firstOrNull { it.typeName == name }
    }
}
