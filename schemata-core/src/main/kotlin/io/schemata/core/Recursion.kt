package io.schemata.core

import io.schemata.core.ir.EnumType
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested

/**
 * Marks records that can reach themselves through references. Whether a target can represent the
 * cycle is the target's decision.
 */
object Recursion {
    fun mark(namespaces: List<Namespace>): List<Namespace> {
        val all = namespaces.flatMap { ns -> ns.declarations.flatMap { it.selfAndNested() } }
        val edges: Map<QualifiedName, Set<QualifiedName>> =
            all.associate { it.qualifiedName to outgoing(it) }
        val recursive =
            all.filter { it is RecordType && reaches(edges, it.qualifiedName, it.qualifiedName) }
                .map { it.qualifiedName }
                .toSet()
        return namespaces.map { ns ->
            ns.copy(declarations = ns.declarations.map { apply(it, recursive) })
        }
    }

    private fun outgoing(decl: TypeDecl): Set<QualifiedName> =
        when (decl) {
            is RecordType -> decl.fields.flatMap { refs(it.type) }.toSet()
            is UnionType -> decl.members.flatMap { refs(it.type) }.toSet()
            is EnumType -> emptySet()
        }

    private fun refs(type: Type): List<QualifiedName> =
        when (type) {
            is Scalar -> emptyList()
            is Ref -> listOf(type.target)
            is ListOf -> refs(type.element)
            is MapOf -> refs(type.key) + refs(type.value)
        }

    private fun reaches(
        edges: Map<QualifiedName, Set<QualifiedName>>,
        from: QualifiedName,
        target: QualifiedName,
    ): Boolean {
        val visited = mutableSetOf<QualifiedName>()
        val stack = ArrayDeque(edges[from].orEmpty())
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (next == target) return true
            if (visited.add(next)) stack += edges[next].orEmpty()
        }
        return false
    }

    private fun apply(decl: TypeDecl, recursive: Set<QualifiedName>): TypeDecl =
        when (decl) {
            is RecordType ->
                decl.copy(
                    recursive = decl.qualifiedName in recursive,
                    nested = decl.nested.map { apply(it, recursive) },
                )
            is EnumType -> decl
            is UnionType -> decl
        }
}
