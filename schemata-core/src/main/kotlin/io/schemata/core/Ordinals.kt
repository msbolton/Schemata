package io.schemata.core

import io.schemata.core.ir.Reserved
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.ast.ReservedItem

/** Ordinal assignment and validation shared by records, enums, and unions. */
object Ordinals {
    data class Element(
        val ordinal: Int?,
        val ordinalSpan: Span?,
        val name: String,
        val nameSpan: Span,
    )

    /** The smallest positive ordinal in neither [used] nor [reserved]. */
    fun nextFree(used: Set<Int>, reserved: Set<Int>): Int =
        generateSequence(1) { it + 1 }.first { it !in used && it !in reserved }

    fun reserved(items: List<ReservedItem>, diagnostics: MutableList<Diagnostic>): Reserved {
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
                is ReservedItem.Name -> names += item.name
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
        // a Set view over the reserved ranges; contains() is a range check, so a huge range never
        // gets materialized
        val reservedOrdinals =
            object : AbstractSet<Int>() {
                override val size: Int
                    get() = reserved.ordinals.sumOf { it.last - it.first + 1 }

                override fun contains(element: Int): Boolean = element in reserved

                override fun iterator(): Iterator<Int> =
                    reserved.ordinals.asSequence().flatMap { it.asSequence() }.iterator()
            }
        val seen = mutableSetOf<Int>()
        return elements.mapIndexed { index, element ->
            val ordinal = element.ordinal ?: (index + 1)
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
                                "give each element its own ordinal; the next free one is #${nextFree(seen, emptySet())}",
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
                            "pick another ordinal; the next free one is #${nextFree(seen, reservedOrdinals)}",
                    )
            }
            ordinal
        }
    }
}
