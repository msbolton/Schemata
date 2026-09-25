package io.schemata.target.sql

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Value
import io.schemata.target.Names
import java.security.MessageDigest

/**
 * The SQL target's naming rules: derived names, `@sql` overrides, quoting, and Postgres's 63-byte
 * identifier limit.
 */
object Naming {
    private const val MAX_IDENTIFIER = 63
    private const val KEPT = 55

    fun snakeCase(upperCamel: String): String = Names.snakeCase(upperCamel)

    fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    fun literal(text: String): String = "'" + text.replace("'", "''") + "'"

    fun literal(value: Value): String =
        when (value) {
            is IntValue -> value.value.toString()
            is RealValue -> value.value.toPlainString()
            is BoolValue -> value.value.toString()
            is StringValue -> literal(value.value)
            is EnumRef -> literal(value.value)
        }

    /** Postgres measures identifiers in bytes, so a multi-byte name reaches the limit sooner. */
    fun truncated(name: String): Boolean = name.toByteArray(Charsets.UTF_8).size > MAX_IDENTIFIER

    /**
     * Over the limit: the longest run of whole code points that fits in 55 bytes, `_`, and 7 hex of
     * the full name's SHA-1.
     */
    fun identifier(name: String): String {
        if (!truncated(name)) return name
        val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }.take(7)
        return prefix(name, KEPT) + "_" + hash
    }

    private fun prefix(name: String, bytes: Int): String {
        var end = 0
        var size = 0
        while (end < name.length) {
            val codePoint = name.codePointAt(end)
            val width = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (size + width > bytes) break
            size += width
            end += Character.charCount(codePoint)
        }
        return name.substring(0, end)
    }

    fun override(annotations: Annotations, key: String): String? =
        (annotations["sql"][key] as? AnnotationValue.Str)?.value

    fun schemaOf(namespace: Namespace): String =
        override(namespace.annotations, "schema") ?: namespace.name.substringAfterLast('.')

    fun tableOf(record: RecordType): String =
        override(record.annotations, "table") ?: snakeCase(record.name)

    fun columnOf(field: Field): String = override(field.annotations, "column") ?: field.name
}
