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
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Validates the refinements written on one type expression and builds its [Refinements]. The names
 * a builtin accepts come from [Builtin.refinementKeys]; collections take `min` and `max` element
 * counts. Returns null after reporting, so a bad refinement drops the type like any other
 * resolution failure.
 */
object RefinementChecker {
    private val collectionKeys = setOf("min", "max")

    /** How a `min`/`max` literal is read for a given type. */
    private enum class Bound(val range: LongRange?) {
        INT32(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()),
        INT64(Long.MIN_VALUE..Long.MAX_VALUE),
        REAL(null),
        COUNT(0L..Long.MAX_VALUE),
    }

    private class Bounds(val min: BigDecimal?, val max: BigDecimal?, val pattern: String?)

    fun scalar(
        builtin: Builtin,
        expr: TypeExpr,
        diagnostics: MutableList<Diagnostic>,
    ): Refinements? {
        val positional = expr.refinements.filterIsInstance<Refinement.Positional>()
        val named = expr.refinements.filterIsInstance<Refinement.Named>()
        var ok = true
        var precision: Int? = null
        var scale: Int? = null
        if (builtin == Builtin.DECIMAL) {
            if (positional.size != 2) {
                report(
                    CoreCodes.MISSING_REFINEMENT,
                    "decimal takes its precision and scale positionally: decimal(p, s)",
                    expr.nameSpan,
                    diagnostics,
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
                )
                ok = false
            }
            if (s < 0) {
                report(
                    CoreCodes.INVALID_REFINEMENT,
                    "scale must not be negative",
                    positional[1].span,
                    diagnostics,
                )
                ok = false
            }
            if (s > p) {
                report(
                    CoreCodes.INVALID_REFINEMENT,
                    "scale $s exceeds precision $p",
                    positional[1].span,
                    diagnostics,
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
                )
                ok = false
            }
        }
        val bound =
            when (builtin) {
                Builtin.INT32 -> Bound.INT32
                Builtin.INT64 -> Bound.INT64
                Builtin.FLOAT32,
                Builtin.FLOAT64,
                Builtin.DECIMAL -> Bound.REAL
                else -> Bound.COUNT
            }
        val values = named(named, builtin.typeName, builtin.refinementKeys, bound, diagnostics)
        if (!ok || values == null) return null
        return Refinements(values.min, values.max, values.pattern, precision, scale)
    }

    /** [kind] is `list` or `map`; both take element-count bounds only. */
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
            )
            ok = false
        }
        val named = expr.refinements.filterIsInstance<Refinement.Named>()
        val values = named(named, kind, collectionKeys, Bound.COUNT, diagnostics)
        if (!ok || values == null) return null
        return Refinements(min = values.min, max = values.max)
    }

    private fun named(
        items: List<Refinement.Named>,
        typeName: String,
        allowed: Set<String>,
        bound: Bound,
        diagnostics: MutableList<Diagnostic>,
    ): Bounds? {
        var ok = true
        val seen = mutableSetOf<String>()
        var min: BigDecimal? = null
        var max: BigDecimal? = null
        var pattern: String? = null
        var lastBound: Refinement.Named? = null
        for (item in items) {
            if (item.name !in allowed) {
                val message =
                    if (allowed.isEmpty())
                        "'${item.name}' is not a refinement; $typeName takes no refinements"
                    else
                        "'${item.name}' is not a refinement of $typeName; allowed: ${allowed.sorted().joinToString(", ")}"
                report(CoreCodes.UNKNOWN_REFINEMENT, message, item.span, diagnostics)
                ok = false
                continue
            }
            if (!seen.add(item.name)) {
                report(
                    CoreCodes.DUPLICATE_REFINEMENT,
                    "'${item.name}' is given more than once",
                    item.span,
                    diagnostics,
                )
                ok = false
                continue
            }
            if (item.name == "pattern") {
                val literal = item.value as? Literal.StringLit
                if (literal == null) {
                    report(
                        CoreCodes.INVALID_REFINEMENT,
                        "pattern must be a string literal",
                        item.span,
                        diagnostics,
                    )
                    ok = false
                    continue
                }
                try {
                    Pattern.compile(literal.value)
                    pattern = literal.value
                } catch (e: PatternSyntaxException) {
                    report(
                        CoreCodes.INVALID_REFINEMENT,
                        "pattern does not compile: ${e.description}",
                        item.span,
                        diagnostics,
                    )
                    ok = false
                }
                continue
            }
            val value = bound(item, bound, typeName, diagnostics)
            if (value == null) {
                ok = false
                continue
            }
            when (item.name) {
                "min" -> min = value
                "max" -> max = value
                else -> error("refinement '${item.name}' is allowed but has no handler")
            }
            lastBound = item
        }
        if (min != null && max != null && min > max) {
            report(
                CoreCodes.INVALID_REFINEMENT,
                "min ${min.toPlainString()} exceeds max ${max.toPlainString()}",
                checkNotNull(lastBound).span,
                diagnostics,
            )
            ok = false
        }
        return if (ok) Bounds(min, max, pattern) else null
    }

    private fun bound(
        item: Refinement.Named,
        bound: Bound,
        typeName: String,
        diagnostics: MutableList<Diagnostic>,
    ): BigDecimal? {
        val value = item.value
        return when (bound) {
            Bound.INT32,
            Bound.INT64 ->
                when {
                    value !is Literal.IntLit -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${item.name} for $typeName must be an integer literal",
                            item.span,
                            diagnostics,
                        )
                        null
                    }
                    value.value !in bound.range!! -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${item.name} ${value.value} is outside the range of $typeName",
                            item.span,
                            diagnostics,
                        )
                        null
                    }
                    else -> BigDecimal.valueOf(value.value)
                }
            Bound.REAL ->
                when (value) {
                    is Literal.IntLit -> BigDecimal.valueOf(value.value)
                    is Literal.FloatLit -> BigDecimal(value.text)
                    else -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${item.name} for $typeName must be a numeric literal",
                            item.span,
                            diagnostics,
                        )
                        null
                    }
                }
            Bound.COUNT ->
                when {
                    value !is Literal.IntLit -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${item.name} must be an integer literal",
                            item.span,
                            diagnostics,
                        )
                        null
                    }
                    value.value < 0 -> {
                        report(
                            CoreCodes.INVALID_REFINEMENT,
                            "${item.name} must not be negative",
                            item.span,
                            diagnostics,
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
    ) {
        diagnostics += Diagnostic(code, message, span)
    }
}
