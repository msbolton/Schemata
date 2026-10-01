package io.schemata.target

import io.schemata.core.ir.Namespace

/**
 * The groups of two or more [namespaces] whose [value] (a package, schema, target namespace, `$id`)
 * coincides, each group in source order; the caller reports at the group's second member.
 */
fun <V> collidingNamespaces(
    namespaces: List<Namespace>,
    value: (Namespace) -> V,
): List<List<Namespace>> = namespaces.groupBy(value).values.filter { it.size > 1 }
