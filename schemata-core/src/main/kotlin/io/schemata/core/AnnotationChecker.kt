package io.schemata.core

import io.schemata.core.annotations.AnnotationRegistry
import io.schemata.core.annotations.AnnotationSpec
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
