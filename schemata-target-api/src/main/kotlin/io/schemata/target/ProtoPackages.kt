package io.schemata.target

import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Schema

/**
 * One `.proto` file: the [members] it holds (one namespace, or the namespaces of a reference cycle,
 * in name order), the proto [packageName] and the file [path] it is written to. [derived] means the
 * package was chosen here because no member declared one. [conflict] holds the first declared
 * package's namespace and the first namespace declaring a different one, when the members disagree;
 * the unit is then not written.
 */
data class ProtoUnit(
    val members: List<Namespace>,
    val packageName: String,
    val path: String,
    val derived: Boolean = false,
    val conflict: Pair<Namespace, Namespace>? = null,
) {
    val merged: Boolean
        get() = members.size > 1
}

/**
 * The file units of a key-rewritten schema and the package and path of each. Under the schema
 * layout namespaces that refer to each other in a cycle cannot be separate files, since `protoc`
 * rejects files that import each other, so a cycle's members share one unit. Under the type layout
 * a unit is a package scope instead: every schema keeps its own package, and only members of one
 * cycle that declare the same package share a scope.
 */
class ProtoPackages private constructor(val units: List<ProtoUnit>) {
    private val byNamespace: Map<String, ProtoUnit> =
        units.flatMap { unit -> unit.members.map { it.name to unit } }.toMap()

    fun unitOf(namespace: String): ProtoUnit =
        byNamespace[namespace] ?: error("no namespace named $namespace in the schema")

    fun packageOf(namespace: String): String = unitOf(namespace).packageName

    companion object {
        /** The raw `@proto(package)` text, or null when the namespace declares none. */
        fun declared(namespace: Namespace): String? =
            namespace.annotations.string("proto", "package")

        /**
         * The package [namespace] takes on its own: its declared `@proto(package)`, else its name.
         */
        fun own(namespace: Namespace): String = declared(namespace) ?: namespace.name

        /** [schema] must already be key-rewritten, so single-key references tie nothing. */
        fun of(schema: Schema): ProtoPackages {
            val byName = schema.namespaces.associateBy { it.name }
            val components =
                stronglyConnected(schema.namespaceReferences()).map { names ->
                    names.map { byName.getValue(it) }
                }
            // The package each unit would take if nothing outside it stood in the way: a declared
            // one, a singleton's name, or a cycle's common prefix.
            val candidates = components.map { candidate(it) }
            val units =
                components.mapIndexed { i, members ->
                    val inside = members.map { it.name }.toSet()
                    val outsideNames = schema.namespaces.map { it.name }.toSet() - inside
                    val outsidePackages =
                        candidates.filterIndexed { j, _ -> j != i }.toSet() +
                            schema.namespaces.filter { it.name !in inside }.mapNotNull(::declared)
                    unitFor(members, outsideNames, outsideNames + outsidePackages)
                }
            return ProtoPackages(units)
        }

        /**
         * [schema]'s units under [layout]: the schema layout's units, or the type layout's scopes.
         */
        fun of(schema: Schema, layout: ProtoLayout): ProtoPackages =
            when (layout) {
                ProtoLayout.SCHEMA -> of(schema)
                ProtoLayout.TYPE -> ProtoPackages(scopes(schema))
            }

        private fun pathOf(name: String) = name.replace('.', '/') + ".proto"

        /**
         * The package scopes of the type layout. Files of different packages may import each other
         * as long as no two files import each other, and the type layout writes a file per type, so
         * a reference cycle between schemas does not merge their packages: each schema takes its
         * own. Members of one cycle that declare the same package still share a scope, since their
         * declarations sit in one proto package where each name may be used once, so they keep the
         * cycle's renames and collision checks; splitting them would make valid input a package
         * collision. A scope is never derived or in conflict, and its path is its first member's,
         * as a singleton's is.
         */
        private fun scopes(schema: Schema): List<ProtoUnit> {
            val byName = schema.namespaces.associateBy { it.name }
            return stronglyConnected(schema.namespaceReferences())
                .flatMap { names -> names.map { byName.getValue(it) }.groupBy { own(it) }.values }
                .map { members ->
                    ProtoUnit(members, own(members.first()), pathOf(members.first().name))
                }
                .sortedBy { it.members.first().name }
        }

        /** [members]' declared package, else a singleton's name or a cycle's common prefix. */
        private fun candidate(members: List<Namespace>): String =
            members.firstNotNullOfOrNull { declared(it) }
                ?: if (members.size == 1) members.single().name
                else commonPrefix(members.map { it.name })

        /**
         * The unit of [members]. A cycle that declares no package takes its members' common prefix,
         * unless that is empty or one of [avoided], the names and packages of the schemas and
         * cycles outside it: the compiler chose it, so it must not collide with a package or path
         * that is already someone else's. It then takes its first member's name.
         */
        private fun unitFor(
            members: List<Namespace>,
            outside: Set<String>,
            avoided: Set<String>,
        ): ProtoUnit {
            val first = members.first()
            if (members.size == 1) {
                return ProtoUnit(members, own(first), pathOf(first.name))
            }
            val declaredBy = members.filter { declared(it) != null }
            val firstDeclared = declaredBy.firstOrNull()?.let { declared(it) }
            val differing = declaredBy.firstOrNull { declared(it) != firstDeclared }
            val outsidePaths = outside.map { pathOf(it) }.toSet()

            fun pathFor(packageName: String): String =
                pathOf(packageName).takeUnless { it in outsidePaths } ?: pathOf(first.name)

            if (firstDeclared != null) {
                return ProtoUnit(
                    members,
                    firstDeclared,
                    pathFor(firstDeclared),
                    conflict = differing?.let { declaredBy.first() to it },
                )
            }
            val prefix = commonPrefix(members.map { it.name })
            val packageName = if (prefix.isEmpty() || prefix in avoided) first.name else prefix
            return ProtoUnit(members, packageName, pathFor(packageName), derived = true)
        }

        /**
         * The longest run of whole dotted segments every name starts with; `ab.cd`, `ab.ce` share
         * `ab`.
         */
        private fun commonPrefix(names: List<String>): String {
            val segments = names.map { it.split('.') }
            val shortest = segments.minOf { it.size }
            val shared =
                (0 until shortest).takeWhile { i -> segments.all { it[i] == segments[0][i] } }
            return segments[0].take(shared.size).joinToString(".")
        }
    }
}
