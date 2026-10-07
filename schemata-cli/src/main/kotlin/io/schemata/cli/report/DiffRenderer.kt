package io.schemata.cli.report

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.HttpBinding
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.Payload
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Reserved
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Value
import io.schemata.core.ir.kindWord
import io.schemata.evolution.AnnotationChanged
import io.schemata.evolution.Change
import io.schemata.evolution.Comparison
import io.schemata.evolution.DeclarationAdded
import io.schemata.evolution.DeclarationKindChanged
import io.schemata.evolution.DeclarationOwner
import io.schemata.evolution.DeclarationRemoved
import io.schemata.evolution.DeprecationChanged
import io.schemata.evolution.DocChanged
import io.schemata.evolution.EnumValueAdded
import io.schemata.evolution.EnumValueOwner
import io.schemata.evolution.EnumValueRemoved
import io.schemata.evolution.EnumValueRenamed
import io.schemata.evolution.FieldAdded
import io.schemata.evolution.FieldDefaultChanged
import io.schemata.evolution.FieldNullabilityChanged
import io.schemata.evolution.FieldOwner
import io.schemata.evolution.FieldRefinementChanged
import io.schemata.evolution.FieldRemoved
import io.schemata.evolution.FieldRenamed
import io.schemata.evolution.FieldTypeChanged
import io.schemata.evolution.Judged
import io.schemata.evolution.NamespaceAdded
import io.schemata.evolution.NamespaceOwner
import io.schemata.evolution.NamespaceRemoved
import io.schemata.evolution.OperationAdded
import io.schemata.evolution.OperationBindingChanged
import io.schemata.evolution.OperationOwner
import io.schemata.evolution.OperationRemoved
import io.schemata.evolution.OperationRenamed
import io.schemata.evolution.OperationRequestChanged
import io.schemata.evolution.OperationResponseChanged
import io.schemata.evolution.Owner
import io.schemata.evolution.ReservedChanged
import io.schemata.evolution.Rulebook
import io.schemata.evolution.ServiceAdded
import io.schemata.evolution.ServiceOwner
import io.schemata.evolution.ServiceRemoved
import io.schemata.evolution.UnionMemberAdded
import io.schemata.evolution.UnionMemberOwner
import io.schemata.evolution.UnionMemberRemoved
import io.schemata.evolution.UnionMemberTypeChanged
import io.schemata.evolution.Verdict
import io.schemata.evolution.changeWord
import io.schemata.lang.Diagnostic
import io.schemata.lang.SchemataText
import io.schemata.target.TypeText

/** `schemata diff`'s own output: the human change list, and the JSON comparison document. */
object DiffRenderer {
    /**
     * One block per changed declaration, in NEW's order (removals after); `no changes` when
     * [comparison] found none. Then a trailer: the total change count, one `breaking`/`note` count
     * line per selected rulebook, and, when a changed declaration has an implicit ordinal on either
     * side, a note counting the declarations with implicit ordinals ([implicitOrdinals], both sides
     * together), since the differ matches members by ordinal and an implicit one shifts with
     * declaration order.
     */
    fun changes(
        comparison: Comparison,
        rulebooks: List<Rulebook>,
        implicitOrdinals: Set<QualifiedName>,
    ): String {
        if (comparison.judged.isEmpty()) return "no changes\n"
        val groups = LinkedHashMap<String, MutableList<Judged>>()
        comparison.judged.forEach { j ->
            groups.getOrPut(groupKey(j.change)) { mutableListOf() } += j
        }
        val lines = mutableListOf<String>()
        groups.forEach { (decl, judged) ->
            lines += decl
            judged.forEach { j -> lines += "  ${describe(j)}    ${verdicts(j)}" }
        }
        lines += ""
        lines += plural(comparison.judged.size, "change")
        rulebooks.forEach { rb ->
            val verdicts =
                comparison.judged.flatMap { it.verdicts }.filter { it.target == rb.target }
            val breaking = verdicts.count { it.verdict is Verdict.Breaking }
            val notes = verdicts.count { it.verdict is Verdict.Note }
            lines += "${rb.target}: $breaking breaking, ${plural(notes, "note")}"
        }
        val implicitNames = implicitOrdinals.map { it.toString() }.toSet()
        if (groups.keys.any { it in implicitNames }) {
            lines += ""
            lines +=
                "note: ordinals are implicit in ${plural(implicitOrdinals.size, "declaration")}; " +
                    "run check --strict"
        }
        return lines.joinToString("\n") + "\n"
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /**
     * Every judged change, a per-target summary, and the report's exit code, as one JSON document.
     * When the two sides could not be compared, [errors] holds why (each `code`, `message`, `help`,
     * `file`, `line`) under an `errors` key, and the document otherwise reads as an empty diff.
     */
    fun json(
        comparison: Comparison,
        report: Report,
        rulebooks: List<Rulebook>,
        errors: List<Diagnostic> = emptyList(),
    ): String {
        val fields =
            mutableListOf<Pair<String, Any?>>(
                "changes" to comparison.judged.map { changeJson(it) },
                "summary" to obj(rulebooks.map { it.target to summaryJson(comparison, it.target) }),
                "exitCode" to report.exitCode,
            )
        if (errors.isNotEmpty()) fields += "errors" to errors.map { errorJson(it) }
        return Json.document(obj(fields))
    }

    internal fun errorJson(d: Diagnostic): Json.Obj =
        Json.Obj(
            "code" to d.code.id,
            "message" to d.message,
            "help" to d.help,
            "file" to d.span.file,
            "line" to d.span.startLine,
        )

    /** [Json.Obj] built from a field list known only at runtime, such as one entry per target. */
    internal fun obj(fields: List<Pair<String, Any?>>): Json.Obj = Json.Obj(*fields.toTypedArray())

    internal fun summaryJson(comparison: Comparison, target: String): Json.Obj {
        val verdicts = comparison.judged.flatMap { it.verdicts }.filter { it.target == target }
        return Json.Obj(
            "breaking" to verdicts.count { it.verdict is Verdict.Breaking },
            "notes" to verdicts.count { it.verdict is Verdict.Note },
        )
    }

    internal fun changeJson(j: Judged): Json.Obj {
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
            is OperationRenamed -> c.from.name
            is OperationRequestChanged -> payloadText(c.from)
            is OperationResponseChanged -> payloadText(c.from)
            is OperationBindingChanged -> bindingText(c.from)
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
            is OperationRenamed -> c.to.name
            is OperationRequestChanged -> payloadText(c.to)
            is OperationResponseChanged -> payloadText(c.to)
            is OperationBindingChanged -> bindingText(c.to)
            else -> null
        }

    /** `Order` or `stream Order`; `none` for an operation without one. */
    private fun payloadText(p: Payload?): String =
        when {
            p == null -> "none"
            p.stream -> "stream ${p.target.simpleName}"
            else -> p.target.simpleName
        }

    /** `get /orders/{id}` as written; `none` for an operation with no binding. */
    private fun bindingText(b: HttpBinding?): String =
        if (b == null) "none" else "${b.verb.lower} ${b.path}"

    private fun valueText(v: Value?): String? =
        when (v) {
            null -> null
            is IntValue -> v.value.toString()
            is RealValue -> v.value.toPlainString()
            is StringValue -> SchemataText.string(v.value)
            is BoolValue -> v.value.toString()
            is EnumRef -> v.value
        }

    private fun reservedText(r: Reserved): String {
        val ordinals =
            r.ordinals.joinToString(", ") {
                if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}"
            }
        val names = r.names.sorted().joinToString(", ") { SchemataText.string(it) }
        return listOf(ordinals, names).filter { it.isNotEmpty() }.joinToString("; ")
    }

    private fun annotationText(v: AnnotationValue?): String? =
        when (v) {
            null -> null
            is AnnotationValue.Flag -> "true"
            is AnnotationValue.Str -> SchemataText.string(v.value)
            is AnnotationValue.Num -> v.value.toString()
            is AnnotationValue.Bool -> v.value.toString()
            is AnnotationValue.Name -> v.value
            is AnnotationValue.Names -> v.values.joinToString(",")
        }

    /**
     * One change's line. A member's annotation, deprecation, or doc change names the member first
     * (`field 'id': @sql(key) removed`); a change to something OLD had marked `@deprecated` says so
     * (`deprecated field 'note' removed`).
     */
    private fun describe(j: Judged): String {
        val dep = if (j.deprecatedInOld) "deprecated " else ""
        return when (val c = j.change) {
            is NamespaceAdded -> "namespace added"
            is NamespaceRemoved -> "namespace removed"
            is DeclarationAdded -> "declaration added"
            is DeclarationRemoved -> "${dep}declaration removed"
            is DeclarationKindChanged ->
                (if (j.deprecatedInOld) "deprecated declaration's kind" else "kind") +
                    " changed from ${c.from.kindWord} to ${c.to.kindWord}"
            is FieldAdded -> "field '${c.field.name}' added"
            is FieldRemoved -> "${dep}field '${c.field.name}' removed"
            is FieldRenamed -> "${dep}field '${c.from.name}' renamed to '${c.to.name}'"
            is FieldTypeChanged ->
                "${dep}field '${c.to.name}' type changed from " +
                    "${TypeText.of(c.from.type, c.from.nullable)} to " +
                    TypeText.of(c.to.type, c.to.nullable)
            is FieldNullabilityChanged ->
                "${dep}field '${c.to.name}' nullability changed from " +
                    "${TypeText.of(c.from.type, c.from.nullable)} to " +
                    TypeText.of(c.to.type, c.to.nullable)
            is FieldDefaultChanged -> "${dep}field '${c.to.name}' default ${defaultWord(c)}"
            is FieldRefinementChanged ->
                "${dep}field '${c.to.name}' refinement " +
                    if (c.tightened) "tightened" else "loosened"
            is EnumValueAdded -> "value '${c.value.name}' added"
            is EnumValueRemoved -> "${dep}value '${c.value.name}' removed"
            is EnumValueRenamed -> "${dep}value '${c.from.name}' renamed to '${c.to.name}'"
            is UnionMemberAdded -> "member added: ${TypeText.of(c.member.type)}"
            is UnionMemberRemoved -> "member removed: ${TypeText.of(c.member.type)}"
            is UnionMemberTypeChanged ->
                "member type changed from ${TypeText.of(c.from.type)} to ${TypeText.of(c.to.type)}"
            is ReservedChanged -> "reserved changed"
            is AnnotationChanged ->
                memberPrefix(c.newOwner) + "@${c.target}(${c.key}) ${changeWord(c)}"
            is DeprecationChanged ->
                memberPrefix(c.owner) +
                    if (c.deprecated) "marked deprecated" else "no longer deprecated"
            is DocChanged -> memberPrefix(c.owner) + "doc changed"
            is ServiceAdded -> "service added"
            is ServiceRemoved -> "${dep}service removed"
            is OperationAdded -> "operation '${c.operation.name}' added"
            is OperationRemoved -> "${dep}operation '${c.operation.name}' removed"
            is OperationRenamed -> "${dep}operation '${c.from.name}' renamed to '${c.to.name}'"
            is OperationRequestChanged ->
                "${dep}operation '${c.operation.name}' request changed from " +
                    "${payloadText(c.from)} to ${payloadText(c.to)}"
            is OperationResponseChanged ->
                "${dep}operation '${c.operation.name}' response changed from " +
                    "${payloadText(c.from)} to ${payloadText(c.to)}"
            is OperationBindingChanged ->
                "${dep}operation '${c.operation.name}' binding changed from " +
                    "${bindingText(c.from)} to ${bindingText(c.to)}"
        }
    }

    private fun defaultWord(c: FieldDefaultChanged) =
        when {
            c.from.default == null -> "added"
            c.to.default == null -> "removed"
            else -> "changed"
        }

    /** `field 'id': ` for a member-level change; empty when the change is on the group itself. */
    private fun memberPrefix(owner: Owner): String =
        when (owner) {
            is FieldOwner -> "field '${owner.field.name}': "
            is EnumValueOwner -> "value '${owner.value.name}': "
            is UnionMemberOwner -> "member #${owner.member.ordinal}: "
            is OperationOwner -> "operation '${owner.operation.name}': "
            is DeclarationOwner,
            is NamespaceOwner,
            is ServiceOwner -> ""
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
     * The heading a change groups under: a field, enum value, or union member change groups under
     * its own declaration, and an operation change under its service; a declaration-, service-, or
     * namespace-level change under itself. Annotation, deprecation, and doc changes carry their
     * owner, which names the heading directly.
     */
    private fun groupKey(change: Change): String =
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
            is AnnotationChanged -> ownerKey(change.newOwner)
            is DeprecationChanged -> ownerKey(change.owner)
            is DocChanged -> ownerKey(change.owner)
            is ServiceAdded -> change.path
            is ServiceRemoved -> change.path
            is OperationAdded -> change.service.qualifiedName.toString()
            is OperationRemoved -> change.service.qualifiedName.toString()
            is OperationRenamed -> change.service.qualifiedName.toString()
            is OperationRequestChanged -> change.service.qualifiedName.toString()
            is OperationResponseChanged -> change.service.qualifiedName.toString()
            is OperationBindingChanged -> change.service.qualifiedName.toString()
        }

    private fun ownerKey(owner: Owner): String =
        when (owner) {
            is NamespaceOwner -> owner.namespace.name
            is DeclarationOwner -> owner.decl.qualifiedName.toString()
            is FieldOwner -> owner.record.qualifiedName.toString()
            is EnumValueOwner -> owner.enum.qualifiedName.toString()
            is UnionMemberOwner -> owner.union.qualifiedName.toString()
            is ServiceOwner -> owner.service.qualifiedName.toString()
            is OperationOwner -> owner.service.qualifiedName.toString()
        }
}
