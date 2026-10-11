package io.schemata.target

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested
import io.schemata.core.ir.storedFields

/**
 * For every namespace, the other namespaces its written declarations refer to: stored fields
 * (virtual back-references are never written, so they refer to nothing), union members at any
 * nesting depth, and rpc request and response types. A namespace's references to itself are not
 * edges. Run it on the key-rewritten schema: there a single-key reference is already a scalar or
 * enum and so ties nothing, while a composite key is a reference to the target's key record.
 */
fun Schema.namespaceReferences(): Map<String, Set<String>> =
    namespaces.associate { ns ->
        val targets = sortedSetOf<String>()

        fun visit(type: Type) {
            when (type) {
                is Scalar -> Unit
                is Ref -> targets += type.target.namespace
                is ListOf -> visit(type.element)
                is MapOf -> {
                    visit(type.key)
                    visit(type.value)
                }
            }
        }
        for (decl in ns.declarations.flatMap { it.selfAndNested() }) {
            when (decl) {
                is RecordType -> decl.storedFields.forEach { visit(it.type) }
                is UnionType -> decl.members.forEach { visit(it.type) }
                else -> Unit
            }
        }
        for (service in ns.services) {
            for (op in service.operations) {
                for (payload in listOfNotNull(op.request, op.response)) {
                    targets += payload.target.namespace
                }
            }
        }
        targets -= ns.name
        ns.name to targets
    }

/**
 * The strongly connected components of [graph], Tarjan's algorithm over sorted keys and sorted
 * neighbours so the result is deterministic: each component sorted, components ordered by their
 * first member. A node with no cycle through it is a component of its own.
 */
fun <T : Comparable<T>> stronglyConnected(graph: Map<T, Set<T>>): List<List<T>> {
    val index = HashMap<T, Int>()
    val low = HashMap<T, Int>()
    val onStack = HashSet<T>()
    val stack = ArrayDeque<T>()
    val components = mutableListOf<List<T>>()
    var counter = 0

    fun connect(node: T) {
        index[node] = counter
        low[node] = counter
        counter++
        stack.addLast(node)
        onStack += node
        for (next in graph[node].orEmpty().sorted()) {
            if (next !in index) {
                connect(next)
                low[node] = minOf(low.getValue(node), low.getValue(next))
            } else if (next in onStack) {
                low[node] = minOf(low.getValue(node), index.getValue(next))
            }
        }
        if (low[node] == index[node]) {
            val component = mutableListOf<T>()
            do {
                val member = stack.removeLast()
                onStack -= member
                component += member
            } while (member != node)
            components += component.sorted()
        }
    }
    for (node in graph.keys.sorted()) if (node !in index) connect(node)
    return components.sortedBy { it.first() }
}
