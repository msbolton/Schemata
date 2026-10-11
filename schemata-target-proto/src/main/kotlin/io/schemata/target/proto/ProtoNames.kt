package io.schemata.target.proto

import io.schemata.target.Names
import io.schemata.target.ProtoFileOptions

/** The proto target's naming rules: enum-value prefixes and what proto can spell. */
object ProtoNames {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** `BankTransfer` → `bank_transfer`; a lowercase name is unchanged. */
    fun snakeCase(name: String): String = Names.snakeCase(name)

    /** `OrderStatus` → `ORDER_STATUS`. */
    fun upperSnake(name: String): String = snakeCase(name).uppercase()

    /** `STATUS_PENDING`: the value upper-cased behind the proto enum name [enumName]. */
    fun valueName(enumName: String, value: String): String =
        "${upperSnake(enumName)}_${value.uppercase()}"

    fun zeroValue(enumName: String): String = "${upperSnake(enumName)}_UNSPECIFIED"

    /**
     * The JSON name protoc derives for a field, the way protoc does it: each `_` is dropped and the
     * character after it upper-cased, so `placed_at` → `placedAt` and `a_1` → `a1`.
     */
    fun jsonName(fieldName: String): String = buildString {
        var upper = false
        for (c in fieldName) {
            when {
                c == '_' -> upper = true
                upper -> {
                    append(if (c in 'a'..'z') c.uppercaseChar() else c)
                    upper = false
                }
                else -> append(c)
            }
        }
    }

    /** What proto accepts as a name: a letter or underscore, then letters, digits, underscores. */
    fun isIdentifier(s: String): Boolean = identifier.matches(s)

    /** A package is dot-separated identifiers. */
    fun isPackage(s: String): Boolean = s.split('.').all { isIdentifier(it) }
}

/**
 * Whether [value] has the [shape] a file option needs. Segments of a PHP or Ruby name are
 * identifiers because the languages reject anything else; a Go path is only kept free of control
 * characters, since its text is the import path and protoc does not parse it.
 */
fun ProtoNames.fitsOption(shape: ProtoFileOptions.Shape, value: String): Boolean =
    when (shape) {
        ProtoFileOptions.Shape.DOTTED -> isPackage(value)
        ProtoFileOptions.Shape.BACKSLASHED -> value.split('\\').all { isIdentifier(it) }
        ProtoFileOptions.Shape.DOUBLE_COLON -> value.split("::").all { isIdentifier(it) }
        ProtoFileOptions.Shape.IDENTIFIER -> isIdentifier(value)
        ProtoFileOptions.Shape.DOTTED_OR_EMPTY -> value.isEmpty() || isPackage(value)
        ProtoFileOptions.Shape.BACKSLASHED_OR_EMPTY ->
            value.isEmpty() || value.split('\\').all { isIdentifier(it) }
        ProtoFileOptions.Shape.IDENTIFIER_OR_EMPTY -> value.isEmpty() || isIdentifier(value)
        ProtoFileOptions.Shape.NON_EMPTY -> value.isNotEmpty() && value.none { it < ' ' }
        ProtoFileOptions.Shape.BOOL -> true
    }
