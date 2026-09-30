package io.schemata.target.jsonschema

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Namespace
import io.schemata.target.Names

/** The target's naming rules: paths, ids, `$defs` keys, union tags, and `@jsonschema` readers. */
object JsonSchemaNames {
    private val scheme = Regex("[A-Za-z][A-Za-z0-9+.\\-]*:.+")

    fun pathOf(namespace: Namespace): String = namespace.name.replace('.', '/') + ".schema.json"

    fun idOf(namespace: Namespace): String =
        override(namespace.annotations, "id") ?: "urn:schemata:${namespace.name}"

    /** `["Order", "Line"]` → `Order.Line`; each segment is already its override when it has one. */
    fun defsKey(path: List<String>): String = path.joinToString(".")

    /** Union member tags: `BankTransfer` → `bank_transfer`. */
    fun tag(declName: String): String = Names.snakeCase(declName)

    fun isAbsoluteUri(s: String): Boolean = scheme.matches(s)

    fun override(annotations: Annotations, key: String): String? =
        (annotations["jsonschema"][key] as? AnnotationValue.Str)?.value

    fun flag(annotations: Annotations, key: String): Boolean =
        annotations["jsonschema"][key] is AnnotationValue.Flag

    fun deprecated(annotations: Annotations): Boolean = "deprecated" in annotations[""]
}
