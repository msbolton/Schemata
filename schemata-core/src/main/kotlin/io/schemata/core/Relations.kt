package io.schemata.core

import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.OnDelete
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.declaresKey
import io.schemata.core.ir.keyFields
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span

/**
 * Turns `@relation` into IR facts once every record is lowered, since a back-reference is judged
 * against the model it references.
 *
 * A field typed as a keyed model (or a list of one) is a forward reference: it stores the target's
 * key, and `@relation(onDelete: cascade | restrict | set_null)` says what deleting the target does;
 * `set_null` needs a reference that may be null. A field carrying `@relation(<name>)`, or a bare
 * `@relation`, is a back-reference: the other end of a forward reference the referenced model
 * already holds. It stores nothing, so it is marked [Field.virtual] and no target emits it; its
 * [Field.backReferenceOf] is the forward field it follows. A bare `@relation` follows the one
 * forward reference the target has back to this model, and must name one when there are several.
 *
 * The `@relation` annotation itself leaves the IR here: what it says lives on [Ref.relation] and
 * the field's own flags.
 */
object Relations {
    fun analyze(schema: Schema, report: (Diagnostic) -> Unit): Schema {
        val pass = Pass(schema, report)
        return Schema(
            schema.namespaces.map { ns -> ns.copy(declarations = ns.declarations.map(pass::decl)) }
        )
    }

    private class Pass(val schema: Schema, val report: (Diagnostic) -> Unit) {
        fun decl(decl: TypeDecl): TypeDecl =
            when (decl) {
                is RecordType -> record(decl)
                else -> decl
            }

        private fun record(record: RecordType): RecordType {
            val fields = record.fields.map { field(record, it) }
            modelLists(record, fields)
            fields.filterNot { it.virtual }.forEach { reservedReference(record, it) }
            return record.copy(fields = fields, nested = record.nested.map(::decl))
        }

        /**
         * A reference to a model with one key field is sent as `<field>_<key>`, so that name must
         * not be one the model keeps out of use: old readers still know it as the retired field.
         */
        private fun reservedReference(record: RecordType, field: Field) {
            val ref = field.type as? Ref ?: return
            if (ref.relation.embed) return
            val target = schema.lookupOrNull(ref.target) as? RecordType ?: return
            val key = target.keyFields().singleOrNull() ?: return
            val sent = "${field.name}_${key.name}"
            if (sent in record.reserved.names)
                error(
                    CoreCodes.RESERVED_CONFLICT,
                    "field '${record.name}.${field.name}' is sent by key as '$sent', which is reserved in model '${record.name}'",
                    field.nameSpan,
                    "rename the field, or write `{ embed }` to send the whole record",
                )
        }

        private fun field(record: RecordType, field: Field): Field {
            val back = field.annotations[""][CoreAnnotations.RELATION]
            val onDelete =
                (field.annotations[CoreAnnotations.RELATION][CoreAnnotations.ON_DELETE]
                        as? AnnotationValue.Name)
                    ?.value
            if (back == null && onDelete == null) return field
            val stripped = field.copy(annotations = withoutRelation(field.annotations))
            val target = referenced(field.type)
            if (target == null) {
                error(
                    CoreCodes.ANNOTATION_ELEMENT,
                    "@relation is on field '${record.name}.${field.name}', which does not reference a keyed model",
                    field.nameSpan,
                    "remove the annotation, or give the referenced model a key with `{ id }` or `@@id(a, b)`",
                )
                return stripped
            }
            return if (onDelete != null) forward(record, stripped, onDelete)
            else backReference(record, stripped, target, (back as? AnnotationValue.Name)?.value)
        }

        /** A forward reference with `@relation(onDelete: …)`: the choice goes on its [Ref]. */
        private fun forward(record: RecordType, field: Field, written: String): Field {
            val onDelete =
                when (written) {
                    "cascade" -> OnDelete.CASCADE
                    "set_null" -> OnDelete.SET_NULL
                    else -> OnDelete.RESTRICT
                }
            val type = field.type
            val nullable = if (type is ListOf) type.nullableElement else field.nullable
            if (onDelete == OnDelete.SET_NULL && !nullable) {
                val what = if (type is ListOf) "its elements are" else "it is"
                error(
                    CoreCodes.SET_NULL_REQUIRED,
                    "field '${record.name}.${field.name}' sets itself null when its target is deleted, but $what required",
                    field.nameSpan,
                    "make the reference nullable or choose restrict",
                )
            }
            return field.copy(type = withOnDelete(type, onDelete))
        }

        /**
         * A back-reference to [target]: [name] is the forward field it follows, or null to follow
         * the only one [target] has back to [record].
         */
        private fun backReference(
            record: RecordType,
            field: Field,
            target: RecordType,
            name: String?,
        ): Field {
            val help = "name a field of ${target.name} typed ${record.name}"
            val forwardName =
                if (name != null) {
                    val forward = target.fields.firstOrNull { it.name == name }
                    val problem =
                        when {
                            forward == null -> "model '${target.name}' has no field '$name'"
                            isBackReference(forward) ->
                                "'${target.name}.$name' is itself a back-reference"
                            referenced(forward.type)?.qualifiedName != record.qualifiedName ->
                                "'${target.name}.$name' does not reference '${record.name}'"
                            else -> null
                        }
                    if (problem != null) {
                        error(
                            CoreCodes.BACK_REFERENCE_TARGET,
                            "field '${record.name}.${field.name}' follows '$name' back, but $problem",
                            field.nameSpan,
                            help,
                        )
                    }
                    name
                } else {
                    val candidates = forwardReferences(target, record.qualifiedName)
                    when (candidates.size) {
                        1 -> candidates.single().name
                        0 -> {
                            error(
                                CoreCodes.BACK_REFERENCE_TARGET,
                                "field '${record.name}.${field.name}' is a back-reference, but model '${target.name}' has no field referencing '${record.name}'",
                                field.nameSpan,
                                help,
                            )
                            null
                        }
                        else -> {
                            val names = candidates.map { it.name }
                            error(
                                CoreCodes.AMBIGUOUS_BACK_REFERENCE,
                                "field '${record.name}.${field.name}' could follow any of ${names.joinToString(", ") { "'${target.name}.$it'" }} back",
                                field.nameSpan,
                                "add @relation(…) naming one of ${names.joinToString(", ")}",
                            )
                            null
                        }
                    }
                }
            storedOptions(record, field)
            return field.copy(virtual = true, backReferenceOf = forwardName)
        }

        /**
         * A back-reference stores nothing, so an option about its column or its copy (`unique`,
         * `index`, `embed`) has nothing to act on.
         */
        private fun storedOptions(record: RecordType, field: Field) {
            val embed =
                when (val type = field.type) {
                    is Ref -> type.relation.embed
                    is ListOf -> (type.element as? Ref)?.relation?.embed == true
                    else -> false
                }
            listOfNotNull(
                    "unique".takeIf { field.unique },
                    "index".takeIf { field.index },
                    "embed".takeIf { embed },
                )
                .forEach {
                    error(
                        CoreCodes.OPTION_NOT_APPLICABLE,
                        "option '$it' does not apply to back-reference '${record.name}.${field.name}', which stores nothing",
                        field.nameSpan,
                        "move the option to the forward reference, or remove it",
                    )
                }
        }

        /** `@@id`, `@@unique`, and `@@index` name stored fields; a back-reference has no column. */
        private fun modelLists(record: RecordType, fields: List<Field>) {
            val virtual = fields.filter { it.virtual }.map { it.name }.toSet()
            if (virtual.isEmpty()) return
            val lists =
                listOf("@@id" to listOf(record.compositeKey)) +
                    listOf("@@unique" to record.uniques, "@@index" to record.indexes)
            lists.forEach { (display, groups) ->
                groups
                    .flatten()
                    .distinct()
                    .filter { it in virtual }
                    .forEach {
                        error(
                            CoreCodes.ANNOTATION_VALUE,
                            "$display names '$it', a back-reference of model '${record.name}', which stores nothing",
                            record.nameSpan,
                            "name stored fields only",
                        )
                    }
            }
        }

        /** Fields of [model] that reference [record] and are not back-references themselves. */
        private fun forwardReferences(model: RecordType, record: QualifiedName): List<Field> =
            model.fields.filter {
                !isBackReference(it) && referenced(it.type)?.qualifiedName == record
            }

        /** The keyed model [type] references directly or as a list's element, if any. */
        private fun referenced(type: Type): RecordType? {
            val ref = (if (type is ListOf) type.element else type) as? Ref ?: return null
            val record = schema.lookupOrNull(ref.target) as? RecordType ?: return null
            return record.takeIf { it.declaresKey() }
        }

        private fun isBackReference(field: Field): Boolean =
            field.virtual ||
                (CoreAnnotations.RELATION in field.annotations[""] &&
                    CoreAnnotations.ON_DELETE !in field.annotations[CoreAnnotations.RELATION])

        private fun withOnDelete(type: Type, onDelete: OnDelete): Type =
            when (type) {
                is Ref -> type.copy(relation = type.relation.copy(onDelete = onDelete))
                is ListOf -> type.copy(element = withOnDelete(type.element, onDelete))
                else -> type
            }

        private fun withoutRelation(annotations: Annotations): Annotations {
            val entries =
                (annotations.entries - CoreAnnotations.RELATION)
                    .mapValues { (target, keys) ->
                        if (target == "") keys - CoreAnnotations.RELATION else keys
                    }
                    .filterValues { it.isNotEmpty() }
            return if (entries.isEmpty()) Annotations.NONE else Annotations(entries)
        }

        private fun error(code: DiagnosticCode, message: String, span: Span, help: String) {
            report(Diagnostic(code, message, span, help))
        }
    }
}
