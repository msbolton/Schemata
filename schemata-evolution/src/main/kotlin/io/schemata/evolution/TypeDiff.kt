package io.schemata.evolution

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import java.math.BigDecimal

/**
 * A type stripped of its bounds and patterns at every depth, so a bound or pattern change alone
 * does not look like a type change. A decimal keeps its precision and scale: those are part of its
 * type, not a bound, wherever the decimal sits (a field, a list element, a map key or value). A
 * reference keeps only what it names: its relation (`{ embed }`, `onDelete`) is compared as the
 * annotation facts [Differ] derives from it.
 */
internal fun typeCore(type: Type): Type =
    when (type) {
        is Scalar ->
            Scalar(
                type.builtin,
                Refinements(precision = type.refinements.precision, scale = type.refinements.scale),
            )
        is ListOf -> ListOf(typeCore(type.element), type.nullableElement)
        is MapOf -> MapOf(typeCore(type.key), typeCore(type.value), type.nullableValue)
        is Ref -> Ref(type.target)
    }

internal fun typeChanged(old: Type, new: Type): Boolean = typeCore(old) != typeCore(new)

/**
 * Each pair of refinements [old] and [new] hold at the same position: the type itself, then a
 * list's element, then a map's key and value, recursively. A position whose shapes differ (a list
 * against a scalar) ends the walk there, since it is a type change rather than a refinement change.
 */
private fun refinementPairs(old: Type, new: Type): List<Pair<Refinements, Refinements>> =
    when {
        old is Scalar && new is Scalar -> listOf(old.refinements to new.refinements)
        old is ListOf && new is ListOf ->
            listOf(old.refinements to new.refinements) + refinementPairs(old.element, new.element)
        old is MapOf && new is MapOf ->
            listOf(old.refinements to new.refinements) +
                refinementPairs(old.key, new.key) +
                refinementPairs(old.value, new.value)
        else -> emptyList()
    }

/** Whether any bound or pattern differs at any position [refinementPairs] visits. */
internal fun refinementsChanged(old: Type, new: Type): Boolean =
    refinementPairs(old, new).any { (o, n) ->
        o.min != n.min || o.max != n.max || o.pattern != n.pattern
    }

/**
 * Whether any position [refinementPairs] visits narrowed a bound or gained or changed a pattern.
 */
internal fun refinementsTightened(old: Type, new: Type): Boolean =
    refinementPairs(old, new).any { (o, n) -> tightened(o, n) }

internal fun tightened(old: Refinements, new: Refinements): Boolean {
    val minTightened = boundTightened(old.min, new.min, widens = false)
    val maxTightened = boundTightened(old.max, new.max, widens = true)
    val patternChanged = new.pattern != null && new.pattern != old.pattern
    return minTightened || maxTightened || patternChanged
}

/** [widens] is true for an upper bound, where a larger value is looser; false for a lower bound. */
private fun boundTightened(old: BigDecimal?, new: BigDecimal?, widens: Boolean): Boolean =
    when {
        old == null && new == null -> false
        new == null -> false
        old == null -> true
        widens -> new < old
        else -> new > old
    }
