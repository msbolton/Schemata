package io.schemata.evolution

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.Operation
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Relation
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Service
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.declaresKey
import io.schemata.core.ir.effectiveErrors
import io.schemata.core.ir.selfAndNested
import io.schemata.core.ir.successStatus
import io.schemata.lang.Span

/**
 * Compares two analysed schemas into a target-neutral list of [Change]s: declarations are matched
 * by qualified name, members by ordinal. Changes are listed in NEW's namespace and declaration
 * order, then OLD-only removals; members within a declaration follow NEW's source order, then the
 * members only OLD has. A namespace's services follow its declarations and are matched the same
 * way: services by qualified name, operations by ordinal and then, for the ones left over on both
 * sides, by name, each operation's own changes in NEW's order, then removed operations, then the
 * service's reservations, annotations, and doc.
 */
object Differ {
    fun diff(old: Schema, new: Schema): List<Change> {
        val out = mutableListOf<Change>()
        val oldNs = old.namespaces.associateBy { it.name }
        val newNs = new.namespaces.associateBy { it.name }
        new.namespaces.forEach { n ->
            val o = oldNs[n.name]
            if (o == null) out += NamespaceAdded(n.name, n.span)
            else {
                annotations(
                    n.name,
                    n.span,
                    NamespaceOwner(o),
                    NamespaceOwner(n),
                    o.annotations,
                    n.annotations,
                    out,
                )
                declarations(old, new, o, n, out)
                services(o, n, out)
            }
        }
        old.namespaces
            .filter { it.name !in newNs }
            .forEach { out += NamespaceRemoved(it.name, it.span) }
        return out
    }

    private fun declarations(
        oldSchema: Schema,
        newSchema: Schema,
        old: Namespace,
        new: Namespace,
        out: MutableList<Change>,
    ) {
        val oldDecls =
            old.declarations.flatMap { it.selfAndNested() }.associateBy { it.qualifiedName }
        val newDecls =
            new.declarations.flatMap { it.selfAndNested() }.associateBy { it.qualifiedName }
        newDecls.values.forEach { n ->
            val o = oldDecls[n.qualifiedName]
            when {
                o == null -> out += DeclarationAdded(path(n), n.nameSpan, n)
                o::class != n::class -> out += DeclarationKindChanged(path(n), n.nameSpan, o, n)
                else -> declaration(oldSchema, newSchema, o, n, out)
            }
        }
        oldDecls.values
            .filter { it.qualifiedName !in newDecls }
            .forEach { out += DeclarationRemoved(path(it), it.nameSpan, it) }
    }

    private fun declaration(
        oldSchema: Schema,
        newSchema: Schema,
        old: TypeDecl,
        new: TypeDecl,
        out: MutableList<Change>,
    ) {
        when (new) {
            is RecordType -> fields(oldSchema, newSchema, new, old as RecordType, out)
            is EnumType -> values(new, old as EnumType, out)
            is UnionType -> members(oldSchema, newSchema, new, old as UnionType, out)
        }
        reserved(old, new, out)
        annotations(
            path(new),
            new.nameSpan,
            DeclarationOwner(old),
            DeclarationOwner(new),
            withKeyFacts(old),
            withKeyFacts(new),
            out,
        )
        if (old.doc != new.doc) out += DocChanged(path(new), new.nameSpan, DeclarationOwner(new))
    }

    private fun fields(
        oldSchema: Schema,
        newSchema: Schema,
        new: RecordType,
        old: RecordType,
        out: MutableList<Change>,
    ) {
        val oldFields = old.fields.associateBy { it.ordinal }
        val newFields = new.fields.associateBy { it.ordinal }
        new.fields.forEach { nf ->
            val of = oldFields[nf.ordinal]
            if (of == null) out += FieldAdded(memberPath(new, nf.name), nf.nameSpan, new, nf)
            else field(oldSchema, newSchema, old, new, of, nf, out)
        }
        old.fields
            .filter { it.ordinal !in newFields }
            .forEach { of -> out += FieldRemoved(memberPath(old, of.name), of.nameSpan, old, of) }
    }

    /**
     * A field that keeps its ordinal but turns into a back-reference (or back) is a stored field
     * removed (or added): no target emits a back-reference, so every target loses (or gains) the
     * column, element, or property, whatever its name and type say. Nothing else about it is
     * compared then.
     */
    private fun field(
        oldSchema: Schema,
        newSchema: Schema,
        oldRecord: RecordType,
        record: RecordType,
        old: Field,
        new: Field,
        out: MutableList<Change>,
    ) {
        if (old.virtual != new.virtual) {
            out +=
                if (new.virtual)
                    FieldRemoved(memberPath(oldRecord, old.name), old.nameSpan, oldRecord, old)
                else FieldAdded(memberPath(record, new.name), new.nameSpan, record, new)
            return
        }
        val p = memberPath(record, new.name)
        if (old.name != new.name) out += FieldRenamed(p, new.nameSpan, record, old, new)
        when {
            typeChanged(old.type, new.type) ||
                embedFlipped(oldSchema, newSchema, old.type, new.type) ->
                out += FieldTypeChanged(p, new.span, record, old, new)
            refinementsChanged(old.type, new.type) ->
                out +=
                    FieldRefinementChanged(
                        p,
                        new.span,
                        record,
                        old,
                        new,
                        refinementsTightened(old.type, new.type),
                    )
        }
        if (old.nullable != new.nullable)
            out += FieldNullabilityChanged(p, new.span, record, old, new)
        if (old.default != new.default) out += FieldDefaultChanged(p, new.span, record, old, new)
        annotations(
            p,
            new.nameSpan,
            FieldOwner(oldRecord, old),
            FieldOwner(record, new),
            withKeyFacts(oldSchema, old),
            withKeyFacts(newSchema, new),
            out,
        )
        if (old.doc != new.doc) out += DocChanged(p, new.nameSpan, FieldOwner(record, new))
    }

    /**
     * A reference flipped between `{ embed }` and by key changes the shape of every output that
     * writes the referenced model, but only when that model has a key to write: an unkeyed model is
     * always written inline. [old] and [new] are a field's or a union member's two types.
     */
    private fun embedFlipped(oldSchema: Schema, newSchema: Schema, old: Type, new: Type): Boolean {
        val before = relationOf(old)
        val after = relationOf(new)
        if (before == null || after == null || before.embed == after.embed) return false
        val target = referenced(new) ?: return false
        return keyed(oldSchema, target) || keyed(newSchema, target)
    }

    private fun referenced(type: Type): QualifiedName? =
        when (type) {
            is Ref -> type.target
            is ListOf -> (type.element as? Ref)?.target
            else -> null
        }

    private fun keyed(schema: Schema, name: QualifiedName): Boolean =
        (schema.lookupOrNull(name) as? RecordType)?.declaresKey() == true

    private fun values(new: EnumType, old: EnumType, out: MutableList<Change>) {
        val oldValues = old.values.associateBy { it.ordinal }
        val newValues = new.values.associateBy { it.ordinal }
        new.values.forEach { nv ->
            val ov = oldValues[nv.ordinal]
            val p = memberPath(new, nv.name)
            if (ov == null) {
                out += EnumValueAdded(p, nv.nameSpan, new, nv)
            } else {
                if (ov.name != nv.name) out += EnumValueRenamed(p, nv.nameSpan, new, ov, nv)
                annotations(
                    p,
                    nv.nameSpan,
                    EnumValueOwner(old, ov),
                    EnumValueOwner(new, nv),
                    ov.annotations,
                    nv.annotations,
                    out,
                )
                if (ov.doc != nv.doc) out += DocChanged(p, nv.nameSpan, EnumValueOwner(new, nv))
            }
        }
        old.values
            .filter { it.ordinal !in newValues }
            .forEach { ov ->
                out += EnumValueRemoved(memberPath(old, ov.name), ov.nameSpan, old, ov)
            }
    }

    /**
     * Members have no name, so they path by ordinal: `s.Payment.#2`. A member's type is compared as
     * a field's is: without its relation, with `{ embed }` added or removed counting only when the
     * member's model has a key. Its bounds stay part of its type, since a member has no refinement
     * change of its own.
     */
    private fun members(
        oldSchema: Schema,
        newSchema: Schema,
        new: UnionType,
        old: UnionType,
        out: MutableList<Change>,
    ) {
        val oldMembers = old.members.associateBy { it.ordinal }
        val newMembers = new.members.associateBy { it.ordinal }
        new.members.forEach { nm ->
            val om = oldMembers[nm.ordinal]
            val p = memberPath(new, "#${nm.ordinal}")
            if (om == null) {
                out += UnionMemberAdded(p, nm.span, new, nm)
            } else {
                val retyped =
                    withoutRelations(om.type) != withoutRelations(nm.type) ||
                        embedFlipped(oldSchema, newSchema, om.type, nm.type)
                if (retyped) out += UnionMemberTypeChanged(p, nm.span, new, om, nm)
                if (om.doc != nm.doc) out += DocChanged(p, nm.span, UnionMemberOwner(new, nm))
            }
        }
        old.members
            .filter { it.ordinal !in newMembers }
            .forEach { om ->
                out += UnionMemberRemoved(memberPath(old, "#${om.ordinal}"), om.span, old, om)
            }
    }

    private fun reserved(old: TypeDecl, new: TypeDecl, out: MutableList<Change>) {
        val oldReserved = reservedOf(old) ?: return
        val newReserved = reservedOf(new) ?: return
        if (oldReserved != newReserved)
            out +=
                ReservedChanged(
                    path(new),
                    new.nameSpan,
                    oldReserved,
                    newReserved,
                    DeclarationOwner(new),
                )
    }

    private fun services(old: Namespace, new: Namespace, out: MutableList<Change>) {
        val oldServices = old.services.associateBy { it.qualifiedName }
        val newNames = new.services.map { it.qualifiedName }.toSet()
        new.services.forEach { n ->
            val o = oldServices[n.qualifiedName]
            if (o == null) out += ServiceAdded(path(n), n.nameSpan, n) else service(o, n, out)
        }
        old.services
            .filter { it.qualifiedName !in newNames }
            .forEach { out += ServiceRemoved(path(it), it.nameSpan, it) }
    }

    /**
     * Operations match by ordinal, then the ones left unmatched on both sides by name: a client
     * reaches an operation by its rpc path or operationId, which carry the name, so an operation
     * renumbered under its own name is moved and compared like any matched one, not removed and
     * added. A name matched by ordinal elsewhere never pairs again.
     */
    private fun service(old: Service, new: Service, out: MutableList<Change>) {
        val oldOps = old.operations.associateBy { it.ordinal }
        val newOrdinals = new.operations.map { it.ordinal }.toSet()
        val unmatchedNew =
            new.operations.filter { it.ordinal !in oldOps.keys }.map { it.name }.toSet()
        val movedFrom =
            old.operations
                .filter { it.ordinal !in newOrdinals && it.name in unmatchedNew }
                .associateBy { it.name }
        new.operations.forEach { nop ->
            val oop = oldOps[nop.ordinal]
            val from = if (oop == null) movedFrom[nop.name] else null
            when {
                oop != null -> operation(old, new, oop, nop, out)
                from != null -> {
                    out += OperationMoved(operationPath(new, nop), nop.nameSpan, new, from, nop)
                    operation(old, new, from, nop, out)
                }
                else -> out += OperationAdded(operationPath(new, nop), nop.nameSpan, new, nop)
            }
        }
        old.operations
            .filter { it.ordinal !in newOrdinals && it.name !in movedFrom.keys }
            .forEach { oop ->
                out += OperationRemoved(operationPath(old, oop), oop.nameSpan, old, oop)
            }
        if (old.reserved != new.reserved)
            out +=
                ReservedChanged(
                    path(new),
                    new.nameSpan,
                    old.reserved,
                    new.reserved,
                    ServiceOwner(new),
                )
        annotations(
            path(new),
            new.nameSpan,
            ServiceOwner(old),
            ServiceOwner(new),
            old.annotations,
            new.annotations,
            out,
        )
        if (old.doc != new.doc) out += DocChanged(path(new), new.nameSpan, ServiceOwner(new))
    }

    /**
     * Payloads and bindings are plain data, so any difference in type, streaming, verb, or path
     * shows. The success code is compared when either side declares one; otherwise it follows the
     * response, whose change already says so.
     */
    private fun operation(
        oldService: Service,
        service: Service,
        old: Operation,
        new: Operation,
        out: MutableList<Change>,
    ) {
        val p = operationPath(service, new)
        if (old.name != new.name) out += OperationRenamed(p, new.nameSpan, service, old, new)
        if (old.request != new.request)
            out += OperationRequestChanged(p, new.span, service, new, old.request, new.request)
        if (old.response != new.response)
            out += OperationResponseChanged(p, new.span, service, new, old.response, new.response)
        if (old.binding != new.binding)
            out += OperationBindingChanged(p, new.span, service, new, old.binding, new.binding)
        if (old.status != null || new.status != null) {
            val from = old.successStatus()
            val to = new.successStatus()
            if (from != to) out += OperationStatusChanged(p, new.span, service, new, from, to)
        }
        errors(p, oldService, service, old, new, out)
        annotations(
            p,
            new.nameSpan,
            OperationOwner(oldService, old),
            OperationOwner(service, new),
            old.annotations,
            new.annotations,
            out,
        )
        if (old.doc != new.doc) out += DocChanged(p, new.nameSpan, OperationOwner(service, new))
    }

    /**
     * Error responses compare by code on each side's effective list, the service's clause merged
     * with the operation's: a code only NEW lists is added, a code only OLD lists is removed, and a
     * code both list whose body differs is changed, in NEW's order and then the removals. A
     * service's clause therefore shows on each of its operations.
     */
    private fun errors(
        path: String,
        oldService: Service,
        service: Service,
        old: Operation,
        new: Operation,
        out: MutableList<Change>,
    ) {
        val before = effectiveErrors(oldService, old)
        val after = effectiveErrors(service, new)
        val oldByCode = before.associateBy { it.code }
        after.forEach { response ->
            val previous = oldByCode[response.code]
            when {
                response.code !in oldByCode ->
                    out += OperationErrorAdded(path, new.span, service, new, response)
                previous?.body != response.body ->
                    out +=
                        OperationErrorChanged(
                            path,
                            new.span,
                            service,
                            new,
                            response.code,
                            previous?.body,
                            response.body,
                        )
            }
        }
        val newCodes = after.map { it.code }.toSet()
        before
            .filter { it.code !in newCodes }
            .forEach { out += OperationErrorRemoved(path, new.span, service, new, it) }
    }

    internal fun reservedOf(decl: TypeDecl): Reserved? =
        when (decl) {
            is RecordType -> decl.reserved
            is EnumType -> decl.reserved
            is UnionType -> null
        }

    /**
     * A field's annotations plus its `{ id }`, `{ unique }`, `{ index }`, and `{ embed }` facts,
     * which the SQL target lowers to its key, unique constraints, indexes, and an embedded record's
     * columns. They are compared as the `sql` keys `key`, `unique`, `index`, and `strategy`
     * (`embed`, unless the field names a strategy of its own), so the SQL rulebook judges a fact
     * added or removed as the DDL change it is and every other rulebook passes it as an annotation
     * it does not read. `{ embed }` is a fact only on a reference to a model that has a key in
     * [schema]: a keyless model is copied either way, so the option changes no table. A reference's
     * `@relation(onDelete: …)` other than the default `restrict` is compared as
     * `@relation(onDelete)`. An `@sql(strategy: table)` that names the shape's own default is left
     * out ([withoutDefaultStrategy]).
     */
    private fun withKeyFacts(schema: Schema, field: Field): Annotations {
        val annotations = withoutDefaultStrategy(schema, field)
        val relation = relationOf(field.type)
        val target = referenced(field.type)
        val copiesKeyed = relation?.embed == true && target != null && keyed(schema, target)
        val facts = buildMap {
            if (field.key) put("key", AnnotationValue.Flag)
            if (field.unique) put("unique", AnnotationValue.Flag)
            if (field.index) put("index", AnnotationValue.Flag)
            if (copiesKeyed && "strategy" !in annotations["sql"])
                put("strategy", AnnotationValue.Name("embed"))
        }
        val withFacts = withSql(annotations, facts)
        val onDelete = relation?.onDelete?.takeIf { it != OnDelete.RESTRICT } ?: return withFacts
        return Annotations(
            withFacts.entries +
                ("relation" to mapOf("onDelete" to AnnotationValue.Name(onDelete.name.lowercase())))
        )
    }

    /**
     * [field]'s annotations without an `@sql(strategy: table)` that names the lowering its shape
     * takes anyway: a reference to a model keyed in [schema] is a foreign key, and a list of models
     * a child table, with or without it. On a scalar or enum list, a map, a keyless or `{ embed }`
     * reference, or a union, `table` changes the lowering or is refused, so it stays and is
     * compared. `json` is never a default.
     */
    private fun withoutDefaultStrategy(schema: Schema, field: Field): Annotations {
        val sql = field.annotations["sql"]
        if (sql["strategy"] != AnnotationValue.Name("table")) return field.annotations
        val tableAnyway =
            when (val type = field.type) {
                is Ref -> !type.relation.embed && keyed(schema, type.target)
                is ListOf ->
                    (type.element as? Ref)?.let { schema.lookupOrNull(it.target) is RecordType } ==
                        true
                else -> false
            }
        if (!tableAnyway) return field.annotations
        val rest = sql - "strategy"
        return Annotations(
            if (rest.isEmpty()) field.annotations.entries - "sql"
            else field.annotations.entries + ("sql" to rest)
        )
    }

    /** The relation a field's reference, or its list's element reference, carries. */
    private fun relationOf(type: Type): Relation? =
        when (type) {
            is Ref -> type.relation
            is ListOf -> (type.element as? Ref)?.relation
            else -> null
        }

    /**
     * A declaration's annotations plus a record's `@@id(a, b)`, as the `sql` key `key`, and each of
     * its `@@unique(a, b)` and `@@index(a, b)` as a flag under the `sql` key spelled as written, so
     * one constraint added or removed is one change and the SQL rulebook judges it.
     */
    private fun withKeyFacts(decl: TypeDecl): Annotations {
        val record = decl as? RecordType ?: return decl.annotations
        val facts = buildMap {
            if (record.compositeKey.isNotEmpty())
                put("key", AnnotationValue.Names(record.compositeKey))
            record.uniques.forEach { put(constraintKey("unique", it), AnnotationValue.Flag) }
            record.indexes.forEach { put(constraintKey("index", it), AnnotationValue.Flag) }
        }
        return withSql(decl.annotations, facts)
    }

    private fun withSql(
        annotations: Annotations,
        facts: Map<String, AnnotationValue>,
    ): Annotations =
        if (facts.isEmpty()) annotations
        else Annotations(annotations.entries + ("sql" to annotations["sql"] + facts))

    private fun annotations(
        path: String,
        span: Span,
        oldOwner: Owner,
        newOwner: Owner,
        old: Annotations,
        new: Annotations,
        out: MutableList<Change>,
    ) {
        (old.entries.keys + new.entries.keys).forEach { target ->
            val oldKeys = old.entries[target] ?: emptyMap()
            val newKeys = new.entries[target] ?: emptyMap()
            (oldKeys.keys + newKeys.keys).forEach { key ->
                val from = oldKeys[key]
                val to = newKeys[key]
                if (from == to) return@forEach
                if (target == "" && key == "deprecated" && (from == null) != (to == null)) {
                    out += DeprecationChanged(path, span, to != null, newOwner)
                } else {
                    out += AnnotationChanged(path, span, target, key, from, to, oldOwner, newOwner)
                }
            }
        }
    }

    private fun path(decl: TypeDecl): String {
        val qn = decl.qualifiedName
        return "${qn.namespace}.${qn.path.joinToString(".")}"
    }

    private fun memberPath(decl: TypeDecl, name: String) = "${path(decl)}.$name"

    private fun path(service: Service): String = service.qualifiedName.toString()

    private fun operationPath(service: Service, op: Operation) = "${path(service)}.${op.name}"
}
