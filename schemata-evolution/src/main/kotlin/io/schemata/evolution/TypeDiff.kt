package io.schemata.evolution

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Refinements
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import java.math.BigDecimal

/**
 * A type stripped of its refinements, so a bound or pattern change alone does not look like a type
 * change.
 */
internal fun typeCore(type: Type): Type =
    when (type) {
        is Scalar -> Scalar(type.builtin)
        is ListOf -> ListOf(typeCore(type.element), type.nullableElement)
        is MapOf -> MapOf(typeCore(type.key), typeCore(type.value), type.nullableValue)
        is Ref -> type
    }

internal fun refinementsOf(type: Type): Refinements =
    when (type) {
        is Scalar -> type.refinements
        is ListOf -> type.refinements
        is MapOf -> type.refinements
        is Ref -> Refinements.NONE
    }

/**
 * A decimal's precision or scale is part of its type, not a bound, so a change there is a type
 * change.
 */
internal fun typeChanged(old: Type, new: Type): Boolean {
    if (typeCore(old) != typeCore(new)) return true
    if (
        old !is Scalar ||
            new !is Scalar ||
            old.builtin != Builtin.DECIMAL ||
            new.builtin != Builtin.DECIMAL
    ) {
        return false
    }
    return old.refinements.precision != new.refinements.precision ||
        old.refinements.scale != new.refinements.scale
}

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
