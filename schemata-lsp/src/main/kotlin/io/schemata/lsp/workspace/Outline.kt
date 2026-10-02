package io.schemata.lsp.workspace

import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.UnionDecl

enum class OutlineKind {
    NAMESPACE,
    RECORD,
    ENUM,
    UNION,
    ALIAS,
    FIELD,
    VALUE,
}

/** One entry of a file's outline: [range] is the whole node, [selection] its name. */
data class OutlineNode(
    val name: String,
    val kind: OutlineKind,
    val range: TextRange,
    val selection: TextRange,
    val children: List<OutlineNode>,
)

/** The namespace, its declarations, and their fields, values, and nested declarations. */
internal fun outline(snapshot: Snapshot): List<OutlineNode> {
    val lines = snapshot.lines
    fun node(decl: Declaration): OutlineNode {
        val children =
            when (decl) {
                is RecordDecl -> {
                    val fields =
                        decl.fields.map {
                            it.span to
                                OutlineNode(
                                    it.name,
                                    OutlineKind.FIELD,
                                    lines.range(it.span),
                                    lines.range(it.nameSpan),
                                    emptyList(),
                                )
                        }
                    // Fields and nested declarations interleave in the source; keep that order.
                    (fields + decl.nested.map { it.span to node(it) })
                        .sortedWith(compareBy({ it.first.startLine }, { it.first.startColumn }))
                        .map { it.second }
                }
                is EnumDecl ->
                    decl.values.map {
                        OutlineNode(
                            it.name,
                            OutlineKind.VALUE,
                            lines.range(it.span),
                            lines.range(it.nameSpan),
                            emptyList(),
                        )
                    }
                is UnionDecl,
                is AliasDecl -> emptyList()
            }
        val kind =
            when (decl) {
                is RecordDecl -> OutlineKind.RECORD
                is EnumDecl -> OutlineKind.ENUM
                is UnionDecl -> OutlineKind.UNION
                is AliasDecl -> OutlineKind.ALIAS
            }
        return OutlineNode(
            decl.name,
            kind,
            lines.range(decl.span),
            lines.range(decl.nameSpan),
            children,
        )
    }
    val file = snapshot.file
    return listOf(
        OutlineNode(
            file.namespace.name,
            OutlineKind.NAMESPACE,
            lines.range(file.span),
            lines.range(file.namespace.nameSpan),
            file.declarations.map(::node),
        )
    )
}
