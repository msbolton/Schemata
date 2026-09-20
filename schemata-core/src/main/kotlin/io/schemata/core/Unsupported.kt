package io.schemata.core

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

/**
 * The grammar accepts the whole language; the analyzer lowers only the v1 subset. Everything in
 * between is reported here, honestly and at its span, instead of being silently dropped. Each
 * message names the ticket that will remove it.
 */
object Unsupported {
    fun check(file: SourceFile): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        annotations(file.annotations, out)
        file.imports.forEach { out += error("imports are not supported yet (SCH-19)", it.span) }
        file.declarations.forEach { declaration(it, nested = false, out) }
        return out
    }

    private fun declaration(decl: Declaration, nested: Boolean, out: MutableList<Diagnostic>) {
        if (nested) out += error("nested declarations are not supported yet (SCH-19)", decl.span)
        annotations(decl.annotations, out)
        when (decl) {
            is RecordDecl -> {
                decl.fields.forEach { field(it, out) }
                decl.reserved.forEach {
                    out += error("reserved statements are not supported yet (SCH-22)", it.span)
                }
                decl.nested.forEach { declaration(it, nested = true, out) }
            }
            is EnumDecl -> out += error("enums are not supported yet (SCH-20)", decl.span)
            is UnionDecl -> out += error("unions are not supported yet (SCH-20)", decl.span)
            is AliasDecl -> out += error("aliases are not supported yet (SCH-20)", decl.span)
        }
    }

    private fun field(field: FieldDecl, out: MutableList<Diagnostic>) {
        annotations(field.annotations, out)
        // field.span starts at the field's leading annotation, if any (grammar: `field : doc?
        // annotation* ORDINAL? IDENT ...`); field.type.span is the first span inside the field's
        // own text, so it lands on the field's line rather than an annotation's.
        if (field.ordinal != null) {
            out += error("explicit ordinals are not supported yet (SCH-22)", field.type.span)
        }
        type(field.type, out)
        field.default?.let {
            out += error("field defaults are not supported yet (SCH-20)", it.span)
        }
    }

    private fun type(type: TypeExpr, out: MutableList<Diagnostic>) {
        if (type.refinements.isNotEmpty())
            out += error("type refinements are not supported yet (SCH-20)", type.span)
        if (type.args.isNotEmpty())
            out += error("generic types are not supported yet (SCH-20)", type.span)
        if ('.' in type.name)
            out += error("qualified type names are not supported yet (SCH-19)", type.span)
    }

    private fun annotations(annotations: List<Annotation>, out: MutableList<Diagnostic>) {
        annotations.forEach { out += error("annotations are not supported yet (SCH-20)", it.span) }
    }

    private fun error(message: String, span: Span) =
        Diagnostic(CoreCodes.UNSUPPORTED_CONSTRUCT, message, span)
}
