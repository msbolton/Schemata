package io.schemata.core

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

/**
 * Two constructs parse but are not analyzed yet — refinements and annotations (SCH-20). They are
 * reported here at their span so nothing is silently dropped.
 */
object Unsupported {
    fun check(file: SourceFile): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        annotations(file.annotations, out)
        file.declarations.forEach { declaration(it, out) }
        return out
    }

    private fun declaration(decl: Declaration, out: MutableList<Diagnostic>) {
        annotations(decl.annotations, out)
        when (decl) {
            is RecordDecl -> {
                decl.fields.forEach { field ->
                    annotations(field.annotations, out)
                    type(field.type, out)
                }
                decl.nested.forEach { declaration(it, out) }
            }
            is EnumDecl -> decl.values.forEach { annotations(it.annotations, out) }
            is UnionDecl -> decl.members.forEach { type(it.type, out) }
            is AliasDecl -> type(decl.type, out)
        }
    }

    private fun type(type: TypeExpr, out: MutableList<Diagnostic>) {
        if (type.refinements.isNotEmpty())
            out += error("type refinements are not supported yet (SCH-20)", type.span)
        type.args.forEach { type(it, out) }
    }

    private fun annotations(annotations: List<Annotation>, out: MutableList<Diagnostic>) {
        annotations.forEach { out += error("annotations are not supported yet (SCH-20)", it.span) }
    }

    private fun error(message: String, span: Span) =
        Diagnostic(CoreCodes.UNSUPPORTED_CONSTRUCT, message, span)
}
