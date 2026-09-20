package io.schemata.core.ir

/** The whole compilation unit after analysis. Targets consume this and nothing upstream of it. */
data class Schema(val namespace: String, val records: List<RecordType>)

data class RecordType(val name: String, val fields: List<Field>)

/**
 * [ordinal] is the field's stable identity (spec §5). In this phase it is always declaration order;
 * explicit `#n` syntax arrives in Project 02 and will set it instead.
 */
data class Field(val ordinal: Int, val name: String, val type: Type, val nullable: Boolean)

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
