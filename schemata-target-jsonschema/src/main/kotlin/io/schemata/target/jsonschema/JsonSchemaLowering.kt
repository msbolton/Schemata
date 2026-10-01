package io.schemata.target.jsonschema

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
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
import io.schemata.core.ir.declarationPath
import io.schemata.core.ir.kindWord
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.NameClaims
import io.schemata.target.OverrideNames
import io.schemata.target.collidingNamespaces
import io.schemata.target.deprecated
import io.schemata.target.flag
import io.schemata.target.json.JsonString
import io.schemata.target.json.JsonValue
import io.schemata.target.string
import io.schemata.target.unionMemberStem
import java.math.BigDecimal

/** Lowers the IR to a [JsonSchemaModel]; every decision and every lossy report lives here. */
object JsonSchemaLowering {
    private const val INTEGER_KEY = "^(0|-?[1-9][0-9]*)$"

    fun lower(schema: Schema): Lowered<JsonSchemaModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val ids = LinkedHashMap<String, String>()
        schema.namespaces.forEach { ns ->
            val override = ns.annotations.string("jsonschema", "id")
            if (override != null && !JsonSchemaNames.isAbsoluteUri(override)) {
                diagnostics +=
                    Diagnostic(
                        JsonSchemaCodes.INVALID_OVERRIDE,
                        "namespace '${ns.name}': @jsonschema(id = \"$override\") is not an absolute URI",
                        ns.span,
                        help =
                            "use an absolute URI without a fragment, such as `urn:example:orders`",
                    )
                ids[ns.name] = "urn:schemata:${ns.name}"
            } else {
                ids[ns.name] = JsonSchemaNames.idOf(ns)
            }
        }
        collidingNamespaces(schema.namespaces) { ids.getValue(it.name) }
            .forEach { group ->
                diagnostics +=
                    Diagnostic(
                        JsonSchemaCodes.ID_COLLISION,
                        "namespaces ${group.joinToString(" and ") { it.name }} both lower to \$id '${ids.getValue(group.first().name)}'",
                        group[1].span,
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
    internal class SchemaNames(private val schema: Schema, diagnostics: MutableList<Diagnostic>) {
        val overrides =
            OverrideNames(
                "jsonschema",
                JsonSchemaCodes.INVALID_OVERRIDE,
                diagnostics,
                { value ->
                    if (value.isEmpty()) "is empty"
                    else
                        JsonSchemaNames.reservedIn(value)?.let {
                            "contains '${shown(it)}', which a \$ref cannot carry"
                        }
                },
            ) { tail ->
                if (tail == "is empty") "give the name at least one character"
                else "leave out whitespace and the characters / ~ # % ? \" \\"
            }

        /** `Order.Line`, each segment its valid override when it has one. */
        fun defsKey(qn: QualifiedName): String =
            JsonSchemaNames.defsKey(
                schema.declarationPath(qn).map { overrides.nameOverride(it) ?: it.name }
            )

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
                overrides.enumValueName(enum, enum.values.first { it.name == ref.value })
            }
        }
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
         * member, `"value:<defs key>/<string>"` for an enum value, scoped so two records may share
         * a property name.
         */
        private val claims =
            NameClaims(
                JsonSchemaCodes.NAME_COLLISION,
                "rename one of them, or set `@jsonschema(name = \"…\")` on one",
                diagnostics,
            )

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
            val here = path + (names.overrides.nameOverride(decl) ?: decl.name)
            val key = names.defsKey(decl.qualifiedName)
            claims.claim(
                key = "def:$key",
                holder = "${decl.kindWord} '${decl.name}'",
                span = decl.nameSpan,
                display = key,
                kind = "\$defs key",
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
                closed = !record.annotations.flag("jsonschema", "open"),
                common =
                    Common(description = record.doc, deprecated = record.annotations.deprecated),
            )

        private fun enum(enum: EnumType): EnumSchema {
            val key = names.defsKey(enum.qualifiedName)
            return EnumSchema(
                enum.values.map { value ->
                    val string = names.overrides.enumValueName(enum, value)
                    claims.claim(
                        key = "value:$key/$string",
                        holder = "enum value '${enum.name}.${value.name}'",
                        span = value.nameSpan,
                        display = string,
                        kind = "enum value",
                    )
                    EnumEntry(string, value.doc)
                },
                Common(description = enum.doc, deprecated = enum.annotations.deprecated),
            )
        }

        private fun union(union: UnionType, path: List<String>): TaggedUnionSchema =
            TaggedUnionSchema(
                union.members.map { unionMember(union, it, path) },
                Common(description = union.doc, deprecated = union.annotations.deprecated),
            )

        /**
         * A member's tag: a `Ref` takes the referenced declaration's override verbatim, else the
         * lower snake of its own name; a scalar takes its builtin's name. Claimed per union. The
         * member's doc becomes its schema's description.
         */
        private fun unionMember(union: UnionType, member: UnionMember, path: List<String>): Member {
            val tag = unionMemberStem(member.type, schema) { names.overrides.nameOverride(it) }
            val declName =
                (member.type as? Ref)?.let { schema.lookup(it.target).name }
                    ?: (member.type as Scalar).builtin.typeName
            claims.claim(
                key = "tag:${path.joinToString(".")}/$tag",
                holder = "union member '$declName'",
                span = member.span,
                display = tag,
                kind = "tag",
            )
            val schema =
                typeSchema(
                    member.type,
                    nullable = false,
                    "union '${union.name}' member",
                    member.span,
                )
            return Member(tag, withCommon(schema, schema.common.copy(description = member.doc)))
        }

        private fun fieldWhere(record: RecordType, field: Field): String =
            "field '${record.name}.${field.name}'"

        private fun property(record: RecordType, field: Field, path: List<String>): Property {
            val where = fieldWhere(record, field)
            val name =
                names.overrides.overrideName(field.annotations, where, field.nameSpan) ?: field.name
            claims.claim(
                key = "property:${path.joinToString(".")}/$name",
                holder = where,
                span = field.nameSpan,
                display = name,
                kind = "property",
            )
            val schema = typeSchema(field.type, field.nullable, where, field.span)
            val common =
                schema.common.copy(
                    description = field.doc,
                    default = field.default?.let { names.defaultValue(it, field.type as? Scalar) },
                    deprecated = field.annotations.deprecated,
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
                    JsonSchemaTypes.scalar(type, lossy(where, span)).let {
                        it.copy(common = it.common.copy(nullable = nullable))
                    }
                is Ref -> RefSchema(refUri(type.target), Common(nullable = nullable))
                is ListOf ->
                    ArraySchema(
                        typeSchema(type.element, type.nullableElement, where, span),
                        minItems = type.refinements.min?.toLong(),
                        maxItems = type.refinements.max?.toLong(),
                        common = Common(nullable = nullable),
                    )
                is MapOf ->
                    MapSchema(
                        typeSchema(type.value, type.nullableValue, where, span),
                        keys = mapKeys(type.key as Scalar, where, span),
                        minProperties = type.refinements.min?.toLong(),
                        maxProperties = type.refinements.max?.toLong(),
                        common = Common(nullable = nullable),
                    )
            }

        /** `#/$defs/<key>` in this document, `<id>#/$defs/<key>` in another. */
        private fun refUri(target: QualifiedName): String {
            val key = names.defsKey(target)
            return if (target.namespace == namespace.name) "#/\$defs/$key"
            else "${ids.getValue(target.namespace)}#/\$defs/$key"
        }

        /**
         * The `propertyNames` schema for a map's key: an integer key becomes a digit pattern, a
         * refined string key keeps its constraints, a plain string key needs nothing.
         */
        private fun mapKeys(key: Scalar, where: String, span: Span): ScalarSchema? =
            when (key.builtin) {
                Builtin.INT32,
                Builtin.INT64 -> ScalarSchema("string", pattern = INTEGER_KEY)
                else -> {
                    val s = JsonSchemaTypes.scalar(key, lossy(where, span))
                    if (s == ScalarSchema("string")) null else s
                }
            }

        /** Reports a construct JSON Schema cannot express at [where]. */
        private fun lossy(where: String, span: Span): (String, String) -> Unit = { message, help ->
            diagnostics += Diagnostic(JsonSchemaCodes.LOSSY, "$where: $message", span, help = help)
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
