package io.schemata.target.proto

import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
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
import io.schemata.target.ProtoPackages
import io.schemata.target.ProtoUnit
import io.schemata.target.stronglyConnected

/**
 * One file: its path; the top-level declarations and services it holds, in source order with
 * schemas by name; the [host] schema whose language options it writes; the package [scope] whose
 * package and directory it takes; the declaration [naming] it is named after, null for a service's
 * file and for the schema layout's one file; and the declarations of other scopes' schemas [moved]
 * into it.
 */
internal class TypeFile(
    val path: String,
    val declarations: List<TypeDecl>,
    val services: List<Service>,
    val host: Namespace,
    val scope: ProtoUnit,
    val naming: TypeDecl? = null,
    val moved: List<TypeDecl> = emptyList(),
) {
    val shared: Boolean
        get() = declarations.size > 1
}

/**
 * Top-level declarations that reference each other in a cycle, or one that is in none, from one
 * schema or several, in source order with schemas by name. The [host] is the schema of the member
 * whose file name sorts first, the earlier member on a tie; its package [scope] holds the file, and
 * the members of other scopes are [moved] into it.
 */
internal class TypeComponent(
    val declarations: List<TypeDecl>,
    val host: Namespace,
    val scope: ProtoUnit,
    val moved: List<TypeDecl>,
)

/** How declarations and services are spread over files. */
internal object ProtoTypeFiles {
    /**
     * `shop.orders.v1` → `shop/orders/v1/`: the scope's package as a directory, so a file's path
     * says its package whichever schema declared it.
     */
    fun directory(scope: ProtoUnit): String = scope.packageName.replace('.', '/') + "/"

    /** The unit as one file holding everything: the schema layout. */
    fun whole(unit: ProtoUnit): TypeFile =
        TypeFile(
            unit.path,
            unit.members.flatMap { it.declarations },
            unit.members.flatMap { it.services },
            unit.members.first(),
            unit,
        )

    /**
     * The type layout's components over every top-level declaration of every scope of [packages].
     * protoc rejects files that import each other, so declarations in one strongly connected
     * component of the reference graph share a file: stored fields and union members at any nesting
     * depth count, and a target is taken to its top-level declaration, whichever schema declares
     * it. Files of different packages may import each other, so a cycle between declarations joins
     * them and a cycle between their schemas does not. A key record references at most the enums of
     * its key fields, which reference nothing, and nothing names it except through the key rewrite,
     * so it is never in a cycle and is a component of its own; read back by the importer it is an
     * ordinary model, so compiling the import again gives the same files. The host is the member
     * whose [fileName] sorts first, so it does not depend on source order. Components are in the
     * order of their first member.
     */
    fun components(packages: ProtoPackages, fileName: (TypeDecl) -> String): List<TypeComponent> {
        val namespaces = packages.units.flatMap { it.members }.sortedBy { it.name }
        val byName = namespaces.associateBy { it.name }
        val decls = namespaces.flatMap { it.declarations }
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
        return stronglyConnected(graph).map { component ->
            val members = component.map { decls[it] }
            val host = byName.getValue(members.minBy(fileName).qualifiedName.namespace)
            val scope = packages.unitOf(host.name)
            TypeComponent(
                members,
                host,
                scope,
                members.filter { packages.unitOf(it.qualifiedName.namespace) !== scope },
            )
        }
    }

    /**
     * One file per component, in its scope's directory, named by the member whose [fileName] sorts
     * first. [fileName] may differ from the one the host was chosen by when declarations were
     * renamed after the host was chosen; the host keeps the file's package either way. In component
     * order.
     */
    fun typeFiles(components: List<TypeComponent>, fileName: (TypeDecl) -> String): List<TypeFile> =
        components.map { component ->
            val naming = component.declarations.minBy(fileName)
            TypeFile(
                directory(component.scope) + fileName(naming) + ".proto",
                component.declarations,
                emptyList(),
                component.host,
                component.scope,
                naming,
                component.moved,
            )
        }

    /**
     * Nothing references a service, so each is a file of its own in its schema's scope, named by
     * [fileName]; it never moves.
     */
    fun serviceFiles(scope: ProtoUnit, fileName: (Service) -> String): List<TypeFile> =
        scope.members.flatMap { member ->
            member.services.map {
                TypeFile(
                    directory(scope) + fileName(it) + ".proto",
                    emptyList(),
                    listOf(it),
                    member,
                    scope,
                )
            }
        }

    private fun top(qn: QualifiedName) = QualifiedName(qn.namespace, qn.path.take(1))
}
