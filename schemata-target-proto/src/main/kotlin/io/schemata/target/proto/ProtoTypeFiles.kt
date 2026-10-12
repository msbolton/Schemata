package io.schemata.target.proto

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Service
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.selfAndNested
import io.schemata.core.ir.storedFields
import io.schemata.target.ProtoUnit
import io.schemata.target.stronglyConnected

/**
 * One file of a unit: its path and the top-level declarations and services it holds, in source
 * order.
 */
internal class TypeFile(
    val path: String,
    val declarations: List<TypeDecl>,
    val services: List<Service>,
) {
    val shared: Boolean
        get() = declarations.size > 1
}

/** How a unit's declarations and services are spread over files. */
internal object ProtoTypeFiles {
    /**
     * `shop.orders.v1` → `shop/orders/v1/`: the unit's package as a directory, so a file's path
     * says its package whichever schema declared it.
     */
    fun directory(unit: ProtoUnit): String = unit.packageName.replace('.', '/') + "/"

    /** The unit as one file holding everything: the schema layout. */
    fun whole(unit: ProtoUnit): TypeFile =
        TypeFile(
            unit.path,
            unit.members.flatMap { it.declarations },
            unit.members.flatMap { it.services },
        )

    /**
     * The unit split per type. protoc rejects files that import each other, so top-level
     * declarations in one strongly connected component of the unit's reference graph share a file:
     * stored fields and union members at any nesting depth count, a target is taken to its
     * top-level declaration, and references outside the unit tie nothing, since units never
     * reference each other in a cycle. A key record holds no references and nothing names it except
     * through the key rewrite, so it is a file of its own like any declaration that references
     * nothing; read back by the importer it is an ordinary model, so compiling the import again
     * gives the same files. Each shared file is named by the member whose [typeFileName] sorts
     * first, so the name does not depend on source order. Nothing references a service, so each is
     * a file of its own, named by [serviceFileName]. Files are sorted by path.
     */
    fun split(
        unit: ProtoUnit,
        typeFileName: (TypeDecl) -> String,
        serviceFileName: (Service) -> String,
    ): List<TypeFile> {
        val dir = directory(unit)
        val decls = unit.members.flatMap { it.declarations }
        val index = decls.withIndex().associate { (i, decl) -> top(decl.qualifiedName) to i }
        val graph = decls.indices.associateWith { mutableSetOf<Int>() }

        fun tie(from: Int, target: QualifiedName) {
            val to = index[top(target)] ?: return
            if (to != from) graph.getValue(from) += to
        }

        fun visit(from: Int, type: Type) {
            when (type) {
                is Scalar -> Unit
                is Ref -> tie(from, type.target)
                is ListOf -> visit(from, type.element)
                is MapOf -> {
                    visit(from, type.key)
                    visit(from, type.value)
                }
            }
        }
        for ((i, decl) in decls.withIndex()) {
            for (inner in decl.selfAndNested()) {
                when (inner) {
                    is RecordType -> inner.storedFields.forEach { visit(i, it.type) }
                    is UnionType -> inner.members.forEach { visit(i, it.type) }
                    else -> Unit
                }
            }
        }
        val typeFiles =
            stronglyConnected(graph).map { component ->
                val members = component.map { decls[it] }
                TypeFile(
                    dir + typeFileName(members.minBy(typeFileName)) + ".proto",
                    members,
                    emptyList(),
                )
            }
        val serviceFiles =
            unit.members
                .flatMap { it.services }
                .map { TypeFile(dir + serviceFileName(it) + ".proto", emptyList(), listOf(it)) }
        return (typeFiles + serviceFiles).sortedBy { it.path }
    }

    private fun top(qn: QualifiedName) = QualifiedName(qn.namespace, qn.path.take(1))
}
