package io.schemata.evolution

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Value
import io.schemata.lang.Span

/** Schema-building and verdict-computing helpers shared by every rulebook's tests. */
fun at(line: Int = 1) = Span("x.schemata", line, 1, line, 10)

fun qn(ns: String, vararg path: String) = QualifiedName(ns, path.toList())

fun namespace(
    name: String,
    declarations: List<TypeDecl> = emptyList(),
    annotations: Annotations = Annotations.NONE,
) = Namespace(name, declarations, at(), annotations)

fun field(
    ordinal: Int,
    name: String,
    type: Type = Scalar(Builtin.BOOL),
    nullable: Boolean = false,
    default: Value? = null,
    annotations: Annotations = Annotations.NONE,
) = Field(ordinal, name, type, nullable, default, null, null, at(), at(), annotations)

fun record(
    ns: String,
    name: String,
    vararg fields: Field,
    reserved: Reserved = Reserved.NONE,
    annotations: Annotations = Annotations.NONE,
) =
    RecordType(
        qn(ns, name),
        name,
        fields.toList(),
        reserved,
        false,
        emptyList(),
        null,
        at(),
        at(),
        annotations,
    )

fun value(ordinal: Int, name: String) = EnumValue(ordinal, name, null, at(), at())

fun enum(ns: String, name: String, vararg values: EnumValue, reserved: Reserved = Reserved.NONE) =
    EnumType(qn(ns, name), name, values.toList(), reserved, emptyList(), null, at(), at())

fun member(ordinal: Int, type: Type) = UnionMember(ordinal, type, null, at())

fun union(ns: String, name: String, vararg members: UnionMember) =
    UnionType(qn(ns, name), name, members.toList(), emptyList(), null, at(), at())

fun ns(decl: TypeDecl) = namespace(decl.qualifiedName.namespace, listOf(decl))

/** Every change between [old] and [new] in the differ's order, each classified by [rulebook]. */
fun verdicts(rulebook: Rulebook, old: List<Namespace>, new: List<Namespace>): List<Verdict> {
    val oldSchema = Schema(old)
    val newSchema = Schema(new)
    val ctx = ChangeContext(oldSchema, newSchema)
    return Differ.diff(oldSchema, newSchema).map { rulebook.classify(it, ctx) }
}

/** The one change between [old] and [new], classified by [rulebook]; fails on any other count. */
fun verdict(rulebook: Rulebook, old: Namespace, new: Namespace): Verdict =
    verdicts(rulebook, listOf(old), listOf(new)).single()
