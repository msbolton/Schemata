package io.schemata.evolution

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.target.TypeText

/**
 * Whether data shaped by one type can still be read once a field's type becomes another,
 * independent of any particular pair of schemas or the member the type belongs to.
 */
object TypeCompat {
    /**
     * Protobuf's own wire-compatibility rules: the same keyword, `int32` and `int64` either way, or
     * a string-shaped keyword (`string`, `uuid`, `date`, `time`, `decimal`) becoming `bytes`, which
     * reads every old value. `bytes` becoming a string-shaped keyword is a note, since proto
     * rejects a string that is not valid UTF-8 and old bytes need not be. A bare reference is never
     * compatible with a scalar here: only an enum reference is, and telling an enum from a record
     * or union reference needs a schema, which this function does not have. [ProtoRules] resolves
     * that case itself before falling back to this function.
     */
    fun proto(from: Type, to: Type): Verdict {
        val fromKeyword = protoKeyword(from)
        val toKeyword = protoKeyword(to)
        val keywords = setOf(fromKeyword, toKeyword)
        return when {
            fromKeyword == toKeyword -> Verdict.Compatible
            keywords == setOf("int32", "int64") -> Verdict.Compatible
            fromKeyword == "string" && toKeyword == "bytes" -> Verdict.Compatible
            fromKeyword == "bytes" && toKeyword == "string" ->
                Verdict.Note(
                    "old values must be valid UTF-8",
                    "confirm every old value is valid UTF-8 before reading it as a string",
                )
            else ->
                Verdict.Breaking(
                    "decoding: the wire types differ",
                    "add a new field instead of changing this one's type",
                )
        }
    }

    /**
     * The Postgres widenings between two different scalar types: a wider integer or float, or a
     * decimal keeping its scale while gaining precision. A bound change on the same keyword (a
     * longer `string(max)`) is a refinement change, not a type change, so it never reaches here.
     */
    fun sqlWidening(from: Type, to: Type): Boolean {
        if (from !is Scalar || to !is Scalar) return false
        return when (from.builtin to to.builtin) {
            Builtin.INT32 to Builtin.INT64 -> true
            Builtin.FLOAT32 to Builtin.FLOAT64 -> true
            Builtin.DECIMAL to Builtin.DECIMAL -> decimalWidened(from.refinements, to.refinements)
            else -> false
        }
    }

    /** [sqlWidening]'s list, plus a uuid relaxing into a plain string (never the other way). */
    fun instanceWidening(from: Type, to: Type): Boolean {
        if (
            from is Scalar &&
                to is Scalar &&
                from.builtin == Builtin.UUID &&
                to.builtin == Builtin.STRING
        ) {
            return true
        }
        return sqlWidening(from, to)
    }

    private fun protoKeyword(type: Type): String =
        when (type) {
            is Scalar -> scalarKeyword(type.builtin)
            is ListOf -> "${protoKeyword(type.element)}[]"
            is MapOf -> "map<${protoKeyword(type.key)}, ${protoKeyword(type.value)}>"
            is Ref -> (if (type.relation.embed) "embed:" else "ref:") + type.target
        }

    private fun scalarKeyword(builtin: Builtin): String =
        when (builtin) {
            Builtin.BOOL -> "bool"
            Builtin.INT32 -> "int32"
            Builtin.INT64 -> "int64"
            Builtin.FLOAT32 -> "float"
            Builtin.FLOAT64 -> "double"
            Builtin.STRING,
            Builtin.UUID,
            Builtin.DATE,
            Builtin.TIME,
            Builtin.DECIMAL -> "string"
            Builtin.BYTES -> "bytes"
            Builtin.INSTANT -> "google.protobuf.Timestamp"
            Builtin.DURATION -> "google.protobuf.Duration"
        }

    private fun decimalWidened(from: Refinements, to: Refinements): Boolean {
        val fromPrecision = from.precision ?: return false
        val toPrecision = to.precision ?: return false
        return from.scale == to.scale && toPrecision >= fromPrecision
    }
}

/**
 * The verdict a table- or document-shaped target gives a field whose type changed, shared by
 * [SqlRules] and [InstanceRules]: two scalars are judged by [scalarWidening], two references by
 * [refVerdict], a list or map keeps its element (or key and value) type and may only loosen its
 * element nullability, and anything else is incompatible. Once the shapes agree, a bound or pattern
 * tightened anywhere in the type still breaks, since a value valid for OLD may fall outside it.
 * [holders] names what holds old values (`existing rows`, `old documents`).
 */
internal class StructuralTypeVerdict(
    private val scalarWidening: (Type, Type) -> Boolean,
    private val holders: String,
    private val incompatible: String,
    private val help: String,
    private val refVerdict: (Ref, Ref) -> Verdict,
) {
    fun of(from: Type, to: Type): Verdict {
        val shape = shapeVerdict(from, to)
        if (shape !is Verdict.Compatible) return shape
        return if (refinementsTightened(from, to))
            Verdict.Breaking("$holders: old values can fall outside the new bound", help)
        else Verdict.Compatible
    }

    private fun shapeVerdict(from: Type, to: Type): Verdict =
        when {
            from is Scalar && to is Scalar ->
                if (scalarWidening(from, to)) Verdict.Compatible
                else Verdict.Breaking("$holders: old values do not fit the new type", help)
            from is Ref && to is Ref -> refVerdict(from, to)
            from is ListOf && to is ListOf ->
                if (
                    elementCompatible(from.element, to.element) &&
                        (!from.nullableElement || to.nullableElement)
                )
                    Verdict.Compatible
                else Verdict.Breaking("$holders: $incompatible", help)
            from is MapOf && to is MapOf ->
                when {
                    typeCore(from.key) != typeCore(to.key) ->
                        Verdict.Breaking("$holders: entries are keyed by the old key type", help)
                    elementCompatible(from.value, to.value) &&
                        (!from.nullableValue || to.nullableValue) -> Verdict.Compatible
                    else -> Verdict.Breaking("$holders: $incompatible", help)
                }
            else -> Verdict.Breaking("$holders: $incompatible", help)
        }

    /** An element or value keeps its type, or widens by the same rules as a top-level field. */
    private fun elementCompatible(from: Type, to: Type): Boolean =
        (typeCore(from) == typeCore(to) && embeds(from) == embeds(to)) ||
            shapeVerdict(from, to) is Verdict.Compatible
}

/**
 * [verdict], a judgement of [change]'s two types alone, restated for the field: a note reads
 * `<path>: type changed from A to B; <caveat>`, a break `<path>: type changed from A to B breaks
 * <reason>`.
 */
internal fun wrapTypeVerdict(change: FieldTypeChanged, verdict: Verdict): Verdict {
    val what = "type changed from ${shown(change.from.type)} to ${shown(change.to.type)}"
    return when (verdict) {
        is Verdict.Compatible -> verdict
        is Verdict.Note -> Verdict.Note("${change.path}: $what; ${verdict.message}", verdict.help)
        is Verdict.Breaking ->
            Verdict.Breaking("${change.path}: $what breaks ${verdict.message}", verdict.help)
    }
}

/** [type] as written, with the `{ embed }` option a reference to a keyed model carries. */
fun shown(type: Type, nullable: Boolean = false): String {
    val text = TypeText.of(type, nullable)
    if (!embeds(type)) return text
    return if (text.endsWith(" }")) text.removeSuffix(" }") + ", embed }" else "$text { embed }"
}

/** Whether [type] is, or is a list of or map to, a reference copied inline with `{ embed }`. */
internal fun embeds(type: Type): Boolean =
    when (type) {
        is Ref -> type.relation.embed
        is ListOf -> embeds(type.element)
        is MapOf -> embeds(type.value)
        else -> false
    }

/**
 * The verdict for a key field retyped while other models reference it: they write its key, so their
 * own key fields change type with it. [inner] is the verdict on the retyped field itself, which
 * this only raises from compatible to a note or adds to a note, never softens a break.
 */
internal fun referencedKey(change: FieldTypeChanged, ctx: ChangeContext, inner: Verdict): Verdict {
    if (inner is Verdict.Breaking) return inner
    val record = change.record
    if (!(change.to.key || change.to.name in record.compositeKey)) return inner
    val count = ctx.referencingModels(record.qualifiedName)
    if (count == 0) return inner
    val what = if (count == 1) "1 model" else "$count models"
    val message = "referenced by $what; their emitted key fields change with it"
    val help = "regenerate the code and schemas of the referencing models along with this one"
    return when (inner) {
        is Verdict.Note -> Verdict.Note("${inner.message}; $message", help)
        else -> Verdict.Note(message, help)
    }
}
