package io.schemata.core

import io.schemata.core.ir.Reserved
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import io.schemata.lang.ast.ReservedItem

/** Ordinal assignment and validation shared by records, enums, and unions. */
object Ordinals {
    /**
     * [ordinal] is the one written, if any. [chosen] is one the compiler picked for an element it
     * appended to a body of implicit ordinals (`@@timestamps`' fields): it stands in for the
     * element's position without counting as written.
     */
    data class Element(
        val ordinal: Int?,
        val ordinalSpan: Span?,
        val name: String,
        val nameSpan: Span,
        val chosen: Int? = null,
    )

    /**
     * The smallest positive ordinal in neither [used] nor any range in [reserved]. Sweeps the
     * ranges once in order of their start, jumping to the end of one instead of counting through
     * it, so a huge range costs one step, not one per ordinal; stops at [Int.MAX_VALUE] instead of
     * overflowing past it.
     */
    fun nextFree(used: Set<Int>, reserved: List<IntRange>): Int {
        val sorted = reserved.filterNot { it.isEmpty() }.sortedBy { it.first }
        var next = 0
        var candidate = 1
        while (true) {
            // the candidate only grows, so a range it has passed is never looked at again
            while (next < sorted.size && sorted[next].first <= candidate) {
                val range = sorted[next++]
                if (candidate <= range.last) {
                    if (range.last == Int.MAX_VALUE) return Int.MAX_VALUE
                    candidate = range.last + 1
                }
            }
            if (candidate !in used) return candidate
            if (candidate == Int.MAX_VALUE) return Int.MAX_VALUE
            candidate++
        }
    }

    /**
     * A reserved name is a former field, operation, or enum value name, so it must be lower_snake
     * too; [naming] is the code that reports one that is not (SCH1003 for a record or a service,
     * SCH1028 for an enum).
     */
    fun reserved(
        items: List<ReservedItem>,
        naming: DiagnosticCode,
        diagnostics: MutableList<Diagnostic>,
    ): Reserved {
        val ordinals = mutableListOf<IntRange>()
        val names = mutableSetOf<String>()
        items.forEach { item ->
            when (item) {
                is ReservedItem.Ordinals ->
                    if (item.from > item.to) {
                        diagnostics +=
                            Diagnostic(
                                CoreCodes.RESERVED_RANGE,
                                "reserved range #${item.from}..#${item.to} is inverted",
                                item.span,
                                help =
                                    "write the lower ordinal first: `reserved #${item.to}..#${item.from}`",
                            )
                    } else {
                        ordinals += item.from..item.to
                    }
                is ReservedItem.Name -> {
                    if (!Analyzer.lowerSnake.matches(item.name)) {
                        val suggestion = Suggest.example(item.name, Suggest.lowerSnake(item.name))
                        diagnostics +=
                            Diagnostic(
                                naming,
                                "reserved name '${item.name}' must be lower_snake",
                                item.span,
                                help =
                                    suggestion?.let { "rename it `$it`" }
                                        ?: "rename it in lower_snake",
                            )
                    }
                    names += item.name
                }
            }
        }
        return Reserved(ordinals, names)
    }

    /**
     * Returns one ordinal per element: the explicit `#n` when every element has one, else
     * declaration order. Mixing, duplicates, non-positive ordinals, reserved conflicts, and (under
     * `--strict`) implicit ordinals are reported; the returned list is still complete so lowering
     * can continue for diagnostics' sake.
     */
    fun assign(
        kind: String,
        elementKind: String,
        name: String,
        nameSpan: Span,
        elements: List<Element>,
        reserved: Reserved,
        options: AnalysisOptions,
        diagnostics: MutableList<Diagnostic>,
    ): List<Int> {
        val explicit = elements.count { it.ordinal != null }
        if (explicit != 0 && explicit != elements.size) {
            diagnostics +=
                Diagnostic(
                    CoreCodes.MIXED_ORDINALS,
                    "$kind '$name' mixes explicit and implicit ordinals",
                    nameSpan,
                    help = "write `#n` on every element or on none",
                )
        }
        if (options.strictOrdinals) {
            elements
                .filter { it.ordinal == null }
                .forEach {
                    diagnostics +=
                        Diagnostic(
                            CoreCodes.IMPLICIT_ORDINAL_STRICT,
                            "$elementKind '${it.name}' has no explicit ordinal (--strict)",
                            it.nameSpan,
                            help =
                                "write `#n` before every field and enum value, starting at #1 in declaration order",
                        )
                }
        }
        val allExplicit = elements.mapNotNull { it.ordinal }.toSet()
        // a chosen ordinal's help must also step over the positions the other elements hold
        val held =
            allExplicit +
                elements.indices.filter { elements[it].chosen == null }.map { it + 1 } +
                elements.mapNotNull { it.chosen }
        val seen = mutableSetOf<Int>()
        return elements.mapIndexed { index, element ->
            val ordinal = element.ordinal ?: element.chosen ?: (index + 1)
            val at = element.ordinalSpan ?: element.nameSpan
            if (element.ordinal != null) {
                if (ordinal <= 0)
                    diagnostics +=
                        Diagnostic(
                            CoreCodes.INVALID_ORDINAL,
                            "ordinal #$ordinal is not positive",
                            at,
                            help = "ordinals start at #1",
                        )
                if (!seen.add(ordinal))
                    diagnostics +=
                        Diagnostic(
                            CoreCodes.DUPLICATE_ORDINAL,
                            "ordinal #$ordinal is used more than once in $kind '$name'",
                            at,
                            help =
                                "give each element its own ordinal; the next free one is #${nextFree(allExplicit, reserved.ordinals)}",
                        )
            }
            if (element.name in reserved.names) {
                diagnostics +=
                    Diagnostic(
                        CoreCodes.RESERVED_CONFLICT,
                        "name '${element.name}' is reserved in $kind '$name'",
                        element.nameSpan,
                        help =
                            "pick another name; reserved names are kept out of use for old readers",
                    )
            }
            if (ordinal in reserved) {
                diagnostics +=
                    Diagnostic(
                        CoreCodes.RESERVED_CONFLICT,
                        "ordinal #$ordinal is reserved in $kind '$name'",
                        at,
                        help =
                            "pick another ordinal; the next free one is #${nextFree(if (element.chosen != null) held else allExplicit, reserved.ordinals)}",
                    )
            }
            ordinal
        }
    }
}
