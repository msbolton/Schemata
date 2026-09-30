package io.schemata.target.jsonschema

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Value
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.json.JsonString
import io.schemata.target.json.JsonValue
import java.math.BigDecimal

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

        /** A default's JSON value; a decimal default is scaled to the field's scale. */
        fun defaultValue(value: Value, scalar: Scalar?): JsonValue {
            val builtin = scalar?.builtin
            if (builtin == Builtin.DECIMAL) {
                val scale = scalar.refinements.scale
                val number =
                    when (value) {
                        is IntValue -> BigDecimal(value.value)
                        is RealValue -> value.value
                        else -> null
                    }
                if (number != null && scale != null)
                    return JsonString(number.setScale(scale).toPlainString())
            }
            return JsonSchemaTypes.defaultValue(value, builtin) { ref ->
                val enum = schema.lookup(ref.enum) as EnumType
                enumValueName(enum, enum.values.first { it.name == ref.value })
            }
        }
    }

    internal fun kindOf(decl: TypeDecl): String =
        when (decl) {
            is RecordType -> "record"
            is EnumType -> "enum"
            is UnionType -> "union"
        }

    /**
     * One namespace's document. Every declaration, nested ones included, becomes a `$defs` entry
     * keyed by its scoped name; references to another namespace's declarations point at that
     * document's `$id`.
     */
    internal class DocumentLowering(
        private val schema: Schema,
        private val names: SchemaNames,
        private val ids: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        /**
         * Who owns each JSON name: `"def:<key>"` for a `$defs` entry, `"property:<declaring
         * path>/<name>"` for a record's property, `"tag:<declaring path>/<tag>"` for a union
         * member, scoped so two records may share a property name.
         */
        private val claims = mutableMapOf<String, Pair<String, Span>>()

        fun lower(): JsonSchemaDocument =
            JsonSchemaDocument(
                JsonSchemaNames.pathOf(namespace),
                ids.getValue(namespace.name),
                namespace.name,
                namespace.declarations.flatMap { defs(it, emptyList()) },
            )

        /**
         * [path] is the enclosing declarations' final names; nested declarations follow their
         * parent.
         */
        private fun defs(decl: TypeDecl, path: List<String>): List<JsonDef> {
            val here = path + (names.nameOverride(decl) ?: decl.name)
            val key = names.defsKey(decl.qualifiedName)
            claim(
                "def",
                key,
                "${kindOf(decl)} '${decl.name}'",
                decl.nameSpan,
                displayKind = "\$defs key",
            )
            val own =
                when (decl) {
                    is RecordType -> record(decl, here)
                    is EnumType -> enum(decl)
                    is UnionType -> union(decl, here)
                }
            return listOf(JsonDef(key, own)) + decl.nested.flatMap { defs(it, here) }
        }

        private fun record(record: RecordType, path: List<String>): ObjectSchema =
            ObjectSchema(
                record.fields.map { property(record, it, path) },
                closed = !JsonSchemaNames.flag(record.annotations, "open"),
                common =
                    Common(
                        description = record.doc,
                        deprecated = JsonSchemaNames.deprecated(record.annotations),
                    ),
            )

        private fun enum(enum: EnumType): EnumSchema =
            EnumSchema(
                enum.values.map { EnumEntry(names.enumValueName(enum, it), it.doc) },
                Common(
                    description = enum.doc,
                    deprecated = JsonSchemaNames.deprecated(enum.annotations),
                ),
            )

        private fun union(union: UnionType, path: List<String>): TaggedUnionSchema =
            TaggedUnionSchema(
                union.members.map { unionMember(union, it, path) },
                Common(
                    description = union.doc,
                    deprecated = JsonSchemaNames.deprecated(union.annotations),
                ),
            )

        /**
         * A member's tag: a `Ref` takes the referenced declaration's override, else the lower snake
         * of its own name; a scalar takes its builtin's name. Claimed per union.
         */
        private fun unionMember(union: UnionType, member: UnionMember, path: List<String>): Member {
            val (tag, declName) =
                when (val t = member.type) {
                    is Ref -> {
                        val target = schema.lookup(t.target)
                        (names.nameOverride(target)?.let(JsonSchemaNames::tag)
                            ?: JsonSchemaNames.tag(target.name)) to target.name
                    }
                    is Scalar -> t.builtin.typeName to t.builtin.typeName
                    is ListOf,
                    is MapOf -> error("union member cannot be a collection")
                }
            claim(
                "tag",
                "${path.joinToString(".")}/$tag",
                "union member '$declName'",
                member.span,
                displayName = tag,
            )
            return Member(
                tag,
                typeSchema(
                    member.type,
                    nullable = false,
                    "union '${union.name}' member",
                    member.span,
                ),
                member.doc,
            )
        }

        private fun fieldWhere(record: RecordType, field: Field): String =
            "field '${record.name}.${field.name}'"

        private fun property(record: RecordType, field: Field, path: List<String>): Property {
            val where = fieldWhere(record, field)
            val name = names.overrideName(field.annotations, where, field.nameSpan) ?: field.name
            claim(
                "property",
                "${path.joinToString(".")}/$name",
                where,
                field.nameSpan,
                displayName = name,
            )
            val schema = typeSchema(field.type, field.nullable, where, field.span)
            val common =
                schema.common.copy(
                    description = field.doc,
                    default = field.default?.let { names.defaultValue(it, field.type as? Scalar) },
                    deprecated = JsonSchemaNames.deprecated(field.annotations),
                )
            return Property(
                name,
                withCommon(schema, common),
                required = !field.nullable && field.default == null,
            )
        }

        /** [type] at one use, nullable or not; collections are lowered in a later step. */
        internal fun typeSchema(
            type: Type,
            nullable: Boolean,
            where: String,
            span: Span,
        ): JsonSchema =
            when (type) {
                is Scalar ->
                    JsonSchemaTypes.scalar(type) { message, help ->
                            diagnostics +=
                                Diagnostic(
                                    JsonSchemaCodes.LOSSY,
                                    "$where: $message",
                                    span,
                                    help = help,
                                )
                        }
                        .let { it.copy(common = it.common.copy(nullable = nullable)) }
                is Ref -> RefSchema(refUri(type.target), Common(nullable = nullable))
                is ListOf,
                is MapOf -> error("collections are lowered in a later step")
            }

        /** `#/$defs/<key>` in this document, `<id>#/$defs/<key>` in another. */
        private fun refUri(target: QualifiedName): String {
            val key = names.defsKey(target)
            return if (target.namespace == namespace.name) "#/\$defs/$key"
            else "${ids.getValue(target.namespace)}#/\$defs/$key"
        }

        private fun claim(
            kind: String,
            key: String,
            holder: String,
            span: Span,
            displayName: String = key,
            displayKind: String = kind,
        ) {
            val fullKey = "$kind:$key"
            val previous = claims[fullKey]
            if (previous == null) {
                claims[fullKey] = holder to span
            } else {
                diagnostics +=
                    Diagnostic(
                        JsonSchemaCodes.NAME_COLLISION,
                        "$holder lowers to $displayKind '$displayName', already used by ${previous.first} " +
                            "(${previous.second.file}:${previous.second.startLine})",
                        span,
                        help = "rename one of them, or set `@jsonschema(name = \"…\")` on one",
                    )
            }
        }
    }

    /** [schema] with [common] in place of its own. */
    internal fun withCommon(schema: JsonSchema, common: Common): JsonSchema =
        when (schema) {
            is ObjectSchema -> schema.copy(common = common)
            is ArraySchema -> schema.copy(common = common)
            is MapSchema -> schema.copy(common = common)
            is ScalarSchema -> schema.copy(common = common)
            is EnumSchema -> schema.copy(common = common)
            is TaggedUnionSchema -> schema.copy(common = common)
            is RefSchema -> schema.copy(common = common)
        }
}
