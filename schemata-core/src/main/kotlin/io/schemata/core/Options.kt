package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.Option
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl
import java.math.BigDecimal
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** What a `{ … }` block may say about a field or a type argument, and what it lowers to. */
object Options {
    val FIELD_FLAGS = setOf("id", "unique", "index", "embed")
    val BOUNDS = setOf("min", "max", "minItems", "maxItems", "match")

    /**
     * [own] bounds the type itself: a scalar's `min`/`max`/`match`, or a list's or map's
     * `minItems`/`maxItems`. [element] bounds a list's elements, from `min`/`max`/`match` written
     * on the list. The flags are the four field options; an option reported as an error sets
     * nothing.
     */
    data class Lowered(
        val own: Refinements,
        val element: Refinements,
        val key: Boolean,
        val unique: Boolean,
        val index: Boolean,
        val embed: Boolean,
    ) {
        companion object {
            val NONE = Lowered(Refinements.NONE, Refinements.NONE, false, false, false, false)
        }
    }

    /**
     * What a resolved type is, as far as options care. For a list written `T[]` or `list<T>` it is
     * the element's kind; the list itself is known from the [TypeExpr]. [SHAPE] is a record hoisted
     * from an inline shape; [LIST] and [MAP] otherwise stand for a collection the expression does
     * not spell as one (an alias to a list, or the element of a list of lists).
     */
    enum class ResolvedKind {
        STRING,
        NUMBER,
        OTHER_SCALAR,
        ENUM,
        RECORD,
        UNION,
        LIST,
        MAP,
        SHAPE,
    }

    // A key column holds one plain value.
    private val keyable =
        setOf(
            ResolvedKind.STRING,
            ResolvedKind.NUMBER,
            ResolvedKind.OTHER_SCALAR,
            ResolvedKind.ENUM,
        )

    // A reference to a record is its key's columns, so it can be unique or indexed as well.
    private val constrainable = keyable + ResolvedKind.RECORD

    private val embeddable = setOf(ResolvedKind.RECORD, ResolvedKind.UNION, ResolvedKind.SHAPE)

    private val numeric =
        setOf(Builtin.INT32, Builtin.INT64, Builtin.FLOAT32, Builtin.FLOAT64, Builtin.DECIMAL)

    /**
     * Lowers [options] written on a field of type [type]: returns the refinements to merge into the
     * type (or its element, for a list) and the flags, reporting SCH1049 for an option the type
     * cannot carry. `min`/`max` on a list constrain its elements; `minItems`/`maxItems` the list or
     * map itself; `match` needs a string; `id` needs a scalar or enum (not a list, map, or shape).
     */
    fun lower(
        options: List<Option>,
        type: TypeExpr,
        resolvedKind: ResolvedKind,
        report: (Diagnostic) -> Unit,
    ): Lowered = lower(options, type, resolvedKind, null, report)

    /**
     * As above, with [builtin] the scalar [resolvedKind] describes, when it is one: a bound is then
     * read within that builtin's range, and `min`/`max` apply only where the builtin takes them (a
     * string's or bytes' length, a number's value). Without it a number is bounded as a decimal.
     */
    fun lower(
        options: List<Option>,
        type: TypeExpr,
        resolvedKind: ResolvedKind,
        builtin: Builtin?,
        report: (Diagnostic) -> Unit,
    ): Lowered {
        val listed = listed(type)
        val collection =
            when {
                listed || resolvedKind == ResolvedKind.LIST -> "list"
                type.name == "map" || resolvedKind == ResolvedKind.MAP -> "map"
                else -> null
            }
        // What `min`, `max`, `match`, and `embed` speak of: a list's element, else the type itself;
        // nothing for a map or a collection not spelled as one.
        val subject = if (listed || collection == null) resolvedKind else null
        val subjectName = describe(resolvedKind, builtin)
        val ownName = if (collection != null) "a $collection" else subjectName
        val own = Bounds()
        val element = Bounds()
        var key = false
        var unique = false
        var index = false
        var embed = false
        val seen = mutableSetOf<String>()
        for (option in options) {
            if (!seen.add(option.name)) {
                report(
                    Diagnostic(
                        CoreCodes.DUPLICATE_REFINEMENT,
                        "option '${option.name}' is given more than once",
                        option.span,
                        help = "keep one of them",
                    )
                )
                continue
            }
            when (option.name) {
                in FIELD_FLAGS -> {
                    val (fits, needs) =
                        when (option.name) {
                            "id" ->
                                (collection == null && resolvedKind in keyable) to
                                    "a scalar or an enum"
                            "embed" -> (subject in embeddable) to "a record or a union"
                            else ->
                                (collection == null && resolvedKind in constrainable) to
                                    "a scalar, an enum, or a record"
                        }
                    if (!fits) {
                        val on = if (option.name == "embed" && listed) subjectName else ownName
                        report(misplaced(option, on, needs))
                        continue
                    }
                    if (option.value != null) {
                        report(
                            invalid(
                                "option '${option.name}' takes no value",
                                option.span,
                                "write `{ ${option.name} }`",
                            )
                        )
                        continue
                    }
                    when (option.name) {
                        "id" -> key = true
                        "unique" -> unique = true
                        "index" -> index = true
                        "embed" -> embed = true
                    }
                }
                "min",
                "max",
                "match" -> {
                    val refinement = if (option.name == "match") "pattern" else option.name
                    // A builtin knows which of its bounds exist: bytes take a length, uuid none.
                    val fits =
                        when {
                            subject == null -> false
                            builtin != null -> refinement in builtin.refinementKeys
                            option.name == "match" -> subject == ResolvedKind.STRING
                            else -> subject == ResolvedKind.STRING || subject == ResolvedKind.NUMBER
                        }
                    if (!fits) {
                        val needs =
                            if (option.name == "match") "a string"
                            else "a string, bytes, or a number"
                        report(
                            misplaced(option, if (subject == null) ownName else subjectName, needs)
                        )
                        continue
                    }
                    val into = if (listed) element else own
                    val value =
                        option.value
                            ?: run {
                                report(needsValue(option))
                                null
                            }
                            ?: continue
                    if (option.name == "match") {
                        into.pattern = pattern(option, value, report) ?: continue
                    } else {
                        val bounded =
                            builtin ?: if (subject == ResolvedKind.NUMBER) Builtin.DECIMAL else null
                        val typeName = bounded?.typeName ?: "string"
                        val number = bound(option, value, bounded, typeName, report) ?: continue
                        into.set(option, number)
                    }
                }
                "minItems",
                "maxItems" -> {
                    if (collection == null) {
                        report(misplaced(option, ownName, "a list or a map"))
                        continue
                    }
                    val value =
                        option.value
                            ?: run {
                                report(needsValue(option))
                                null
                            }
                            ?: continue
                    val number = bound(option, value, null, collection, report) ?: continue
                    own.set(option, number)
                }
                else ->
                    report(
                        Diagnostic(
                            CoreCodes.OPTION_NOT_APPLICABLE,
                            "unknown option '${option.name}'; options: ${(FIELD_FLAGS + BOUNDS).sorted().joinToString(", ")}",
                            option.span,
                            help = "write one of the listed options, or remove it",
                        )
                    )
            }
        }
        return Lowered(own.check(report), element.check(report), key, unique, index, embed)
    }

    /**
     * Classifies a resolved [type] written as [expr] for [lower]: the element of a list the
     * expression spells as one, else the type itself. [find] looks a referenced declaration up.
     */
    fun classify(
        type: Type,
        expr: TypeExpr,
        find: (QualifiedName) -> Declaration?,
    ): Pair<ResolvedKind, Builtin?> {
        val subject = if (listed(expr) && type is ListOf) type.element else type
        return when (subject) {
            is Scalar ->
                when (subject.builtin) {
                    Builtin.STRING -> ResolvedKind.STRING
                    in numeric -> ResolvedKind.NUMBER
                    else -> ResolvedKind.OTHER_SCALAR
                } to subject.builtin
            is ListOf -> ResolvedKind.LIST to null
            is MapOf -> ResolvedKind.MAP to null
            is Ref ->
                when (find(subject.target)) {
                    is EnumDecl -> ResolvedKind.ENUM
                    is UnionDecl -> ResolvedKind.UNION
                    else ->
                        if (expr.inlineShape != null) ResolvedKind.SHAPE else ResolvedKind.RECORD
                } to null
        }
    }

    /**
     * Lowers the [options] written on [expr], which resolved to [resolved], and returns the type
     * with their bounds merged in alongside what was lowered. A transparent alias is refined where
     * it is declared, never at a use, so a bound written on one is reported instead.
     */
    internal fun refine(
        options: List<Option>,
        expr: TypeExpr,
        resolved: Resolved,
        find: (QualifiedName) -> Declaration?,
        report: (Diagnostic) -> Unit,
    ): Pair<Resolved, Lowered> {
        if (options.isEmpty()) return resolved to Lowered.NONE
        val alias = resolved.aliasName
        val usable =
            if (alias == null) options
            else
                options.filter { option ->
                    val bound = option.name in BOUNDS
                    if (bound)
                        report(
                            Diagnostic(
                                CoreCodes.OPTION_NOT_APPLICABLE,
                                "'$alias' is an alias and takes no option '${option.name}' here",
                                option.span,
                                help =
                                    "write the option where the alias is declared, or write the builtin type here",
                            )
                        )
                    !bound
                }
        val (kind, builtin) = classify(resolved.type, expr, find)
        val lowered = lower(usable, expr, kind, builtin, report)
        return resolved.copy(type = merge(resolved.type, expr, lowered)) to lowered
    }

    private fun merge(type: Type, expr: TypeExpr, lowered: Lowered): Type =
        when (type) {
            is Scalar -> type.copy(refinements = type.refinements.with(lowered.own))
            is ListOf ->
                type.copy(
                    refinements = type.refinements.with(lowered.own),
                    element =
                        if (listed(expr) && type.element is Scalar)
                            type.element.copy(
                                refinements = type.element.refinements.with(lowered.element)
                            )
                        else type.element,
                )
            is MapOf -> type.copy(refinements = type.refinements.with(lowered.own))
            is Ref -> type
        }

    private fun Refinements.with(other: Refinements): Refinements =
        copy(min = other.min ?: min, max = other.max ?: max, pattern = other.pattern ?: pattern)

    private fun listed(type: TypeExpr): Boolean = type.list || type.name == "list"

    private fun describe(kind: ResolvedKind, builtin: Builtin?): String =
        when (kind) {
            ResolvedKind.STRING,
            ResolvedKind.NUMBER,
            ResolvedKind.OTHER_SCALAR -> builtin?.typeName ?: "a scalar"
            ResolvedKind.ENUM -> "an enum"
            ResolvedKind.RECORD -> "a record"
            ResolvedKind.UNION -> "a union"
            ResolvedKind.LIST -> "a list"
            ResolvedKind.MAP -> "a map"
            ResolvedKind.SHAPE -> "an inline shape"
        }

    /** The bounds one side of [lower] collects: the type's own, or its elements'. */
    private class Bounds {
        var min: Option? = null
        var max: Option? = null
        var low: BigDecimal? = null
        var high: BigDecimal? = null
        var last: Option? = null
        var pattern: String? = null

        fun set(option: Option, value: BigDecimal) {
            if (option.name.startsWith("min")) {
                min = option
                low = value
            } else {
                max = option
                high = value
            }
            last = option
        }

        /** The refinements, with an inverted pair reported and dropped. */
        fun check(report: (Diagnostic) -> Unit): Refinements {
            val low = low
            val high = high
            if (low != null && high != null && low > high) {
                report(
                    invalid(
                        "${min!!.name} ${low.toPlainString()} exceeds ${max!!.name} ${high.toPlainString()}",
                        last!!.span,
                        "swap or fix the bounds",
                    )
                )
                return Refinements(pattern = pattern)
            }
            return Refinements(min = low, max = high, pattern = pattern)
        }
    }

    private fun bound(
        option: Option,
        value: Literal,
        builtin: Builtin?,
        typeName: String,
        report: (Diagnostic) -> Unit,
    ): BigDecimal? {
        val found = mutableListOf<Diagnostic>()
        val number =
            RefinementChecker.optionBound(option.name, value, option.span, builtin, typeName, found)
        found.forEach(report)
        return number
    }

    private fun pattern(option: Option, value: Literal, report: (Diagnostic) -> Unit): String? {
        val help = "write the pattern as a string: `{ match \"^…\$\" }`"
        if (value !is Literal.StringLit) {
            report(invalid("match must be a string literal", option.span, help))
            return null
        }
        return try {
            Pattern.compile(value.value)
            value.value
        } catch (e: PatternSyntaxException) {
            report(
                invalid(
                    "match does not compile: ${e.description}",
                    option.span,
                    "fix the regular expression; Postgres and Java must both accept it, so keep to the shared subset",
                )
            )
            null
        }
    }

    private fun misplaced(option: Option, on: String, needs: String) =
        Diagnostic(
            CoreCodes.OPTION_NOT_APPLICABLE,
            "option '${option.name}' does not apply to $on; it needs $needs",
            option.span,
            help = "remove the option, or move it to the element type",
        )

    private fun needsValue(option: Option): Diagnostic {
        val example = if (option.name == "match") "\"^…\$\"" else "10"
        return invalid(
            "option '${option.name}' needs a value",
            option.span,
            "write `{ ${option.name} $example }`",
        )
    }

    private fun invalid(message: String, span: Span, help: String) =
        error(CoreCodes.INVALID_REFINEMENT, message, span, help)

    private fun error(code: DiagnosticCode, message: String, span: Span, help: String) =
        Diagnostic(code, message, span, help)
}
