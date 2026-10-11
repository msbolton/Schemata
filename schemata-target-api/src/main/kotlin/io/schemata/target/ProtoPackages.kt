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
 * The file units of a key-rewritten schema and the package and path of each. Namespaces that refer
 * to each other in a cycle cannot be separate files, since `protoc` rejects files that import each
 * other, so a cycle's members share one unit.
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

        /** [schema] must already be key-rewritten, so single-key references tie nothing. */
        fun of(schema: Schema): ProtoPackages {
            val byName = schema.namespaces.associateBy { it.name }
            val components = stronglyConnected(schema.namespaceReferences())
            val units =
                components.map { names ->
                    val members = names.map { byName.getValue(it) }
                    val outside = schema.namespaces.map { it.name }.toSet() - names.toSet()
                    unitFor(members, outside)
                }
            return ProtoPackages(units)
        }

        private fun pathOf(name: String) = name.replace('.', '/') + ".proto"

        private fun unitFor(members: List<Namespace>, outside: Set<String>): ProtoUnit {
            val first = members.first()
            if (members.size == 1) {
                return ProtoUnit(members, declared(first) ?: first.name, pathOf(first.name))
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
            // A prefix equal to an outside schema's name is that schema's own package and path;
            // taking it would put two files at one path.
            val prefix = commonPrefix(members.map { it.name })
            val packageName = if (prefix.isEmpty() || prefix in outside) first.name else prefix
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
