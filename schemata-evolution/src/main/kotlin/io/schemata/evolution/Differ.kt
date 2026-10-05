package io.schemata.evolution

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Operation
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Service
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Span

/**
 * Compares two analysed schemas into a target-neutral list of [Change]s: declarations are matched
 * by qualified name, members by ordinal. Changes are listed in NEW's namespace and declaration
 * order, then OLD-only removals; members within a declaration follow NEW's source order, then the
 * members only OLD has. A namespace's services follow its declarations and are matched the same
 * way: services by qualified name, operations by ordinal, each operation's own changes in NEW's
 * order, then removed operations, then the service's reservations, annotations, and doc.
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
                declarations(o, n, out)
                services(o, n, out)
            }
        }
        old.namespaces
            .filter { it.name !in newNs }
            .forEach { out += NamespaceRemoved(it.name, it.span) }
        return out
    }

    private fun declarations(old: Namespace, new: Namespace, out: MutableList<Change>) {
        val oldDecls =
            old.declarations.flatMap { it.selfAndNested() }.associateBy { it.qualifiedName }
        val newDecls =
            new.declarations.flatMap { it.selfAndNested() }.associateBy { it.qualifiedName }
        newDecls.values.forEach { n ->
            val o = oldDecls[n.qualifiedName]
            when {
                o == null -> out += DeclarationAdded(path(n), n.nameSpan, n)
                o::class != n::class -> out += DeclarationKindChanged(path(n), n.nameSpan, o, n)
                else -> declaration(o, n, out)
            }
        }
        oldDecls.values
            .filter { it.qualifiedName !in newDecls }
            .forEach { out += DeclarationRemoved(path(it), it.nameSpan, it) }
    }

    private fun declaration(old: TypeDecl, new: TypeDecl, out: MutableList<Change>) {
        when (new) {
            is RecordType -> fields(new, old as RecordType, out)
            is EnumType -> values(new, old as EnumType, out)
            is UnionType -> members(new, old as UnionType, out)
        }
        reserved(old, new, out)
        annotations(
            path(new),
            new.nameSpan,
            DeclarationOwner(old),
            DeclarationOwner(new),
            old.annotations,
            new.annotations,
            out,
        )
        if (old.doc != new.doc) out += DocChanged(path(new), new.nameSpan, DeclarationOwner(new))
    }

    private fun fields(new: RecordType, old: RecordType, out: MutableList<Change>) {
        val oldFields = old.fields.associateBy { it.ordinal }
        val newFields = new.fields.associateBy { it.ordinal }
        new.fields.forEach { nf ->
            val of = oldFields[nf.ordinal]
            if (of == null) out += FieldAdded(memberPath(new, nf.name), nf.nameSpan, new, nf)
            else field(old, new, of, nf, out)
        }
        old.fields
            .filter { it.ordinal !in newFields }
            .forEach { of -> out += FieldRemoved(memberPath(old, of.name), of.nameSpan, old, of) }
    }

    private fun field(
        oldRecord: RecordType,
        record: RecordType,
        old: Field,
        new: Field,
        out: MutableList<Change>,
    ) {
        val p = memberPath(record, new.name)
        if (old.name != new.name) out += FieldRenamed(p, new.nameSpan, record, old, new)
        when {
            typeChanged(old.type, new.type) ->
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
            old.annotations,
            new.annotations,
            out,
        )
        if (old.doc != new.doc) out += DocChanged(p, new.nameSpan, FieldOwner(record, new))
    }

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

    /** Members have no name, so they path by ordinal: `s.Payment.#2`. */
    private fun members(new: UnionType, old: UnionType, out: MutableList<Change>) {
        val oldMembers = old.members.associateBy { it.ordinal }
        val newMembers = new.members.associateBy { it.ordinal }
        new.members.forEach { nm ->
            val om = oldMembers[nm.ordinal]
            val p = memberPath(new, "#${nm.ordinal}")
            if (om == null) {
                out += UnionMemberAdded(p, nm.span, new, nm)
            } else {
                if (om.type != nm.type) out += UnionMemberTypeChanged(p, nm.span, new, om, nm)
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

    private fun service(old: Service, new: Service, out: MutableList<Change>) {
        val oldOps = old.operations.associateBy { it.ordinal }
        val newOrdinals = new.operations.map { it.ordinal }.toSet()
        new.operations.forEach { nop ->
            val oop = oldOps[nop.ordinal]
            if (oop == null) out += OperationAdded(operationPath(new, nop), nop.nameSpan, new, nop)
            else operation(old, new, oop, nop, out)
        }
        old.operations
            .filter { it.ordinal !in newOrdinals }
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
     * shows.
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

    internal fun reservedOf(decl: TypeDecl): Reserved? =
        when (decl) {
            is RecordType -> decl.reserved
            is EnumType -> decl.reserved
            is UnionType -> null
        }

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
