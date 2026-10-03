package io.schemata.core

import io.schemata.core.ir.BoolValue
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.IntValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RealValue
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.StringValue
import io.schemata.core.ir.Type
import io.schemata.core.ir.Value
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.UnionDecl
import java.math.BigDecimal
import java.util.regex.Pattern

/**
 * Turns a field's default literal into a [Value] once the field's type is known: the literal must
 * fit the type, name a value of the enum, and satisfy every refinement. Returns null after
 * reporting.
 */
object DefaultChecker {
    fun check(
        literal: Literal,
        type: Type,
        index: DeclarationIndex,
        diagnostics: MutableList<Diagnostic>,
    ): Value? {
        if (literal is Literal.NameLit && literal.name == "null") {
            report(
                CoreCodes.NULL_DEFAULT,
                "default may not be null",
                literal.span,
                diagnostics,
                help =
                    "add `?` to the field's type and drop the default; a nullable field is null when absent",
            )
            return null
        }
        return when (type) {
            is ListOf ->
                reject(
                    "list fields cannot have a default",
                    literal.span,
                    diagnostics,
                    help = "remove the default; an empty list is the absence",
                )
            is MapOf ->
                reject(
                    "map fields cannot have a default",
                    literal.span,
                    diagnostics,
                    help = "remove the default; an empty map is the absence",
                )
            is Ref -> reference(literal, type, index, diagnostics)
            is Scalar -> scalar(literal, type, diagnostics)
        }
    }

    private fun reference(
        literal: Literal,
        type: Ref,
        index: DeclarationIndex,
        diagnostics: MutableList<Diagnostic>,
    ): Value? =
        when (val decl = index.find(type.target)?.decl) {
            is EnumDecl -> {
                val name = literal as? Literal.NameLit
                if (name == null || decl.values.none { it.name == name.name }) {
                    reject(
                        "default for enum '${decl.name}' must be one of: ${decl.values.joinToString(", ") { it.name }}",
                        literal.span,
                        diagnostics,
                        help =
                            "write one of the listed values, for example `= ${decl.values.first().name}`",
                    )
                } else EnumRef(type.target, name.name)
            }
            is RecordDecl ->
                reject(
                    "record fields cannot have a default",
                    literal.span,
                    diagnostics,
                    help = "remove the default; give the record's own fields defaults instead",
                )
            is UnionDecl ->
                reject(
                    "union fields cannot have a default",
                    literal.span,
                    diagnostics,
                    help = "remove the default; a union is chosen by the writer",
                )
            else ->
                reject(
                    "'${type.target.simpleName}' fields cannot have a default",
                    literal.span,
                    diagnostics,
                    help = "remove the default; `${type.target.simpleName}` has no literal form",
                )
        }

    private fun scalar(
        literal: Literal,
        type: Scalar,
        diagnostics: MutableList<Diagnostic>,
    ): Value? {
        val name = type.builtin.typeName
        val r = type.refinements
        return when (type.builtin) {
            Builtin.BOOL ->
                (literal as? Literal.BoolLit)?.let { BoolValue(it.value) }
                    ?: reject(
                        "default for bool must be true or false",
                        literal.span,
                        diagnostics,
                        help = "write `= true` or `= false`",
                    )
            Builtin.INT32,
            Builtin.INT64 -> {
                val lit =
                    literal as? Literal.IntLit
                        ?: return reject(
                            "default for $name must be an integer literal",
                            literal.span,
                            diagnostics,
                            help = "write a whole number",
                        )
                if (type.builtin == Builtin.INT32 && lit.value !in Int.MIN_VALUE..Int.MAX_VALUE)
                    return reject(
                        "default ${lit.value} is outside the range of int32",
                        literal.span,
                        diagnostics,
                        help =
                            "use a value between -2147483648 and 2147483647, or declare the field int64",
                    )
                if (!bounds(BigDecimal.valueOf(lit.value), r, literal.span, diagnostics)) null
                else IntValue(lit.value)
            }
            Builtin.FLOAT32,
            Builtin.FLOAT64,
            Builtin.DECIMAL -> {
                val value =
                    when (literal) {
                        is Literal.IntLit -> BigDecimal.valueOf(literal.value)
                        is Literal.FloatLit -> BigDecimal(literal.text)
                        else ->
                            return reject(
                                "default for $name must be a number",
                                literal.span,
                                diagnostics,
                                help = "write a number",
                            )
                    }
                val range = FloatRange.of(type.builtin)
                if (range != null && !range.contains(value))
                    return reject(
                        "default ${value.toPlainString()} is outside the range of $name (${range.shown})",
                        literal.span,
                        diagnostics,
                        help =
                            "use a value between ${range.between}" +
                                if (range == FloatRange.FLOAT32) ", or declare the field float64"
                                else "",
                    )
                val scale = r.scale
                if (scale != null && maxOf(value.stripTrailingZeros().scale(), 0) > scale)
                    return violates(
                        "default ${value.toPlainString()} exceeds scale $scale",
                        literal.span,
                        diagnostics,
                        help = "use at most $scale decimal places",
                    )
                val precision = r.precision
                if (precision != null && scale != null) {
                    val normalized = value.stripTrailingZeros()
                    val integerDigits = normalized.precision() - normalized.scale()
                    if (integerDigits > precision - scale)
                        return violates(
                            "default ${value.toPlainString()} exceeds precision $precision",
                            literal.span,
                            diagnostics,
                            help = "use at most $precision digits",
                        )
                }
                if (!bounds(value, r, literal.span, diagnostics)) null else RealValue(value)
            }
            Builtin.STRING -> {
                val lit =
                    literal as? Literal.StringLit
                        ?: return reject(
                            "default for string must be a string literal",
                            literal.span,
                            diagnostics,
                            help = "quote the default: `= \"…\"`",
                        )
                val length = lit.value.codePointCount(0, lit.value.length).toBigDecimal()
                when {
                    r.min != null && length < r.min ->
                        violates(
                            "default ${SchemataText.string(lit.value)} is shorter than min ${r.min.toPlainString()}",
                            literal.span,
                            diagnostics,
                            help =
                                "use a default of at least ${r.min.toPlainString()} characters, or lower min",
                        )
                    r.max != null && length > r.max ->
                        violates(
                            "default ${SchemataText.string(lit.value)} is longer than max ${r.max.toPlainString()}",
                            literal.span,
                            diagnostics,
                            help =
                                "use a default of at most ${r.max.toPlainString()} characters, or raise max",
                        )
                    r.pattern != null && !Pattern.compile(r.pattern).matcher(lit.value).find() ->
                        violates(
                            "default ${SchemataText.string(lit.value)} does not match pattern ${SchemataText.pattern(r.pattern)}",
                            literal.span,
                            diagnostics,
                            help = "use a default the pattern accepts, or change the pattern",
                        )
                    else -> StringValue(lit.value)
                }
            }
            Builtin.BYTES,
            Builtin.UUID,
            Builtin.DATE,
            Builtin.TIME,
            Builtin.INSTANT,
            Builtin.DURATION ->
                reject(
                    "$name fields cannot have a default",
                    literal.span,
                    diagnostics,
                    help = "remove the default; `$name` has no literal form",
                )
        }
    }

    /** True when [value] lies within the numeric bounds; reports otherwise. */
    private fun bounds(
        value: BigDecimal,
        r: Refinements,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
    ): Boolean {
        if (r.min != null && value < r.min) {
            violates(
                "default ${value.toPlainString()} is below min ${r.min.toPlainString()}",
                span,
                diagnostics,
                help = "use a default of at least ${r.min.toPlainString()}, or lower min",
            )
            return false
        }
        if (r.max != null && value > r.max) {
            violates(
                "default ${value.toPlainString()} is above max ${r.max.toPlainString()}",
                span,
                diagnostics,
                help = "use a default of at most ${r.max.toPlainString()}, or raise max",
            )
            return false
        }
        return true
    }

    private fun reject(
        message: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
        help: String? = null,
    ): Value? {
        report(CoreCodes.DEFAULT_TYPE, message, span, diagnostics, help)
        return null
    }

    private fun violates(
        message: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
        help: String? = null,
    ): Value? {
        report(CoreCodes.DEFAULT_VIOLATES_REFINEMENT, message, span, diagnostics, help)
        return null
    }

    private fun report(
        code: DiagnosticCode,
        message: String,
        span: Span,
        diagnostics: MutableList<Diagnostic>,
        help: String? = null,
    ) {
        diagnostics += Diagnostic(code, message, span, help)
    }
}
