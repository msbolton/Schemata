package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.Refinements
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.TypeExpr
import java.math.BigDecimal

/**
 * Validates the parenthesised refinements written on one type expression, which only `decimal(p,
 * s)` takes, and reads the bound an option sets. Returns null after reporting, so a bad refinement
 * drops the type like any other resolution failure.
 */
object RefinementChecker {

    /**
     * How a `min`/`max` literal is read for a given type: [range] bounds an integer type, [floats]
     * a binary floating-point one.
     */
    private enum class Bound(val range: LongRange? = null, val floats: FloatRange? = null) {
        INT32(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()),
        INT64(Long.MIN_VALUE..Long.MAX_VALUE),
        FLOAT32(floats = FloatRange.FLOAT32),
        FLOAT64(floats = FloatRange.FLOAT64),
        REAL,
        COUNT(0L..Long.MAX_VALUE),
    }

    fun scalar(
        builtin: Builtin,
        expr: TypeExpr,
        diagnostics: MutableList<Diagnostic>,
    ): Refinements? {
        val positional = expr.refinements.filterIsInstance<Refinement.Positional>()
        var ok = true
        var precision: Int? = null
        var scale: Int? = null
        if (builtin == Builtin.DECIMAL) {
            if (positional.size != 2) {
                report(
                    CoreCodes.MISSING_REFINEMENT,
                    "decimal needs precision and scale",
                    expr.nameSpan,
                    diagnostics,
                    help = "write `decimal(p, s)`, for example `decimal(19, 4)`",
                )
                return null
            }
            val p = smallInt(positional[0], "precision", diagnostics)
            val s = smallInt(positional[1], "scale", diagnostics)
            if (p == null || s == null) return null
            if (p < 1) {
                report(
                    CoreCodes.INVALID_REFINEMENT,
                    "precision must be at least 1",
                    positional[0].span,
                    diagnostics,
                    help = "write `decimal(p, s)` with p ≥ 1",
                )
                ok = false
            }
            if (s < 0) {
                report(
                    CoreCodes.INVALID_REFINEMENT,
                    "scale must not be negative",
                    positional[1].span,
                    diagnostics,
                    help = "use a scale of 0 or more",
                )
                ok = false
            }
            if (s > p) {
                report(
                    CoreCodes.INVALID_REFINEMENT,
                    "scale $s exceeds precision $p",
                    positional[1].span,
                    diagnostics,
                    help = "use a scale no larger than the precision",
                )
                ok = false
            }
            precision = p
            scale = s
        } else {
            positional.forEach {
                report(
                    CoreCodes.INVALID_REFINEMENT,
                    "only decimal takes positional refinements",
                    it.span,
                    diagnostics,
                    help = "write the bound as an option: `${builtin.typeName} { max … }`",
                )
                ok = false
            }
        }
        if (!ok) return null
        return Refinements(precision = precision, scale = scale)
    }

    /**
     * A numeric type is bounded within its own range; every other type (a string's or bytes'
     * length, a collection's size, or no builtin at all) by a non-negative count.
     */
    private fun boundOf(builtin: Builtin?): Bound =
        when (builtin) {
            Builtin.INT32 -> Bound.INT32
            Builtin.INT64 -> Bound.INT64
            Builtin.FLOAT32 -> Bound.FLOAT32
            Builtin.FLOAT64 -> Bound.FLOAT64
            Builtin.DECIMAL -> Bound.REAL
            else -> Bound.COUNT
        }

    /**
     * Reads the value of a bounding option (`min`, `max`, `minItems`, `maxItems`) as the limit it
     * sets on [builtin], or on a collection's size when [builtin] is null; [typeName] names the
     * bounded type in messages. Returns null after reporting, as a refinement's bound does.
     */
    internal fun optionBound(
        name: String,
        value: Literal,
        span: Span,
        builtin: Builtin?,
        typeName: String,
        diagnostics: MutableList<Diagnostic>,
    ): BigDecimal? = bound(name, value, span, boundOf(builtin), typeName, diagnostics)

    /** [kind] is `list` or `map`; neither takes a parenthesised refinement. */
    fun collection(
        kind: String,
        expr: TypeExpr,
        diagnostics: MutableList<Diagnostic>,
    ): Refinements? {
        var ok = true
        expr.refinements.filterIsInstance<Refinement.Positional>().forEach {
            report(
                CoreCodes.INVALID_REFINEMENT,
                "only decimal takes positional refinements",
                it.span,
                diagnostics,
                help = "write the bound as an option: `{ maxItems … }`",
            )
            ok = false
        }
        return if (ok) Refinements.NONE else null
    }

    private fun bound(
        name: String,
        value: Literal,
        span: Span,
        bound: Bound,
        typeName: String,
        diagnostics: MutableList<Diagnostic>,
    ): BigDecimal? {
        return when (bound) {
            Bound.INT32,
            Bound.INT64 ->
                when {
                    value !is Literal.IntLit -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${name} for $typeName must be an integer literal",
                            span,
                            diagnostics,
                            help = "write a whole number",
                        )
                        null
                    }
                    value.value !in bound.range!! -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${name} ${value.value} is outside the range of $typeName",
                            span,
                            diagnostics,
                            help =
                                "use a value between ${bound.range.first} and ${bound.range.last}",
                        )
                        null
                    }
                    else -> BigDecimal.valueOf(value.value)
                }
            Bound.FLOAT32,
            Bound.FLOAT64,
            Bound.REAL -> {
                val number =
                    when (value) {
                        is Literal.IntLit -> BigDecimal.valueOf(value.value)
                        is Literal.FloatLit -> BigDecimal(value.text)
                        else -> {
                            report(
                                CoreCodes.INVALID_REFINEMENT,
                                "${name} for $typeName must be a number",
                                span,
                                diagnostics,
                                help = "write a number",
                            )
                            return null
                        }
                    }
                val floats = bound.floats
                if (floats != null && !floats.contains(number)) {
                    report(
                        CoreCodes.INVALID_REFINEMENT,
                        "${name} ${number.toPlainString()} is outside the range of $typeName (${floats.shown})",
                        span,
                        diagnostics,
                        help = "use a value between ${floats.between}",
                    )
                    return null
                }
                number
            }
            Bound.COUNT ->
                when {
                    value !is Literal.IntLit -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${name} must be an integer literal",
                            span,
                            diagnostics,
                            help = "write a whole number",
                        )
                        null
                    }
                    value.value < 0 -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${name} must not be negative",
                            span,
                            diagnostics,
                            help = "use 0 or more",
                        )
                        null
                    }
                    else -> BigDecimal.valueOf(value.value)
                }
        }
    }

    private fun smallInt(
        item: Refinement,
        what: String,
        diagnostics: MutableList<Diagnostic>,
    ): Int? {
        val literal = item.value as? Literal.IntLit
        if (literal == null || literal.value !in Int.MIN_VALUE..Int.MAX_VALUE) {
            report(
                CoreCodes.INVALID_REFINEMENT,
                "$what must be an integer literal",
                item.span,
                diagnostics,
                help = "write a whole number",
            )
            return null
        }
        return literal.value.toInt()
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
