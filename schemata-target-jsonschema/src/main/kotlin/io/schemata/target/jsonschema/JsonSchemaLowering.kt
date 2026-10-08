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
import io.schemata.core.ir.storedFields
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
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
    private val codes =
        LoweringCodes(
            lossy = JsonSchemaCodes.LOSSY,
            nameCollision = JsonSchemaCodes.NAME_COLLISION,
            invalidOverride = JsonSchemaCodes.INVALID_OVERRIDE,
            idCollision = JsonSchemaCodes.ID_COLLISION,
        )

    fun lower(schema: Schema): Lowered<JsonSchemaModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val ids = LinkedHashMap<String, String>()
        schema.namespaces.forEach { ns ->
            val override = ns.annotations.string("jsonschema", "id")
            if (override != null && !JsonSchemaNames.isAbsoluteUri(override)) {
                diagnostics +=
                    Diagnostic(
                        codes.invalidOverride,
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
                        codes.idCollision,
                        "namespaces ${group.joinToString(" and ") { it.name }} both lower to \$id '${ids.getValue(group.first().name)}'",
                        group[1].span,
                        help = "set `@jsonschema(id = \"…\")` on one of them",
                    )
            }
        val names = SchemaNames(schema, codes, diagnostics)
        val documents =
            schema.namespaces.map { ns ->
                DocumentLowering(
                        schema,
                        names,
                        ns,
                        codes,
                        diagnostics,
                        refs = { target ->
                            val key = names.defsKey(target)
                            if (target.namespace == ns.name) "#/\$defs/$key"
                            else "${ids.getValue(target.namespace)}#/\$defs/$key"
                        },
                        keys = names::defsKey,
                    )
                    .lower(JsonSchemaNames.pathOf(ns), ids.getValue(ns.name), ns.name)
            }
        return Lowered(JsonSchemaModel(documents), diagnostics)
    }
}

/**
 * The codes a document lowering reports under, so a target that files JSON Schemas in its own
 * documents reports under its own catalog.
 */
data class LoweringCodes(
    val lossy: DiagnosticCode,
    val nameCollision: DiagnosticCode,
    val invalidOverride: DiagnosticCode,
    val idCollision: DiagnosticCode,
)

/**
 * Names shared by every document: a declaration's `$defs` key and the validated `@jsonschema(name)`
 * overrides of declarations and enum values, each checked once.
 */
class SchemaNames(
    private val schema: Schema,
    codes: LoweringCodes,
    diagnostics: MutableList<Diagnostic>,
) {
    val overrides =
        OverrideNames(
            "jsonschema",
            codes.invalidOverride,
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

    /** The final names of [qn]'s enclosing declarations and its own, outermost first. */
    fun path(qn: QualifiedName): List<String> =
        schema.declarationPath(qn).map { overrides.nameOverride(it) ?: it.name }

    /** `Order.Line`, each segment its valid override when it has one. */
    fun defsKey(qn: QualifiedName): String = JsonSchemaNames.defsKey(path(qn))

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
 * One namespace's declarations as JSON Schemas. Every declaration, nested ones included, becomes a
 * def filed under [keys]; a reference to a declaration points at [refs]. Each declaration and each
 * field is lowered at most once per instance, so lowering one again, or a field again in a partial
 * record or as a field schema, reports nothing twice.
 */
class DocumentLowering(
    private val schema: Schema,
    private val names: SchemaNames,
    private val namespace: Namespace,
    private val codes: LoweringCodes,
    private val diagnostics: MutableList<Diagnostic>,
    /** Where a reference to a declaration points, such as `#/$defs/<key>`. */
    private val refs: (QualifiedName) -> String,
    /** The key a declaration is filed under, such as its [SchemaNames.defsKey]. */
    private val keys: (QualifiedName) -> String,
) {
    /**
     * Who owns each JSON name: `"def:<key>"` for a def, `"property:<declaring scope>/<name>"` for a
     * record's property, `"tag:<declaring scope>/<tag>"` for a union member,
     * `"value:<key>/<string>"` for an enum value, scoped so two records may share a property name.
     * A declaring scope carries the namespace: one lowering may span namespaces, and `a.Money {
     * amount }` and `b.Money { amount }` are two scopes.
     */
    private val claims =
        NameClaims(
            codes.nameCollision,
            "rename one of them, or set `@jsonschema(name = \"…\")` on one",
            diagnostics,
        )

    private val defs = HashMap<QualifiedName, JsonDef>()
    private val properties = HashMap<Pair<QualifiedName, String>, Property>()

    /** Every declaration of the namespace as one document at [path]. */
    fun lower(path: String, id: String, title: String): JsonSchemaDocument =
        JsonSchemaDocument(path, id, title, lower(namespace.declarations))

    /**
     * [decls] in order, each followed by its nested declarations; a declaration reached twice, such
     * as a nested one listed beside its parent, appears once, where it is first reached.
     */
    fun lower(decls: List<TypeDecl>): List<JsonDef> {
        val emitted = mutableSetOf<QualifiedName>()
        return decls.flatMap { defs(it, emitted) }
    }

    /** [decl] and its nested declarations, leaving out those in [emitted]. */
    private fun defs(decl: TypeDecl, emitted: MutableSet<QualifiedName>): List<JsonDef> {
        val own = if (emitted.add(decl.qualifiedName)) listOf(def(decl)) else emptyList()
        return own + decl.nested.flatMap { defs(it, emitted) }
    }

    private fun def(decl: TypeDecl): JsonDef =
        defs.getOrPut(decl.qualifiedName) {
            val key = keys(decl.qualifiedName)
            claims.claim(
                key = "def:$key",
                holder = "${decl.kindWord} '${decl.name}'",
                span = decl.nameSpan,
                display = key,
                kind = "\$defs key",
            )
            val own =
                when (decl) {
                    // a back-reference is virtual: no property carries it
                    is RecordType -> record(decl, decl.storedFields)
                    is EnumType -> enum(decl)
                    is UnionType -> union(decl)
                }
            JsonDef(key, own)
        }

    /**
     * An object schema over [fields], a subset of [record]'s, for a request body whose path took
     * the rest; [where] names that use in a precondition failure.
     */
    fun partialRecord(record: RecordType, fields: List<Field>, where: String): ObjectSchema {
        require(fields.all { it in record.fields }) {
            "$where: every field of a partial record must belong to ${record.qualifiedName}"
        }
        return record(record, fields)
    }

    /** [field]'s schema with its description, default, and deprecation applied, as in [record]. */
    fun fieldSchema(record: RecordType, field: Field): JsonSchema = property(record, field).schema

    private fun record(record: RecordType, fields: List<Field>): ObjectSchema =
        ObjectSchema(
            fields.map { property(record, it) },
            closed = !record.annotations.flag("jsonschema", "open"),
            common = Common(description = record.doc, deprecated = record.annotations.deprecated),
        )

    private fun enum(enum: EnumType): EnumSchema {
        val key = keys(enum.qualifiedName)
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

    private fun union(union: UnionType): TaggedUnionSchema {
        val path = names.path(union.qualifiedName)
        return TaggedUnionSchema(
            union.members.map { unionMember(union, it, path) },
            Common(description = union.doc, deprecated = union.annotations.deprecated),
        )
    }

    /**
     * A member's tag: a `Ref` takes the referenced declaration's override verbatim, else the lower
     * snake of its own name; a scalar takes its builtin's name. Claimed per union. The member's doc
     * becomes its schema's description.
     */
    private fun unionMember(union: UnionType, member: UnionMember, path: List<String>): Member {
        val tag = unionMemberStem(member.type, schema) { names.overrides.nameOverride(it) }
        val declName =
            (member.type as? Ref)?.let { schema.lookup(it.target).name }
                ?: (member.type as Scalar).builtin.typeName
        claims.claim(
            key = "tag:${scope(union.qualifiedName, path)}/$tag",
            holder = "union member '$declName'",
            span = member.span,
            display = tag,
            kind = "tag",
        )
        val schema =
            typeSchema(member.type, nullable = false, "union '${union.name}' member", member.span)
        return Member(tag, withCommon(schema, schema.common.copy(description = member.doc)))
    }

    /** [qn]'s claim scope: its namespace and its final [path]. */
    private fun scope(qn: QualifiedName, path: List<String>): String =
        "${qn.namespace}:${path.joinToString(".")}"

    private fun fieldWhere(record: RecordType, field: Field): String =
        "field '${record.name}.${field.name}'"

    /** [field]'s property, its name claimed in [record]'s scope; built once per field. */
    private fun property(record: RecordType, field: Field): Property =
        properties.getOrPut(record.qualifiedName to field.name) {
            val where = fieldWhere(record, field)
            val name =
                names.overrides.overrideName(field.annotations, where, field.nameSpan) ?: field.name
            claims.claim(
                key =
                    "property:${scope(record.qualifiedName, names.path(record.qualifiedName))}/$name",
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
            Property(
                name,
                withCommon(schema, common),
                required = !field.nullable && field.default == null,
            )
        }

    /**
     * [type] at one use, nullable or not; constructs JSON Schema cannot express report at [where].
     */
    fun typeSchema(type: Type, nullable: Boolean, where: String, span: Span): JsonSchema =
        when (type) {
            is Scalar ->
                JsonSchemaTypes.scalar(type, lossy(where, span)).let {
                    it.copy(common = it.common.copy(nullable = nullable))
                }
            is Ref -> RefSchema(refs(type.target), Common(nullable = nullable))
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

    /**
     * The `propertyNames` schema for a map's key: an integer key becomes a digit pattern, a refined
     * string key keeps its constraints, a plain string key needs nothing.
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
        diagnostics += Diagnostic(codes.lossy, "$where: $message", span, help = help)
    }

    private companion object {
        const val INTEGER_KEY = "^(0|-?[1-9][0-9]*)$"
    }
}

/** [schema] with [common] in place of its own. */
fun withCommon(schema: JsonSchema, common: Common): JsonSchema =
    when (schema) {
        is ObjectSchema -> schema.copy(common = common)
        is ArraySchema -> schema.copy(common = common)
        is MapSchema -> schema.copy(common = common)
        is ScalarSchema -> schema.copy(common = common)
        is EnumSchema -> schema.copy(common = common)
        is TaggedUnionSchema -> schema.copy(common = common)
        is RefSchema -> schema.copy(common = common)
    }
