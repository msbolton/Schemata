package io.schemata.target

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.keyFields

/**
 * The key fields of the model [qn] names, or null when it has none (a value type, composed or
 * embedded).
 */
fun Schema.keyOf(qn: QualifiedName): List<Field>? =
    (lookupOrNull(qn) as? RecordType)?.keyFields()?.takeIf { it.isNotEmpty() }

/** `<field>_<key field>` for a single key, as the SQL target names its column. */
fun referenceName(field: Field, key: Field): String = "${field.name}_${key.name}"

/**
 * The record that carries a composite key in place of the model [target] names: `<Target>Key`,
 * declared beside the model itself.
 */
fun keyRecordName(target: QualifiedName): QualifiedName =
    QualifiedName(target.namespace, target.path.dropLast(1) + "${target.simpleName}Key")

/**
 * Whether [decl] is a key record [referencesByKey] declared rather than one the schema wrote: it
 * sits beside a model with a composite key, is named for it, and shares its span.
 */
fun Schema.isKeyRecord(decl: TypeDecl): Boolean {
    if (decl !is RecordType || !decl.name.endsWith("Key")) return false
    val qn = decl.qualifiedName
    val model =
        lookupOrNull(
            QualifiedName(qn.namespace, qn.path.dropLast(1) + decl.name.removeSuffix("Key"))
        )
            as? RecordType ?: return false
    return (keyOf(model.qualifiedName)?.size ?: 0) > 1 &&
        decl.span == model.span &&
        decl.nameSpan == model.nameSpan
}

/**
 * The schema as a document target sends it, where a reference to a keyed model carries the model's
 * key rather than a copy of the model:
 * - a reference to a model with one key field becomes the field `<field>_<key>`, typed as the key
 *   field (its refinements included), keeping the reference's ordinal, nullability, and doc;
 * - a reference to a model with a composite key keeps its name and is typed as `<Target>Key`, a
 *   record holding the key fields in key order, numbered from 1, declared once beside the model;
 * - a list of a keyed model, or a map whose values are one, keeps its name and holds the key
 *   instead: the key field's type for a single key, `<Target>Key` for a composite one;
 * - a union member standing for a keyed model holds the key the same way and keeps the model's name
 *   ([io.schemata.core.ir.UnionMember.byKey]);
 * - a reference written `{ embed }`, or to a model without a key, still copies the record.
 *
 * A key field that is itself a reference to a keyed model is replaced by that model's key the same
 * way, so a key copied from a key reaches the scalar or enum it is made of. A `name` override any
 * target gives the reference is suffixed as its name is (`_<key>`), and one on the model carries
 * over to its key record (`<override>Key`). A service payload is the document itself, not a
 * reference, so it is left as written; the virtual back-references are skipped by every target
 * already and are left too.
 */
fun Schema.referencesByKey(): Schema = KeyedReferences(this).rewrite()

private class KeyedReferences(private val schema: Schema) {
    /** Every model a reference reaches through its composite key, in first-use order. */
    private val composites = LinkedHashSet<QualifiedName>()

    fun rewrite(): Schema {
        val namespaces =
            schema.namespaces.map { ns -> ns.copy(declarations = ns.declarations.map(::decl)) }
        // A key record's own fields may reach further composite keys; build until none is new.
        val keys = LinkedHashMap<QualifiedName, RecordType>()
        while (true) {
            val pending = composites.filter { it !in keys }
            if (pending.isEmpty()) break
            pending.forEach { keys[it] = keyRecord(schema.lookup(it) as RecordType) }
        }
        if (keys.isEmpty()) return Schema(namespaces)
        return Schema(namespaces.map { ns -> placed(ns, keys) })
    }

    private fun placed(ns: Namespace, keys: Map<QualifiedName, RecordType>): Namespace =
        ns.copy(declarations = besides(ns.declarations, keys))

    /** [decls] with each model's key record right after the model, at every depth. */
    private fun besides(
        decls: List<TypeDecl>,
        keys: Map<QualifiedName, RecordType>,
    ): List<TypeDecl> =
        decls.flatMap { decl ->
            val nested = besides(decl.nested, keys)
            val updated =
                when (decl) {
                    is RecordType -> decl.copy(nested = nested)
                    is EnumType -> decl.copy(nested = nested)
                    is UnionType -> decl.copy(nested = nested)
                }
            listOfNotNull(updated, keys[decl.qualifiedName])
        }

    private fun decl(decl: TypeDecl): TypeDecl =
        when (decl) {
            is RecordType -> record(decl)
            is EnumType -> decl.copy(nested = decl.nested.map(::decl))
            is UnionType -> union(decl)
        }

    private fun record(record: RecordType): RecordType {
        val fields = record.fields.map { if (it.virtual) it else field(it, emptySet()) }
        val renamed =
            record.fields
                .zip(fields)
                .filter { (a, b) -> a.name != b.name }
                .associate { (a, b) -> a.name to b.name }
        fun names(list: List<String>) = list.map { renamed[it] ?: it }
        return record.copy(
            fields = fields,
            nested = record.nested.map(::decl),
            compositeKey = names(record.compositeKey),
            uniques = record.uniques.map(::names),
            indexes = record.indexes.map(::names),
        )
    }

    /**
     * [field] with a reference to a keyed model replaced by the key. [chain] holds the models a
     * single key has already been followed through, so a key that leads back to its own model is
     * left as the record rather than followed forever.
     */
    private fun field(field: Field, chain: Set<QualifiedName>): Field =
        when (val type = field.type) {
            is Ref -> {
                val key = byKey(type)
                when {
                    key == null || type.target in chain -> field
                    key.size == 1 ->
                        field(
                            field.copy(
                                name = referenceName(field, key.single()),
                                type = key.single().type,
                                aliasName = key.single().aliasName,
                                annotations = suffixed(field.annotations, "_${key.single().name}"),
                            ),
                            chain + type.target,
                        )
                    else -> {
                        composites += type.target
                        field.copy(type = Ref(keyRecordName(type.target)), aliasName = null)
                    }
                }
            }
            is ListOf,
            is MapOf -> {
                val keyed = keyed(type, emptySet())
                if (keyed == type) field else field.copy(type = keyed, aliasName = null)
            }
            is Scalar -> field
        }

    /**
     * A union member standing for a keyed model carries its key, and remembers the model it stands
     * for so it keeps that model's name.
     */
    private fun union(union: UnionType): UnionType =
        union.copy(
            members =
                union.members.map { member ->
                    val keyed = keyed(member.type, emptySet())
                    if (keyed == member.type) member
                    else member.copy(type = keyed, byKey = (member.type as Ref).target)
                },
            nested = union.nested.map(::decl),
        )

    /**
     * [type] with every reference to a keyed model in it, itself or a list's element or a map's
     * value, replaced by the key's type: the key field's for a single key, `<Target>Key` for a
     * composite one. Nothing is renamed; only a field's own reference takes the key's name.
     */
    private fun keyed(type: Type, chain: Set<QualifiedName>): Type =
        when (type) {
            is Ref -> {
                val key = byKey(type)
                when {
                    key == null || type.target in chain -> type
                    key.size == 1 -> keyed(key.single().type, chain + type.target)
                    else -> {
                        composites += type.target
                        Ref(keyRecordName(type.target))
                    }
                }
            }
            is ListOf -> type.copy(element = keyed(type.element, chain))
            is MapOf -> type.copy(value = keyed(type.value, chain))
            is Scalar -> type
        }

    /** The key a reference carries, or null when it copies the record. */
    private fun byKey(ref: Ref): List<Field>? =
        if (ref.relation.embed) null else schema.keyOf(ref.target)

    /**
     * `<Target>Key`: the key fields of [model] in key order, numbered from 1, each a plain field (a
     * key field's own `id`, `unique`, `index`, and default belong to the model's table, not to
     * every copy of its key).
     */
    private fun keyRecord(model: RecordType): RecordType {
        val fields =
            model.keyFields().mapIndexed { i, key ->
                field(
                    key.copy(
                        ordinal = i + 1,
                        default = null,
                        key = false,
                        unique = false,
                        index = false,
                    ),
                    setOf(model.qualifiedName),
                )
            }
        val qn = keyRecordName(model.qualifiedName)
        return RecordType(
            qualifiedName = qn,
            name = qn.simpleName,
            fields = fields,
            reserved = Reserved.NONE,
            recursive = false,
            nested = emptyList(),
            doc = null,
            span = model.span,
            nameSpan = model.nameSpan,
            annotations = suffixed(model.annotations, "Key", keep = false),
        )
    }

    /**
     * [annotations] with every target's `name` override followed by [suffix]. With [keep] false
     * only the overrides survive: a key record inherits its model's name, not its other options.
     */
    private fun suffixed(
        annotations: Annotations,
        suffix: String,
        keep: Boolean = true,
    ): Annotations {
        val entries =
            annotations.entries.mapNotNull { (target, values) ->
                val name = values["name"] as? AnnotationValue.Str
                val updated =
                    when {
                        name != null && keep ->
                            values + ("name" to AnnotationValue.Str(name.value + suffix))
                        name != null -> mapOf("name" to AnnotationValue.Str(name.value + suffix))
                        keep -> values
                        else -> null
                    }
                updated?.let { target to it }
            }
        return if (entries.isEmpty()) Annotations.NONE else Annotations(entries.toMap())
    }
}
