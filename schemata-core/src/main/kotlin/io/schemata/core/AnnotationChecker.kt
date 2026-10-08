package io.schemata.core

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.AnnotationSpec
import io.schemata.core.annotations.CoreAnnotations
import io.schemata.core.annotations.Element
import io.schemata.core.annotations.ValueKind
import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.Annotations
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue as Written
import io.schemata.lang.ast.Literal

/**
 * Validates the annotations written on one element against the registry and builds the
 * [Annotations] the IR keeps. `@target(args)` carries one key per argument — bare (`key`) or `key =
 * value`; a core key such as `@deprecated` carries at most one positional value.
 */
class AnnotationChecker(
    private val registry: AnnotationRegistry,
    private val diagnostics: MutableList<Diagnostic>,
) {
    fun check(annotations: List<Annotation>, element: Element): Annotations {
        val entries = linkedMapOf<String, MutableMap<String, AnnotationValue>>()
        for (annotation in annotations) {
            when {
                registry.hasTarget(annotation.name) -> {
                    if (annotation.args.isEmpty()) {
                        val targetSpecs = registry.specs(annotation.name)
                        val spec =
                            targetSpecs.firstOrNull { element in it.elements }
                                ?: targetSpecs.first()
                        report(
                            CoreCodes.ANNOTATION_VALUE,
                            "@${annotation.name} needs at least one key",
                            annotation.span,
                            help = writeHelp(annotation.name, spec),
                        )
                    }
                    annotation.args.forEach { targetArg(annotation.name, it, element, entries) }
                }
                annotation.name == CoreAnnotations.RELATION &&
                    registry.find("", annotation.name).isNotEmpty() ->
                    relation(annotation, element, entries)
                registry.find("", annotation.name).isNotEmpty() ->
                    coreKey(annotation, element, entries)
                else ->
                    report(
                        CoreCodes.UNKNOWN_ANNOTATION_TARGET,
                        "unknown annotation '@${annotation.name}'; known: ${registry.names().joinToString(", ")}",
                        annotation.span,
                        help =
                            "write one of the listed annotations, or run `schemata targets` for each target's keys",
                    )
            }
        }
        return if (entries.isEmpty()) Annotations.NONE
        else Annotations(entries.mapValues { it.value.toMap() })
    }

    private fun targetArg(
        target: String,
        arg: AnnotationArg,
        element: Element,
        entries: MutableMap<String, MutableMap<String, AnnotationValue>>,
    ) {
        val (key, written) =
            when (arg) {
                is AnnotationArg.Named -> arg.name to arg.value
                is AnnotationArg.Positional -> {
                    val flag = (arg.value as? Written.Lit)?.literal as? Literal.NameLit
                    if (flag == null) {
                        report(
                            CoreCodes.ANNOTATION_VALUE,
                            "@$target arguments are a bare key or key = value",
                            arg.span,
                            help = "write `@$target(key)` or `@$target(key = value)`",
                        )
                        return
                    }
                    flag.name to null
                }
            }
        apply(target, key, written, arg.span, element, entries, "@$target($key)")
    }

    private fun coreKey(
        annotation: Annotation,
        element: Element,
        entries: MutableMap<String, MutableMap<String, AnnotationValue>>,
    ) {
        val display = "@${annotation.name}"
        val written =
            when (annotation.args.size) {
                0 -> null
                1 -> (annotation.args[0] as? AnnotationArg.Positional)?.value
                else -> null
            }
        if (annotation.args.size > 1 || (annotation.args.size == 1 && written == null)) {
            report(
                CoreCodes.ANNOTATION_VALUE,
                "$display takes a single value",
                annotation.span,
                help = "write `$display(\"value\")`",
            )
            return
        }
        apply("", annotation.name, written, annotation.span, element, entries, display)
    }

    /**
     * `@relation` in one of its two forms. A back-reference names the forward field on the model it
     * references, `@relation(customer)`, or is bare when that model has a single field that can be
     * meant; it is kept as `@relation` with that name, or as a flag. A forward reference says what
     * deleting its target does, `@relation(onDelete: cascade)`; it is kept as the key `onDelete`
     * under the target `relation`. A back-reference stores nothing, so it has no foreign key for
     * `onDelete` to act on: one annotation with both forms is reported here, as a wrong shape.
     */
    private fun relation(
        annotation: Annotation,
        element: Element,
        entries: MutableMap<String, MutableMap<String, AnnotationValue>>,
    ) {
        val display = "@${CoreAnnotations.RELATION}"
        val spec = registry.find("", CoreAnnotations.RELATION).first()
        if (element !in spec.elements) {
            report(
                CoreCodes.ANNOTATION_ELEMENT,
                "$display is not allowed on ${element.article} ${element.displayName}; allowed on: field",
                annotation.span,
                help = "move the annotation to a field, or remove it",
            )
            return
        }
        val usage =
            "write `$display(field)` on a back-reference, or `$display(${CoreAnnotations.ON_DELETE}: cascade)` on a forward reference"
        val positional = annotation.args.filterIsInstance<AnnotationArg.Positional>()
        val named = annotation.args.filterIsInstance<AnnotationArg.Named>()
        val stray = named.firstOrNull { it.name != CoreAnnotations.ON_DELETE }
        val problem: Pair<String, Span>? =
            when {
                stray != null ->
                    "'${stray.name}' is not an argument of $display; it takes a field name or ${CoreAnnotations.ON_DELETE}" to
                        stray.span
                positional.isNotEmpty() && named.isNotEmpty() ->
                    "$display names a forward field or sets ${CoreAnnotations.ON_DELETE}, not both; a back-reference has no foreign key to act on delete" to
                        annotation.span
                positional.size > 1 || named.size > 1 ->
                    "$display takes one argument" to annotation.span
                else -> null
            }
        if (problem != null) {
            report(CoreCodes.ANNOTATION_VALUE, problem.first, problem.second, help = usage)
            return
        }
        val target: String
        val key: String
        val value: AnnotationValue
        if (named.isNotEmpty()) {
            val choice =
                ((named[0].value as? Written.Lit)?.literal as? Literal.NameLit)?.name?.takeIf {
                    it in CoreAnnotations.RELATION_ON_DELETE
                }
            if (choice == null) {
                report(
                    CoreCodes.ANNOTATION_VALUE,
                    "$display(${CoreAnnotations.ON_DELETE}) expects one of: ${CoreAnnotations.RELATION_ON_DELETE.sorted().joinToString(", ")}",
                    named[0].span,
                    help = "write `$display(${CoreAnnotations.ON_DELETE}: cascade)`",
                )
                return
            }
            target = CoreAnnotations.RELATION
            key = CoreAnnotations.ON_DELETE
            value = AnnotationValue.Name(choice)
        } else {
            val name =
                positional.firstOrNull()?.let {
                    ((it.value as? Written.Lit)?.literal as? Literal.NameLit)?.name
                        ?: run {
                            report(
                                CoreCodes.ANNOTATION_VALUE,
                                "$display expects a field name",
                                it.span,
                                help = usage,
                            )
                            return
                        }
                }
            target = ""
            key = CoreAnnotations.RELATION
            value = name?.let { AnnotationValue.Name(it) } ?: AnnotationValue.Flag
        }
        val given =
            CoreAnnotations.RELATION in entries[""].orEmpty() ||
                CoreAnnotations.ON_DELETE in entries[CoreAnnotations.RELATION].orEmpty()
        if (given) {
            report(
                CoreCodes.DUPLICATE_ANNOTATION,
                "$display is given more than once",
                annotation.span,
                help = "keep one of them",
            )
            return
        }
        entries.getOrPut(target) { linkedMapOf() }[key] = value
    }

    private fun apply(
        target: String,
        key: String,
        written: Written?,
        span: Span,
        element: Element,
        entries: MutableMap<String, MutableMap<String, AnnotationValue>>,
        display: String,
    ) {
        val specs = registry.find(target, key)
        if (specs.isEmpty()) {
            val keys = registry.keys(target)
            val targetSpecs = registry.specs(target)
            val suggestion =
                targetSpecs.firstOrNull { element in it.elements } ?: targetSpecs.firstOrNull()
            report(
                CoreCodes.UNKNOWN_ANNOTATION_KEY,
                "'$key' is not a key of @$target; keys: ${keys.joinToString(", ")}",
                span,
                help =
                    if (suggestion == null) "remove the annotation; @$target has no keys"
                    else "write one of the listed keys, for example `@$target(${suggestion.key})`",
            )
            return
        }
        val spec = specs.firstOrNull { element in it.elements }
        if (spec == null) {
            val allowedElements = specs.flatMap { it.elements }.distinct().sortedBy { it.ordinal }
            val allowed = allowedElements.joinToString(", ") { it.displayName }
            val first = allowedElements.first()
            report(
                CoreCodes.ANNOTATION_ELEMENT,
                "$display is not allowed on ${element.article} ${element.displayName}; allowed on: $allowed",
                span,
                help = "move the annotation to ${first.article} ${first.displayName}, or remove it",
            )
            return
        }
        val value = value(spec, written)
        if (value == null) {
            report(
                CoreCodes.ANNOTATION_VALUE,
                "$display ${expected(spec)}",
                span,
                help = writeHelp(target, spec),
            )
            return
        }
        val forTarget = entries.getOrPut(target) { linkedMapOf() }
        if (key in forTarget) {
            report(
                CoreCodes.DUPLICATE_ANNOTATION,
                "$display is given more than once",
                span,
                help = "keep one of them",
            )
            return
        }
        forTarget[key] = value
    }

    private fun value(spec: AnnotationSpec, written: Written?): AnnotationValue? {
        val literal = (written as? Written.Lit)?.literal
        return when (spec.valueKind) {
            ValueKind.FLAG -> if (written == null) AnnotationValue.Flag else null
            ValueKind.STRING ->
                if (written == null && spec.optional) AnnotationValue.Flag
                else (literal as? Literal.StringLit)?.let { AnnotationValue.Str(it.value) }
            ValueKind.INT -> (literal as? Literal.IntLit)?.let { AnnotationValue.Num(it.value) }
            ValueKind.BOOL -> (literal as? Literal.BoolLit)?.let { AnnotationValue.Bool(it.value) }
            ValueKind.NAME ->
                (literal as? Literal.NameLit)
                    ?.takeIf { spec.choices == null || it.name in spec.choices }
                    ?.let { AnnotationValue.Name(it.name) }
            ValueKind.NAME_TUPLE ->
                (written as? Written.Tuple)?.let { AnnotationValue.Names(it.names) }
        }
    }

    private fun expected(spec: AnnotationSpec): String =
        when (spec.valueKind) {
            ValueKind.FLAG -> "takes no value"
            ValueKind.STRING -> "expects a string"
            ValueKind.INT -> "expects an integer"
            ValueKind.BOOL -> "expects true or false"
            ValueKind.NAME ->
                spec.choices?.let { "expects one of: ${it.sorted().joinToString(", ")}" }
                    ?: "expects a name"
            ValueKind.NAME_TUPLE -> "expects a tuple of names: (a, b)"
        }

    /** A "write it like this" help message for writing [spec]'s key under [target]. */
    private fun writeHelp(target: String, spec: AnnotationSpec): String =
        when {
            spec.valueKind == ValueKind.FLAG -> "write `${example(spec)}`"
            target.isEmpty() -> "write `@${spec.key}(${example(spec)})`"
            else -> "write `@$target(${spec.key} = ${example(spec)})`"
        }

    /** An example value for [spec]'s kind, for a "write it like this" help message. */
    private fun example(spec: AnnotationSpec): String {
        spec.choices?.let {
            return it.sorted().first()
        }
        return when (spec.valueKind) {
            ValueKind.FLAG ->
                if (spec.target.isEmpty()) "@${spec.key}" else "@${spec.target}(${spec.key})"
            ValueKind.STRING -> "\"…\""
            ValueKind.INT -> "1"
            ValueKind.BOOL -> "true"
            ValueKind.NAME -> "a"
            ValueKind.NAME_TUPLE -> "(a, b)"
        }
    }

    private fun report(code: DiagnosticCode, message: String, span: Span, help: String? = null) {
        diagnostics += Diagnostic(code, message, span, help)
    }
}
