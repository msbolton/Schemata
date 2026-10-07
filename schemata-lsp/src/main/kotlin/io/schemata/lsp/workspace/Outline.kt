package io.schemata.lsp.workspace

import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

enum class OutlineKind {
    NAMESPACE,
    RECORD,
    ENUM,
    UNION,
    ALIAS,
    FIELD,
    VALUE,
    SERVICE,
    OPERATION,
}

/**
 * One entry of a file's outline: [range] is the whole node, [selection] its name, and [detail] what
 * the editor shows beside the name, such as an operation's `(Request): Response`.
 */
data class OutlineNode(
    val name: String,
    val kind: OutlineKind,
    val range: TextRange,
    val selection: TextRange,
    val children: List<OutlineNode>,
    val detail: String? = null,
)

/**
 * The namespace, its declarations and services in source order, and their fields, values, nested
 * declarations, and operations.
 */
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
    fun service(decl: ServiceDecl): OutlineNode {
        val operations =
            decl.operations.map {
                OutlineNode(
                    it.name,
                    OutlineKind.OPERATION,
                    lines.range(it.span),
                    lines.range(it.nameSpan),
                    emptyList(),
                    payloadsText(it, lines::slice),
                )
            }
        return OutlineNode(
            decl.name,
            OutlineKind.SERVICE,
            lines.range(decl.span),
            lines.range(decl.nameSpan),
            operations,
        )
    }
    val file = snapshot.file
    val members =
        (file.declarations.map { it.span to node(it) } +
                file.services.map { it.span to service(it) })
            .sortedWith(compareBy({ it.first.startLine }, { it.first.startColumn }))
            .map { it.second }
    return listOf(
        OutlineNode(
            file.namespace.name,
            OutlineKind.NAMESPACE,
            lines.range(file.span),
            lines.range(file.namespace.nameSpan),
            members,
        )
    )
}

/**
 * An operation's payloads as the formatter prints them: `(Id): Order`, `(): stream Order`, or
 * `(stream Chunk)` when there is no response. [slice] gives the source text of a refinement value.
 */
internal fun payloadsText(op: OperationDecl, slice: (Span) -> String): String {
    fun payload(p: PayloadDecl) = (if (p.stream) "stream " else "") + typeText(p.type, slice)
    val request = op.request?.let(::payload) ?: ""
    val response = op.response?.let { ": " + payload(it) } ?: ""
    return "($request)$response"
}

/**
 * A type as the formatter prints it: `map<string, Line>`, `decimal(19, 4)`, `Line?[]?`, with no
 * stray whitespace. A payload or type argument names its type, so it has no inline shape to print.
 */
internal fun typeText(type: TypeExpr, slice: (Span) -> String): String {
    val args =
        if (type.args.isEmpty()) ""
        else "<" + type.args.joinToString(", ") { typeText(it, slice) } + ">"
    val refinements =
        if (type.refinements.isEmpty()) ""
        else "(" + type.refinements.joinToString(", ") { slice(it.value.span) } + ")"
    val nullable = if (type.nullable) "?" else ""
    val list = if (!type.list) "" else "[]" + if (type.listNullable) "?" else ""
    return type.name + args + refinements + nullable + list
}
