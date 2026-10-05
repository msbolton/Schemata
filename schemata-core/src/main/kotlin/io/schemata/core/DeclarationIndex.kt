package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Diagnostic
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl

data class IndexedDecl(
    val qualifiedName: QualifiedName,
    val decl: Declaration,
    val file: SourceFile,
)

/**
 * Every declaration in the compilation, keyed by qualified name, in sorted-path then declaration
 * order. Building it reports duplicate names (same file or across files) and declarations that
 * shadow a builtin type. Services share a namespace's type names: a service may not take the name
 * of a declaration or of another service. Services are never found by [find], so a type expression
 * cannot name one.
 */
class DeclarationIndex(files: List<SourceFile>, private val diagnostics: MutableList<Diagnostic>) {
    private val byName = linkedMapOf<QualifiedName, IndexedDecl>()
    private val services = linkedMapOf<QualifiedName, Pair<ServiceDecl, SourceFile>>()
    private val namespaces = files.map { it.namespace.name }.toSet()

    val entries: List<IndexedDecl>
        get() = byName.values.toList()

    init {
        val sorted = files.sortedBy { it.path }
        sorted.forEach { file ->
            file.declarations.forEach { add(file, file.namespace.name, emptyList(), it) }
        }
        // after every declaration, so a collision is always reported on the service
        sorted.forEach { file -> file.services.forEach { claimService(file, it) } }
    }

    fun find(name: QualifiedName): IndexedDecl? = byName[name]

    fun service(name: QualifiedName): ServiceDecl? = services[name]?.first

    fun namespaceExists(namespace: String): Boolean = namespace in namespaces

    private fun add(file: SourceFile, namespace: String, parent: List<String>, decl: Declaration) {
        val name = QualifiedName(namespace, parent + decl.name)
        val previous = byName[name]
        if (previous == null) {
            byName[name] = IndexedDecl(name, decl, file)
            if (Builtin.byName(decl.name) != null) {
                diagnostics +=
                    Diagnostic(
                        CoreCodes.BUILTIN_SHADOWED,
                        "'${decl.name}' shadows the builtin type of the same name",
                        decl.nameSpan,
                        help =
                            "rename the declaration; every bare use of `${decl.name}` in this namespace now means yours",
                    )
            }
        } else {
            val kind = kindOf(decl)
            diagnostics +=
                if (previous.file.path == file.path) {
                    Diagnostic(
                        CoreCodes.DUPLICATE_TYPE,
                        "$kind '${decl.name}' is declared more than once",
                        decl.nameSpan,
                        help = "rename or remove one of them",
                    )
                } else {
                    Diagnostic(
                        CoreCodes.DUPLICATE_TYPE,
                        "$kind '${decl.name}' is declared in both ${previous.file.path}:${previous.decl.nameSpan.startLine} and ${file.path}:${decl.nameSpan.startLine}",
                        decl.nameSpan,
                        help = "rename or remove one of them",
                    )
                }
        }
        if (decl is RecordDecl) decl.nested.forEach { add(file, namespace, parent + decl.name, it) }
    }

    private fun claimService(file: SourceFile, decl: ServiceDecl) {
        val name = QualifiedName(file.namespace.name, listOf(decl.name))
        val declared = byName[name]
        val previous = services[name]
        when {
            declared != null -> {
                val where =
                    if (declared.file.path == file.path) file.path
                    else
                        "${file.path}:${decl.nameSpan.startLine} and ${declared.file.path}:${declared.decl.nameSpan.startLine}"
                diagnostics +=
                    Diagnostic(
                        CoreCodes.DUPLICATE_TYPE,
                        "service '${decl.name}' and ${kindOf(declared.decl)} '${decl.name}' are both declared in $where",
                        decl.nameSpan,
                        help = "rename one of them; a service shares its namespace's type names",
                    )
            }
            previous != null ->
                diagnostics +=
                    if (previous.second.path == file.path) {
                        Diagnostic(
                            CoreCodes.DUPLICATE_TYPE,
                            "service '${decl.name}' is declared more than once",
                            decl.nameSpan,
                            help = "rename or remove one of them",
                        )
                    } else {
                        Diagnostic(
                            CoreCodes.DUPLICATE_TYPE,
                            "service '${decl.name}' is declared in both ${previous.second.path}:${previous.first.nameSpan.startLine} and ${file.path}:${decl.nameSpan.startLine}",
                            decl.nameSpan,
                            help = "rename or remove one of them",
                        )
                    }
            else -> services[name] = decl to file
        }
    }

    companion object {
        fun kindOf(decl: Declaration): String =
            when (decl) {
                is RecordDecl -> "record"
                is EnumDecl -> "enum"
                is UnionDecl -> "union"
                is AliasDecl -> "alias"
            }
    }
}
