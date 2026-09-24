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
 * The SQL target's naming rules: derived names, `@sql` overrides, quoting, and Postgres's
 * 63-character limit.
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

    /**
     * [reserve] holds back room for a caller-added suffix, such as a constraint's own qualifier.
     */
    fun truncated(name: String, reserve: Int = 0): Boolean = name.length + reserve > MAX_IDENTIFIER

    /**
     * Over the limit: the first `55 - [reserve]` characters, `_`, and 7 hex of the full name's
     * SHA-1.
     */
    fun identifier(name: String, reserve: Int = 0): String {
        if (!truncated(name, reserve)) return name
        val digest = MessageDigest.getInstance("SHA-1").digest(name.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(7)
        return name.take(KEPT - reserve) + "_" + hash
    }

    fun override(annotations: Annotations, key: String): String? =
        (annotations["sql"][key] as? AnnotationValue.Str)?.value

    fun schemaOf(namespace: Namespace): String =
        override(namespace.annotations, "schema") ?: namespace.name.substringAfterLast('.')

    fun tableOf(record: RecordType): String =
        override(record.annotations, "table") ?: snakeCase(record.name)

    fun columnOf(field: Field): String = override(field.annotations, "column") ?: field.name
}
