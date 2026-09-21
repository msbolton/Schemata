package io.schemata.core

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.UnionDecl

/**
 * Annotations parse but are not analyzed yet (SCH-20). They are reported here at their span so
 * nothing is silently dropped.
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
                decl.fields.forEach { field -> annotations(field.annotations, out) }
                decl.nested.forEach { declaration(it, out) }
            }
            is EnumDecl -> decl.values.forEach { annotations(it.annotations, out) }
            is UnionDecl -> {}
            is AliasDecl -> {}
        }
    }

    private fun annotations(annotations: List<Annotation>, out: MutableList<Diagnostic>) {
        annotations.forEach { out += error("annotations are not supported yet (SCH-20)", it.span) }
    }

    private fun error(message: String, span: Span) =
        Diagnostic(CoreCodes.UNSUPPORTED_CONSTRUCT, message, span)
}
