package io.schemata.target.jsonschema

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered

/** Lowers the IR to a [JsonSchemaModel]; every decision and every lossy report lives here. */
object JsonSchemaLowering {
    fun lower(schema: Schema): Lowered<JsonSchemaModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val ids = LinkedHashMap<String, String>()
        schema.namespaces.forEach { ns ->
            val override = JsonSchemaNames.override(ns.annotations, "id")
            if (override != null && !JsonSchemaNames.isAbsoluteUri(override)) {
                diagnostics +=
                    Diagnostic(
                        JsonSchemaCodes.INVALID_OVERRIDE,
                        "namespace '${ns.name}': @jsonschema(id = \"$override\") is not an absolute URI",
                        ns.span,
                        help = "use an absolute URI such as `urn:example:orders`",
                    )
                ids[ns.name] = "urn:schemata:${ns.name}"
            } else {
                ids[ns.name] = JsonSchemaNames.idOf(ns)
            }
        }
        ids.entries
            .groupBy({ it.value }, { it.key })
            .values
            .filter { it.size > 1 }
            .forEach { names ->
                val second = schema.namespaces.first { it.name == names[1] }
                diagnostics +=
                    Diagnostic(
                        JsonSchemaCodes.ID_COLLISION,
                        "namespaces ${names.joinToString(" and ")} both lower to \$id '${ids.getValue(names.first())}'",
                        second.span,
                        help = "set `@jsonschema(id = \"…\")` on one of them",
                    )
            }
        val names = SchemaNames(schema, diagnostics)
        val documents =
            schema.namespaces.map { DocumentLowering(schema, names, ids, it, diagnostics).lower() }
        return Lowered(JsonSchemaModel(documents), diagnostics)
    }

    /**
     * Names shared by every document: a declaration's `$defs` key and the validated
     * `@jsonschema(name)` overrides of declarations and enum values, each checked once.
     */
    internal class SchemaNames(
        private val schema: Schema,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        private val declOverrides = mutableMapOf<QualifiedName, String?>()
        private val valueOverrides = mutableMapOf<Pair<QualifiedName, String>, String?>()
        private val keys = mutableMapOf<QualifiedName, String>()

        /** `Order.Line`, each segment its valid override when it has one. */
        fun defsKey(qn: QualifiedName): String =
            keys.getOrPut(qn) {
                JsonSchemaNames.defsKey(
                    qn.path.indices.map { i ->
                        val decl = schema.lookup(QualifiedName(qn.namespace, qn.path.take(i + 1)))
                        nameOverride(decl) ?: decl.name
                    }
                )
            }

        fun nameOverride(decl: TypeDecl): String? =
            declOverrides.memo(decl.qualifiedName) {
                overrideName(decl.annotations, "${kindOf(decl)} '${decl.name}'", decl.nameSpan)
            }

        fun enumValueName(enum: EnumType, value: EnumValue): String =
            valueOverrides.memo(enum.qualifiedName to value.name) {
                overrideName(
                    value.annotations,
                    "enum value '${enum.name}.${value.name}'",
                    value.nameSpan,
                )
            } ?: value.name

        private fun <K> MutableMap<K, String?>.memo(key: K, compute: () -> String?): String? {
            if (key !in this) this[key] = compute()
            return getValue(key)
        }

        /** The `@jsonschema(name)` value, or null (with a diagnostic) when it is empty. */
        fun overrideName(annotations: Annotations, where: String, span: Span): String? {
            val value = JsonSchemaNames.override(annotations, "name") ?: return null
            if (value.isNotEmpty()) return value
            diagnostics +=
                Diagnostic(
                    JsonSchemaCodes.INVALID_OVERRIDE,
                    "$where: @jsonschema(name = \"\") is empty",
                    span,
                    help = "give the name at least one character",
                )
            return null
        }
    }

    internal fun kindOf(decl: TypeDecl): String =
        when (decl) {
            is RecordType -> "record"
            is EnumType -> "enum"
            is UnionType -> "union"
        }

    /** One namespace's document; declarations are added by later steps. */
    internal class DocumentLowering(
        private val schema: Schema,
        private val names: SchemaNames,
        private val ids: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        fun lower(): JsonSchemaDocument =
            JsonSchemaDocument(
                JsonSchemaNames.pathOf(namespace),
                ids.getValue(namespace.name),
                namespace.name,
                emptyList(),
            )
    }
}
