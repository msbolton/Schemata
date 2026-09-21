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

    fun reserved(items: List<ReservedItem>, diagnostics: MutableList<Diagnostic>): Reserved {
        val ordinals = mutableSetOf<Int>()
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
                        )
                }
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
                        )
                if (!seen.add(ordinal))
                    diagnostics +=
                        Diagnostic(
                            CoreCodes.DUPLICATE_ORDINAL,
                            "ordinal #$ordinal is used more than once in $kind '$name'",
                            at,
                        )
            }
            if (element.name in reserved.names) {
                diagnostics +=
                    Diagnostic(
                        CoreCodes.RESERVED_CONFLICT,
                        "name '${element.name}' is reserved in $kind '$name'",
                        element.nameSpan,
                    )
            }
            if (ordinal in reserved.ordinals) {
                diagnostics +=
                    Diagnostic(
                        CoreCodes.RESERVED_CONFLICT,
                        "ordinal #$ordinal is reserved in $kind '$name'",
                        at,
                    )
            }
            ordinal
        }
    }
}
