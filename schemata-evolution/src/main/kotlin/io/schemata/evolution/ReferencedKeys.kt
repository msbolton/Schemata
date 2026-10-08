package io.schemata.evolution

import io.schemata.core.ir.Field
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.keyFields

/**
 * A model referenced by key is written, in every model that references it, as its key: Protobuf,
 * XSD, JSON Schema, and OpenAPI emit `<field>_<key>` (or a `<Model>Key` record for a composite key)
 * where the reference stands. So a change to that key reaches every referencing model, though the
 * referencing models themselves did not change: a key added, removed, moved, or made composite
 * reshapes their reference fields, a key field renamed renames them, and a key field retyped
 * retypes them. The document rulebooks judge such a change against the models that reference the
 * one it touches and name them.
 */
internal object ReferencedKeys {
    private sealed interface Effect {
        val referencing: List<QualifiedName>
    }

    /** The key's fields, their order, or their number changed. */
    private class Reshaped(override val referencing: List<QualifiedName>, val what: String) :
        Effect

    /** A key field kept its place in the key and changed its name. */
    private class Renamed(override val referencing: List<QualifiedName>, val from: String) : Effect

    /** A key field kept its place in the key and changed its type. */
    private class Retyped(override val referencing: List<QualifiedName>) : Effect

    /**
     * [inner], the verdict [target] gives [change] on its own, joined with what the change does to
     * the models that reference the model it touches. [visible] narrows those models to the ones
     * the target's output carries (OpenAPI's, reachable from a service); a change that reaches none
     * of them keeps [inner].
     */
    fun judge(
        target: String,
        change: Change,
        ctx: ChangeContext,
        inner: Verdict,
        visible: (QualifiedName) -> Boolean = { true },
    ): Verdict {
        val effect = effect(change, ctx) ?: return inner
        val referencing = effect.referencing.filter(visible)
        if (referencing.isEmpty()) return inner
        val models = englishNames(referencing)
        return when (effect) {
            is Retyped -> retyped(change as FieldTypeChanged, models, inner)
            is Reshaped -> join(inner, reshaped(target, change.path, effect.what, models))
            is Renamed -> join(inner, renamed(target, change.path, models))
        }
    }

    private fun effect(change: Change, ctx: ChangeContext): Effect? =
        when (change) {
            is AnnotationChanged -> {
                val record = keyOwner(change)
                if (record == null) null
                else if (key(ctx.old, record) == key(ctx.new, record)) null
                else
                    ctx.keyReferences(record)
                        .takeIf { it.isNotEmpty() }
                        ?.let { Reshaped(it, "${annotationLabel(change)} ${changeWord(change)}") }
            }
            is FieldRenamed -> {
                val inKey =
                    inKey(ctx.old, change.record.qualifiedName, change.from) &&
                        inKey(ctx.new, change.record.qualifiedName, change.to)
                if (!inKey) null
                else
                    ctx.keyReferences(change.record.qualifiedName)
                        .takeIf { it.isNotEmpty() }
                        ?.let { Renamed(it, change.from.name) }
            }
            is FieldTypeChanged ->
                if (!inKey(ctx.new, change.record.qualifiedName, change.to)) null
                else
                    ctx.keyReferences(change.record.qualifiedName)
                        .takeIf { it.isNotEmpty() }
                        ?.let { Retyped(it) }
            else -> null
        }

    /** The record whose key a `{ id }` or `@@id` fact belongs to, for a change to that fact. */
    private fun keyOwner(change: AnnotationChanged): QualifiedName? {
        if (change.target != "sql" || change.key != "key") return null
        return when (val owner = change.newOwner) {
            is FieldOwner -> owner.record.qualifiedName
            is DeclarationOwner -> (owner.decl as? RecordType)?.qualifiedName
            else -> null
        }
    }

    /** The key of [record] in [schema] as its references copy it: each field's name and type. */
    private fun key(schema: Schema, record: QualifiedName): List<Pair<String, Type>> =
        (schema.lookupOrNull(record) as? RecordType)?.keyFields()?.map { it.name to it.type }
            ?: emptyList()

    private fun inKey(schema: Schema, record: QualifiedName, field: Field): Boolean =
        (schema.lookupOrNull(record) as? RecordType)?.keyFields()?.any {
            it.ordinal == field.ordinal
        } == true

    /** What a change does to the referencing models, as a verdict of its own and a clause. */
    private class Impact(
        val breaking: Boolean,
        val message: String,
        val clause: String,
        val help: String,
    )

    private fun reshaped(target: String, path: String, what: String, models: String): Impact {
        val reason =
            when (target) {
                "proto" -> "the wire shape changes"
                "openapi" -> "clients send and read the old key"
                else -> "old documents carry the old key"
            }
        return Impact(
            breaking = true,
            message =
                "$path: $what breaks the reference fields of $models, which carry this model's key: $reason",
            clause = "it also breaks the reference fields of $models, which carry this model's key",
            help =
                "keep the key while other models reference it, or add new reference fields for a new key",
        )
    }

    private fun renamed(target: String, path: String, models: String): Impact =
        if (target == "proto")
            Impact(
                breaking = false,
                message =
                    "$path: the key field was renamed; the reference fields of $models are named " +
                        "after it, so their JSON mapping changes",
                clause = "the reference fields of $models are named after it and change too",
                help = "regenerate the referencing models along with this one",
            )
        else {
            val reason =
                if (target == "openapi") "clients use the old name"
                else "old documents use the old name"
            Impact(
                breaking = true,
                message =
                    "$path: the key field was renamed breaks the reference fields of $models, which " +
                        "are named after it: $reason",
                clause = "it also breaks the reference fields of $models, which are named after it",
                help = "keep the key field's name while other models reference it",
            )
        }

    /**
     * A retyped key field changes every reference field that copies it in the same way, so the
     * referencing models fare as the field itself does; this names them, and raises a compatible
     * retype to a note since their generated code changes too.
     */
    private fun retyped(change: FieldTypeChanged, models: String, inner: Verdict): Verdict {
        val clause = "the reference fields of $models carry this key and change type with it"
        val help = "regenerate the code and schemas of the referencing models along with this one"
        return when (inner) {
            is Verdict.Compatible ->
                Verdict.Note(
                    "${change.path}: type changed from ${shown(change.from.type)} to " +
                        "${shown(change.to.type)}; $clause",
                    help,
                )
            is Verdict.Note -> Verdict.Note("${inner.message}; $clause", help)
            is Verdict.Breaking -> Verdict.Breaking("${inner.message}; $clause", inner.help)
        }
    }

    /**
     * The more severe of [inner] and [impact], carrying the other's clause when both say something.
     */
    private fun join(inner: Verdict, impact: Impact): Verdict =
        when (inner) {
            is Verdict.Compatible ->
                if (impact.breaking) Verdict.Breaking(impact.message, impact.help)
                else Verdict.Note(impact.message, impact.help)
            is Verdict.Note ->
                if (impact.breaking) Verdict.Breaking(impact.message, impact.help)
                else Verdict.Note("${inner.message}; ${impact.clause}", inner.help)
            is Verdict.Breaking ->
                Verdict.Breaking("${inner.message}; ${impact.clause}", inner.help)
        }

    /** `a.Order`, `a.Order and b.Invoice`, `a.A, a.B, and a.C`. */
    private fun englishNames(names: List<QualifiedName>): String {
        val shown = names.map { it.toString() }
        return when (shown.size) {
            1 -> shown.single()
            2 -> "${shown[0]} and ${shown[1]}"
            else -> shown.dropLast(1).joinToString(", ") + ", and " + shown.last()
        }
    }
}
