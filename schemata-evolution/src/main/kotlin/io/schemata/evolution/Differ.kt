package io.schemata.evolution

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested
import io.schemata.lang.Span
import java.math.BigDecimal

/**
 * Compares two analysed schemas into a target-neutral list of [Change]s: declarations are matched
 * by qualified name, members by ordinal. Changes are listed in NEW's namespace and declaration
 * order, then OLD-only removals; members within a declaration are listed in ordinal order.
 */
object Differ {
    fun diff(old: Schema, new: Schema): List<Change> {
        val out = mutableListOf<Change>()
        val oldNs = old.namespaces.associateBy { it.name }
        val newNs = new.namespaces.associateBy { it.name }
        new.namespaces.forEach { n ->
            val o = oldNs[n.name]
            if (o == null) out += NamespaceAdded(n.name, n.span) else declarations(o, n, out)
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
        annotations(path(new), new.nameSpan, old.annotations, new.annotations, out)
        if (old.doc != new.doc) out += DocChanged(path(new), new.nameSpan)
    }

    private fun fields(new: RecordType, old: RecordType, out: MutableList<Change>) {
        val oldFields = old.fields.associateBy { it.ordinal }
        val newFields = new.fields.associateBy { it.ordinal }
        new.fields.forEach { nf ->
            val of = oldFields[nf.ordinal]
            if (of == null) out += FieldAdded(memberPath(new, nf.name), nf.nameSpan, new, nf)
            else field(new, of, nf, out)
        }
        old.fields
            .filter { it.ordinal !in newFields }
            .forEach { of -> out += FieldRemoved(memberPath(old, of.name), of.nameSpan, old, of) }
    }

    private fun field(record: RecordType, old: Field, new: Field, out: MutableList<Change>) {
        val p = memberPath(record, new.name)
        if (old.name != new.name) out += FieldRenamed(p, new.nameSpan, record, old, new)
        when {
            typeChanged(old.type, new.type) ->
                out += FieldTypeChanged(p, new.span, record, old, new)
            refinementsOf(old.type) != refinementsOf(new.type) ->
                out +=
                    FieldRefinementChanged(
                        p,
                        new.span,
                        record,
                        old,
                        new,
                        tightened(refinementsOf(old.type), refinementsOf(new.type)),
                    )
        }
        if (old.nullable != new.nullable)
            out += FieldNullabilityChanged(p, new.span, record, old, new)
        if (old.default != new.default) out += FieldDefaultChanged(p, new.span, record, old, new)
        annotations(p, new.nameSpan, old.annotations, new.annotations, out)
        if (old.doc != new.doc) out += DocChanged(p, new.nameSpan)
    }

    private fun values(new: EnumType, old: EnumType, out: MutableList<Change>) {
        val oldValues = old.values.associateBy { it.ordinal }
        val newValues = new.values.associateBy { it.ordinal }
        new.values.forEach { nv ->
            val ov = oldValues[nv.ordinal]
            val p = memberPath(new, nv.name)
            when {
                ov == null -> out += EnumValueAdded(p, nv.nameSpan, new, nv)
                ov.name != nv.name -> out += EnumValueRenamed(p, nv.nameSpan, new, ov, nv)
            }
        }
        old.values
            .filter { it.ordinal !in newValues }
            .forEach { ov ->
                out += EnumValueRemoved(memberPath(old, ov.name), ov.nameSpan, old, ov)
            }
    }

    private fun members(new: UnionType, old: UnionType, out: MutableList<Change>) {
        val oldMembers = old.members.associateBy { it.ordinal }
        val newMembers = new.members.associateBy { it.ordinal }
        new.members.forEach { nm ->
            val om = oldMembers[nm.ordinal]
            val p = memberPath(new, nm.ordinal.toString())
            when {
                om == null -> out += UnionMemberAdded(p, nm.span, new, nm)
                om.type != nm.type -> out += UnionMemberTypeChanged(p, nm.span, new, om, nm)
            }
        }
        old.members
            .filter { it.ordinal !in newMembers }
            .forEach { om ->
                out += UnionMemberRemoved(memberPath(old, om.ordinal.toString()), om.span, old, om)
            }
    }

    private fun reserved(old: TypeDecl, new: TypeDecl, out: MutableList<Change>) {
        val oldReserved = reservedOf(old) ?: return
        val newReserved = reservedOf(new) ?: return
        if (oldReserved != newReserved)
            out += ReservedChanged(path(new), new.nameSpan, oldReserved, newReserved)
    }

    private fun reservedOf(decl: TypeDecl): Reserved? =
        when (decl) {
            is RecordType -> decl.reserved
            is EnumType -> decl.reserved
            is UnionType -> null
        }

    private fun annotations(
        path: String,
        span: Span,
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
                if (target == "" && key == "deprecated")
                    out += DeprecationChanged(path, span, to != null)
                else out += AnnotationChanged(path, span, target, key, from, to)
            }
        }
    }

    /**
     * A type stripped of its refinements, so a bound or pattern change alone does not look like a
     * type change.
     */
    private fun typeCore(type: Type): Type =
        when (type) {
            is Scalar -> Scalar(type.builtin)
            is ListOf -> ListOf(typeCore(type.element), type.nullableElement)
            is MapOf -> MapOf(typeCore(type.key), typeCore(type.value), type.nullableValue)
            is Ref -> type
        }

    private fun refinementsOf(type: Type): Refinements =
        when (type) {
            is Scalar -> type.refinements
            is ListOf -> type.refinements
            is MapOf -> type.refinements
            is Ref -> Refinements.NONE
        }

    /**
     * A decimal's precision or scale is part of its type, not a bound, so a change there is a type
     * change.
     */
    private fun typeChanged(old: Type, new: Type): Boolean {
        if (typeCore(old) != typeCore(new)) return true
        if (
            old !is Scalar ||
                new !is Scalar ||
                old.builtin != Builtin.DECIMAL ||
                new.builtin != Builtin.DECIMAL
        ) {
            return false
        }
        return old.refinements.precision != new.refinements.precision ||
            old.refinements.scale != new.refinements.scale
    }

    private fun tightened(old: Refinements, new: Refinements): Boolean {
        val minTightened = boundTightened(old.min, new.min, widens = false)
        val maxTightened = boundTightened(old.max, new.max, widens = true)
        val patternChanged = new.pattern != null && new.pattern != old.pattern
        return minTightened || maxTightened || patternChanged
    }

    /**
     * [widens] is true for an upper bound, where a larger value is looser; false for a lower bound.
     */
    private fun boundTightened(old: BigDecimal?, new: BigDecimal?, widens: Boolean): Boolean =
        when {
            old == null && new == null -> false
            new == null -> false
            old == null -> true
            widens -> new < old
            else -> new > old
        }

    private fun path(decl: TypeDecl): String {
        val qn = decl.qualifiedName
        return "${qn.namespace}.${qn.path.joinToString(".")}"
    }

    private fun memberPath(decl: TypeDecl, name: String) = "${path(decl)}.$name"
}
