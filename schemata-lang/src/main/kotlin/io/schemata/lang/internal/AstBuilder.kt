package io.schemata.lang.internal

import io.schemata.lang.Span
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.NamespaceDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeRef
import org.antlr.v4.runtime.ParserRuleContext

internal class AstBuilder(private val file: String) {
    fun build(ctx: SchemataParser.FileContext): SourceFile =
        SourceFile(
            path = file,
            namespace =
                NamespaceDecl(ctx.namespaceDecl().qualifiedName().text, ctx.namespaceDecl().span()),
            declarations = ctx.declaration().map { build(it) },
            span = ctx.span(),
        )

    // record is the only declaration alternative today; ctx.recordDecl() is a platform type and
    // will NPE when a second alternative is added — extend this dispatch in the same change
    // (SCH-16).
    private fun build(ctx: SchemataParser.DeclarationContext): Declaration = build(ctx.recordDecl())

    private fun build(ctx: SchemataParser.RecordDeclContext): RecordDecl =
        RecordDecl(
            name = ctx.IDENT().text,
            fields = ctx.field().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.FieldContext): FieldDecl =
        FieldDecl(name = ctx.IDENT().text, type = build(ctx.typeRef()), span = ctx.span())

    private fun build(ctx: SchemataParser.TypeRefContext): TypeRef =
        TypeRef(name = ctx.IDENT().text, nullable = ctx.QUESTION() != null, span = ctx.span())

    private fun ParserRuleContext.span(): Span {
        val stop = stop ?: start
        return Span(
            file = file,
            startLine = start.line,
            startColumn = start.charPositionInLine + 1,
            endLine = stop.line,
            endColumn = stop.charPositionInLine + stop.text.length,
        )
    }
}
