package io.schemata.core

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.QualifiedName
import io.schemata.lang.Diagnostic
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
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
 * shadow a builtin type.
 */
class DeclarationIndex(files: List<SourceFile>, private val diagnostics: MutableList<Diagnostic>) {
    private val byName = linkedMapOf<QualifiedName, IndexedDecl>()
    private val namespaces = files.map { it.namespace.name }.toSet()

    val entries: List<IndexedDecl>
        get() = byName.values.toList()

    init {
        files
            .sortedBy { it.path }
            .forEach { file ->
                file.declarations.forEach { add(file, file.namespace.name, emptyList(), it) }
            }
    }

    fun find(name: QualifiedName): IndexedDecl? = byName[name]

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
                    )
                } else {
                    Diagnostic(
                        CoreCodes.DUPLICATE_TYPE,
                        "$kind '${decl.name}' is declared in both ${previous.file.path}:${previous.decl.nameSpan.startLine} and ${file.path}:${decl.nameSpan.startLine}",
                        decl.nameSpan,
                    )
                }
        }
        if (decl is RecordDecl) decl.nested.forEach { add(file, namespace, parent + decl.name, it) }
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
