package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Operation
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Service
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.declaresKey
import io.schemata.core.ir.selfAndNested
import io.schemata.core.ir.service
import io.schemata.core.ir.services
import io.schemata.target.Names
import io.schemata.target.ProtoPackages
import io.schemata.target.bool
import io.schemata.target.deprecated
import io.schemata.target.flag
import io.schemata.target.referencesByKey
import io.schemata.target.string

/**
 * Whether a removed member's ordinal, name, both, or neither ([UNRESERVED], free to be reused) are
 * still reserved in NEW.
 */
enum class ReservedStatus {
    BOTH,
    ORDINAL_ONLY,
    NAME_ONLY,
    UNRESERVED,
}

/**
 * Everything a [Rulebook] needs beyond the bare [Change]: what NEW still reserves, what OLD
 * deprecated, the name a target actually emits for a member, and the roles (`keyed`, `root`,
 * `open`, `hasTable`) a target's own lowering would assign a record.
 */
class ChangeContext(val old: Schema, val new: Schema) {
    /**
     * Which of a removed member's [ordinal] and [name] NEW's declaration or service at [declPath]
     * still reserves: both, one, or neither.
     */
    fun reservedInNew(declPath: QualifiedName, ordinal: Int, name: String): ReservedStatus {
        val reserved =
            new.lookupOrNull(declPath)?.let(Differ::reservedOf) ?: new.service(declPath)?.reserved
        val ordinalReserved = reserved != null && ordinal in reserved
        val nameReserved = reserved != null && name in reserved.names
        return when {
            ordinalReserved && nameReserved -> ReservedStatus.BOTH
            ordinalReserved -> ReservedStatus.ORDINAL_ONLY
            nameReserved -> ReservedStatus.NAME_ONLY
            else -> ReservedStatus.UNRESERVED
        }
    }

    /**
     * Whether the thing [change] removes, renames away from, or recasts was `@deprecated` on OLD's
     * side; false for a change with no single OLD-side element, such as an addition.
     */
    fun deprecatedInOld(change: Change): Boolean =
        when (change) {
            is FieldRemoved -> change.field.annotations.deprecated
            is FieldRenamed -> change.from.annotations.deprecated
            is FieldTypeChanged -> change.from.annotations.deprecated
            is FieldNullabilityChanged -> change.from.annotations.deprecated
            is FieldDefaultChanged -> change.from.annotations.deprecated
            is FieldRefinementChanged -> change.from.annotations.deprecated
            is EnumValueRemoved -> change.value.annotations.deprecated
            is EnumValueRenamed -> change.from.annotations.deprecated
            is DeclarationRemoved -> change.decl.annotations.deprecated
            is DeclarationKindChanged -> change.from.annotations.deprecated
            is DeprecationChanged -> !change.deprecated
            is ServiceRemoved -> change.service.annotations.deprecated
            is OperationRemoved -> change.operation.annotations.deprecated
            is OperationRenamed -> change.from.annotations.deprecated
            is OperationRequestChanged -> oldOperationDeprecated(change.service, change.operation)
            is OperationResponseChanged -> oldOperationDeprecated(change.service, change.operation)
            is OperationBindingChanged -> oldOperationDeprecated(change.service, change.operation)
            else -> false
        }

    /** [service] as OLD declares it, or [service] itself when OLD has no service by that name. */
    internal fun oldService(service: Service): Service =
        old.service(service.qualifiedName) ?: service

    private fun oldOperationDeprecated(service: Service, operation: Operation): Boolean =
        oldService(service)
            .operations
            ?.firstOrNull { it.ordinal == operation.ordinal }
            ?.annotations
            ?.deprecated == true

    /** The name [target] emits for [field]: its override on that side, else its declared name. */
    fun emittedFieldName(target: String, field: Field): String =
        field.annotations.string(target, overrideKey(target)) ?: field.name

    /** The name [target] emits for [value]: its override on that side, else its declared name. */
    fun emittedValueName(target: String, value: EnumValue): String =
        value.annotations.string(target, overrideKey(target)) ?: value.name

    /**
     * The name [target] emits for [owner] as it stands on one side. A declaration is its
     * `@<target>(name)` override, or for Postgres its `@sql(table)` override or snake-cased name; a
     * namespace is, for Postgres, its `@sql(schema)` override or the last segment of its name, and
     * its full name elsewhere; a union member has no name, so it is its ordinal. On OpenAPI a
     * service is its tag and an operation its `operationId`; on Protobuf a service is its valid
     * `@proto(name)` else its own name, and an operation its rpc name, its valid `@proto(name)`
     * else its name in UpperCamel; elsewhere both are their own names.
     */
    fun emittedName(target: String, owner: Owner): String =
        when (owner) {
            is NamespaceOwner ->
                if (target == "sql")
                    owner.namespace.annotations.string("sql", "schema")
                        ?: owner.namespace.name.substringAfterLast('.')
                else owner.namespace.name
            is DeclarationOwner ->
                when {
                    target == "sql" ->
                        owner.decl.annotations.string("sql", "table")
                            ?: Names.snakeCase(owner.decl.name)
                    // XSD names a record's global element in lower snake unless overridden,
                    // and that element is what old root documents address.
                    target == "xsd" && owner.decl is RecordType ->
                        owner.decl.annotations.string("xsd", "name")
                            ?: Names.snakeCase(owner.decl.name)
                    else -> owner.decl.annotations.string(target, "name") ?: owner.decl.name
                }
            is FieldOwner -> emittedFieldName(target, owner.field)
            is EnumValueOwner -> emittedValueName(target, owner.value)
            is UnionMemberOwner -> "#${owner.member.ordinal}"
            is ServiceOwner ->
                when (target) {
                    "openapi" -> tagName(owner.service)
                    "proto" -> protoName(owner.service.annotations) ?: owner.service.name
                    else -> owner.service.name
                }
            is OperationOwner ->
                when (target) {
                    "openapi" -> operationId(owner.service, owner.operation)
                    "proto" ->
                        protoName(owner.operation.annotations)
                            ?: Names.upperCamel(owner.operation.name)
                    else -> owner.operation.name
                }
        }

    /**
     * A `@proto(name)` override, unless it is not an identifier (the target reports it and falls
     * back).
     */
    private fun protoName(annotations: Annotations): String? =
        annotations.string("proto", "name")?.takeIf { PROTO_IDENTIFIER.matches(it) }

    /**
     * An OpenAPI tag: the service's `@openapi(name)`, unless that is not a valid tag (the target
     * reports it and falls back), else the service's own name.
     */
    private fun tagName(service: Service): String =
        service.annotations.string("openapi", "name")?.takeIf { OPENAPI_NAME.matches(it) }
            ?: service.name

    /**
     * An OpenAPI `operationId`: the operation's valid `@openapi(name)`, which replaces the whole
     * id, else `<tag>_<operation>` with the service's tag.
     */
    private fun operationId(service: Service, operation: Operation): String =
        operation.annotations.string("openapi", "name")?.takeIf { OPENAPI_NAME.matches(it) }
            ?: "${tagName(service)}_${operation.name}"

    /**
     * The Protobuf file units of [side]'s schema, which decide the package of every service's
     * method path. Computed once per side: a diff judges each removed rpc on its own, and the
     * assignment walks the whole schema.
     */
    internal fun protoPackages(side: Side): ProtoPackages =
        if (side == Side.OLD) oldProtoPackages else newProtoPackages

    private val oldProtoPackages by lazy { ProtoPackages.of(old.referencesByKey()) }
    private val newProtoPackages by lazy { ProtoPackages.of(new.referencesByKey()) }

    /**
     * Whether [decl] is one an OpenAPI document on [side] carries: reachable from some service's
     * request or response through field types, union members, and nesting.
     */
    fun reachableFromServices(side: Side, decl: QualifiedName): Boolean =
        decl in (if (side == Side.OLD) oldReachable else newReachable)

    private val oldReachable by lazy { reachable(old) }
    private val newReachable by lazy { reachable(new) }

    private fun reachable(schema: Schema): Set<QualifiedName> {
        val seen = mutableSetOf<QualifiedName>()
        fun visit(qn: QualifiedName) {
            if (!seen.add(qn)) return
            val decl = schema.lookupOrNull(qn) ?: return
            val types =
                when (decl) {
                    is RecordType -> decl.fields.map { it.type }
                    is UnionType -> decl.members.map { it.type }
                    is EnumType -> emptyList()
                }
            types.flatMap(::refsIn).forEach(::visit)
            decl.nested.forEach { visit(it.qualifiedName) }
        }
        schema
            .services()
            .flatMap { it.operations }
            .flatMap { listOfNotNull(it.request, it.response) }
            .forEach { visit(it.target) }
        return seen
    }

    private fun refsIn(type: Type): List<QualifiedName> =
        when (type) {
            is Ref -> listOf(type.target)
            is ListOf -> refsIn(type.element)
            is MapOf -> refsIn(type.key) + refsIn(type.value)
            else -> emptyList()
        }

    /** Every declaration, nested ones included, of the namespace named [namespace] on [side]. */
    fun declarationsOf(side: Side, namespace: String): List<TypeDecl> =
        schema(side)
            .namespaces
            .firstOrNull { it.name == namespace }
            ?.declarations
            .orEmpty()
            .flatMap { it.selfAndNested() }

    /**
     * `{ id }` on a field or `@@id(…)` on the record itself, Catalog's rule for a table-backed
     * record.
     */
    fun isKeyed(side: Side, record: QualifiedName): Boolean {
        return (schema(side).lookupOrNull(record) as? RecordType)?.declaresKey() == true
    }

    /**
     * The declarations that hold a stored reference to [record] by its key, NEW's in NEW's order
     * and then any only OLD has: a model's field typed as it, or a list of it, or a map to it, that
     * is neither a back-reference nor `{ embed }`, and a union's member standing for it unless that
     * member is `{ embed }`.
     */
    fun keyReferences(record: QualifiedName): List<QualifiedName> =
        (keyReferences(new, record) + keyReferences(old, record)).distinct()

    private fun keyReferences(schema: Schema, record: QualifiedName): List<QualifiedName> =
        schema.namespaces
            .flatMap { it.declarations.flatMap { d -> d.selfAndNested() } }
            .filter { decl ->
                when (decl) {
                    is RecordType ->
                        decl.fields.any { f -> !f.virtual && keyReferenceTo(f.type, record) }
                    is UnionType -> decl.members.any { keyReferenceTo(it.type, record) }
                    is EnumType -> false
                }
            }
            .map { it.qualifiedName }

    private fun keyReferenceTo(type: Type, record: QualifiedName): Boolean =
        when (type) {
            is Ref -> type.target == record && !type.relation.embed
            is ListOf -> keyReferenceTo(type.element, record)
            is MapOf -> keyReferenceTo(type.value, record)
            else -> false
        }

    /** A top-level record (not nested in another declaration) not opted out with `@xsd(root)`. */
    fun isRoot(side: Side, record: QualifiedName): Boolean {
        val decl = schema(side).lookupOrNull(record) as? RecordType ?: return false
        return record.path.size == 1 && decl.annotations.bool("xsd", "root") != false
    }

    fun isOpen(side: Side, record: QualifiedName): Boolean {
        val decl = schema(side).lookupOrNull(record) as? RecordType ?: return false
        return decl.annotations.flag("jsonschema", "open")
    }

    /**
     * [record] is backed by its own Postgres table: it is keyed directly, or it is the element type
     * of some `Model[]` field elsewhere on [side] whose `@sql(strategy: …)` is absent or `table`,
     * which gives that field's elements a child table of their own.
     */
    fun hasTable(side: Side, record: QualifiedName): Boolean =
        isKeyed(side, record) || isChildTable(side, record)

    private fun isChildTable(side: Side, record: QualifiedName): Boolean =
        schema(side)
            .namespaces
            .flatMap { it.declarations.flatMap { d -> d.selfAndNested() } }
            .filterIsInstance<RecordType>()
            .any { r -> r.fields.any { f -> listsTableOf(f, record) } }

    private fun listsTableOf(field: Field, record: QualifiedName): Boolean =
        listedRecord(field) == record

    /**
     * Whether [field], a field of a record on [side], is a `Model[]` whose elements live in a child
     * table of their own rather than in a JSON column.
     */
    internal fun hasChildTable(side: Side, field: Field): Boolean =
        listedRecord(field)?.let { schema(side).lookupOrNull(it) is RecordType } == true

    /** The element record of a `Model[]` field stored as a child table, else null. */
    private fun listedRecord(field: Field): QualifiedName? {
        val element = (field.type as? ListOf)?.element as? Ref ?: return null
        val strategy = (field.annotations["sql"]["strategy"] as? AnnotationValue.Name)?.value
        return element.target.takeIf { strategy == null || strategy == "table" }
    }

    private fun schema(side: Side): Schema = if (side == Side.OLD) old else new

    private fun overrideKey(target: String) = if (target == "sql") "column" else "name"

    private companion object {
        /**
         * What OpenAPI accepts as a tag or `operationId` override; keep in step with
         * `OpenApiLowering.OPERATION_ID`, which this module cannot depend on.
         */
        val OPENAPI_NAME = Regex("[A-Za-z0-9_.-]+")

        /**
         * What Protobuf accepts as a service or rpc name override; keep in step with
         * `ProtoNames.isIdentifier`, which this module cannot depend on.
         */
        val PROTO_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
