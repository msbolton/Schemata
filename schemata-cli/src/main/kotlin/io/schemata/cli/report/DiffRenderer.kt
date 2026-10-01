package io.schemata.cli.report

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.Schema
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.Value
import io.schemata.core.ir.kindWord
import io.schemata.core.ir.selfAndNested
import io.schemata.evolution.AnnotationChanged
import io.schemata.evolution.Change
import io.schemata.evolution.Comparison
import io.schemata.evolution.DeclarationAdded
import io.schemata.evolution.DeclarationKindChanged
import io.schemata.evolution.DeclarationRemoved
import io.schemata.evolution.DeprecationChanged
import io.schemata.evolution.DocChanged
import io.schemata.evolution.EnumValueAdded
import io.schemata.evolution.EnumValueRemoved
import io.schemata.evolution.EnumValueRenamed
import io.schemata.evolution.FieldAdded
import io.schemata.evolution.FieldDefaultChanged
import io.schemata.evolution.FieldNullabilityChanged
import io.schemata.evolution.FieldRefinementChanged
import io.schemata.evolution.FieldRemoved
import io.schemata.evolution.FieldRenamed
import io.schemata.evolution.FieldTypeChanged
import io.schemata.evolution.Judged
import io.schemata.evolution.NamespaceAdded
import io.schemata.evolution.NamespaceRemoved
import io.schemata.evolution.ReservedChanged
import io.schemata.evolution.Rulebook
import io.schemata.evolution.UnionMemberAdded
import io.schemata.evolution.UnionMemberRemoved
import io.schemata.evolution.UnionMemberTypeChanged
import io.schemata.evolution.Verdict
import io.schemata.target.TypeText

/** `schemata diff`'s own output: the human change list, and the JSON comparison document. */
object DiffRenderer {
    /**
     * One block per changed declaration, in NEW's order (removals after); `no changes` when
     * [comparison] found none. A trailer notes when a changed declaration has an implicit ordinal
     * on either side, since the differ matches members by ordinal and an implicit one shifts with
     * declaration order.
     */
    fun changes(
        comparison: Comparison,
        old: Schema,
        new: Schema,
        implicitOrdinals: Set<QualifiedName>,
    ): String {
        if (comparison.judged.isEmpty()) return "no changes\n"
        val groups = LinkedHashMap<String, MutableList<Judged>>()
        comparison.judged.forEach { j ->
            groups.getOrPut(groupKey(j.change, old, new)) { mutableListOf() } += j
        }
        val lines = mutableListOf<String>()
        groups.forEach { (decl, judged) ->
            lines += decl
            judged.forEach { j -> lines += "  ${describe(j.change)}    ${verdicts(j)}" }
        }
        val implicitNames = implicitOrdinals.map { it.toString() }.toSet()
        if (groups.keys.any { it in implicitNames }) {
            lines += ""
            lines +=
                "note: a compared declaration has an implicit ordinal; reordering a field, " +
                    "value, or member changes its identity"
        }
        return lines.joinToString("\n") + "\n"
    }

    /**
     * Every judged change, a per-target summary, and the report's exit code, as one JSON document.
     */
    fun json(comparison: Comparison, report: Report, rulebooks: List<Rulebook>): String =
        Json.document(
            Json.Obj(
                "changes" to comparison.judged.map { changeJson(it) },
                "summary" to obj(rulebooks.map { it.target to summaryJson(comparison, it.target) }),
                "exitCode" to report.exitCode,
            )
        )

    /** [Json.Obj] built from a field list known only at runtime, such as one entry per target. */
    private fun obj(fields: List<Pair<String, Any?>>): Json.Obj = Json.Obj(*fields.toTypedArray())

    private fun summaryJson(comparison: Comparison, target: String): Json.Obj {
        val verdicts = comparison.judged.flatMap { it.verdicts }.filter { it.target == target }
        return Json.Obj(
            "breaking" to verdicts.count { it.verdict is Verdict.Breaking },
            "notes" to verdicts.count { it.verdict is Verdict.Note },
        )
    }

    private fun changeJson(j: Judged): Json.Obj {
        val c = j.change
        return Json.Obj(
            "kind" to c.kind,
            "path" to c.path,
            "old" to oldText(c),
            "new" to newText(c),
            "file" to c.span.file,
            "line" to c.span.startLine,
            "deprecatedInOld" to j.deprecatedInOld,
            "verdicts" to obj(j.verdicts.map { it.target to verdictJson(it.verdict) }),
        )
    }

    private fun verdictJson(v: Verdict): Any =
        when (v) {
            is Verdict.Compatible -> "compatible"
            is Verdict.Note ->
                Json.Obj("verdict" to "note", "message" to v.message, "help" to v.help)
            is Verdict.Breaking ->
                Json.Obj("verdict" to "breaking", "message" to v.message, "help" to v.help)
        }

    private fun oldText(c: Change): String? =
        when (c) {
            is FieldRenamed -> c.from.name
            is FieldTypeChanged -> TypeText.of(c.from.type, c.from.nullable)
            is FieldNullabilityChanged -> TypeText.of(c.from.type, c.from.nullable)
            is FieldRefinementChanged -> TypeText.of(c.from.type, c.from.nullable)
            is FieldDefaultChanged -> valueText(c.from.default)
            is EnumValueRenamed -> c.from.name
            is UnionMemberTypeChanged -> TypeText.of(c.from.type)
            is ReservedChanged -> reservedText(c.from)
            is AnnotationChanged -> annotationText(c.from)
            is DeprecationChanged -> (!c.deprecated).toString()
            is DeclarationKindChanged -> c.from.kindWord
            else -> null
        }

    private fun newText(c: Change): String? =
        when (c) {
            is FieldRenamed -> c.to.name
            is FieldTypeChanged -> TypeText.of(c.to.type, c.to.nullable)
            is FieldNullabilityChanged -> TypeText.of(c.to.type, c.to.nullable)
            is FieldRefinementChanged -> TypeText.of(c.to.type, c.to.nullable)
            is FieldDefaultChanged -> valueText(c.to.default)
            is EnumValueRenamed -> c.to.name
            is UnionMemberTypeChanged -> TypeText.of(c.to.type)
            is ReservedChanged -> reservedText(c.to)
            is AnnotationChanged -> annotationText(c.to)
            is DeprecationChanged -> c.deprecated.toString()
            is DeclarationKindChanged -> c.to.kindWord
            else -> null
        }

    private fun valueText(v: Value?): String? =
        when (v) {
            null -> null
            is IntValue -> v.value.toString()
            is RealValue -> v.value.toPlainString()
            is StringValue -> v.value
            is BoolValue -> v.value.toString()
            is EnumRef -> v.value
        }

    private fun reservedText(r: Reserved): String {
        val ordinals =
            r.ordinals.joinToString(", ") {
                if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}"
            }
        val names = r.names.sorted().joinToString(", ") { "\"$it\"" }
        return listOf(ordinals, names).filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun annotationText(v: AnnotationValue?): String? =
        when (v) {
            null -> null
            is AnnotationValue.Flag -> "true"
            is AnnotationValue.Str -> v.value
            is AnnotationValue.Num -> v.value.toString()
            is AnnotationValue.Bool -> v.value.toString()
            is AnnotationValue.Name -> v.value
            is AnnotationValue.Names -> v.values.joinToString(",")
        }

    private fun describe(c: Change): String =
        when (c) {
            is NamespaceAdded -> "namespace added"
            is NamespaceRemoved -> "namespace removed"
            is DeclarationAdded -> "declaration added"
            is DeclarationRemoved -> "declaration removed"
            is DeclarationKindChanged -> "kind changed from ${c.from.kindWord} to ${c.to.kindWord}"
            is FieldAdded -> "field '${c.field.name}' added"
            is FieldRemoved -> "field '${c.field.name}' removed"
            is FieldRenamed -> "field '${c.from.name}' renamed to '${c.to.name}'"
            is FieldTypeChanged ->
                "field '${c.to.name}' type changed from ${TypeText.of(c.from.type, c.from.nullable)} " +
                    "to ${TypeText.of(c.to.type, c.to.nullable)}"
            is FieldNullabilityChanged ->
                "field '${c.to.name}' nullability changed from ${TypeText.of(c.from.type, c.from.nullable)} " +
                    "to ${TypeText.of(c.to.type, c.to.nullable)}"
            is FieldDefaultChanged -> "field '${c.to.name}' default changed"
            is FieldRefinementChanged ->
                "field '${c.to.name}' refinement ${if (c.tightened) "tightened" else "loosened"}"
            is EnumValueAdded -> "value '${c.value.name}' added"
            is EnumValueRemoved -> "value '${c.value.name}' removed"
            is EnumValueRenamed -> "value '${c.from.name}' renamed to '${c.to.name}'"
            is UnionMemberAdded -> "member added: ${TypeText.of(c.member.type)}"
            is UnionMemberRemoved -> "member removed: ${TypeText.of(c.member.type)}"
            is UnionMemberTypeChanged ->
                "member type changed from ${TypeText.of(c.from.type)} to ${TypeText.of(c.to.type)}"
            is ReservedChanged -> "reserved changed"
            is AnnotationChanged -> "@${c.target}(${c.key}) changed"
            is DeprecationChanged ->
                if (c.deprecated) "marked deprecated" else "no longer deprecated"
            is DocChanged -> "doc changed"
        }

    private fun verdicts(j: Judged) =
        j.verdicts.joinToString(", ") { "${it.target}: ${word(it.verdict)}" }

    private fun word(v: Verdict) =
        when (v) {
            is Verdict.Compatible -> "compatible"
            is Verdict.Note -> "note"
            is Verdict.Breaking -> "breaking"
        }

    /**
     * The declaration a change belongs to: a field, enum value, or union member change groups under
     * its own declaration directly; a declaration- or namespace-level change groups under itself.
     * Annotation, deprecation, and doc changes fire at either level, so they resolve by looking the
     * path up in both schemas.
     */
    private fun groupKey(change: Change, old: Schema, new: Schema): String =
        when (change) {
            is NamespaceAdded -> change.path
            is NamespaceRemoved -> change.path
            is DeclarationAdded -> change.path
            is DeclarationRemoved -> change.path
            is DeclarationKindChanged -> change.path
            is FieldAdded -> change.record.qualifiedName.toString()
            is FieldRemoved -> change.record.qualifiedName.toString()
            is FieldRenamed -> change.record.qualifiedName.toString()
            is FieldTypeChanged -> change.record.qualifiedName.toString()
            is FieldNullabilityChanged -> change.record.qualifiedName.toString()
            is FieldDefaultChanged -> change.record.qualifiedName.toString()
            is FieldRefinementChanged -> change.record.qualifiedName.toString()
            is EnumValueAdded -> change.enum.qualifiedName.toString()
            is EnumValueRemoved -> change.enum.qualifiedName.toString()
            is EnumValueRenamed -> change.enum.qualifiedName.toString()
            is UnionMemberAdded -> change.union.qualifiedName.toString()
            is UnionMemberRemoved -> change.union.qualifiedName.toString()
            is UnionMemberTypeChanged -> change.union.qualifiedName.toString()
            is ReservedChanged -> change.path
            is AnnotationChanged -> declarationPath(change.path, old, new)
            is DeprecationChanged -> declarationPath(change.path, old, new)
            is DocChanged -> declarationPath(change.path, old, new)
        }

    private fun declarationPath(path: String, old: Schema, new: Schema): String =
        if (declAt(new, path) != null || declAt(old, path) != null) path
        else path.substringBeforeLast(".")

    private fun declAt(schema: Schema, path: String): TypeDecl? =
        schema.namespaces
            .flatMap { it.declarations.flatMap { d -> d.selfAndNested() } }
            .firstOrNull { it.qualifiedName.toString() == path }
}
