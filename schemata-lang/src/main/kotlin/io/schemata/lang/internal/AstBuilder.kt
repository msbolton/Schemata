package io.schemata.lang.internal

import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.Span
import io.schemata.lang.antlr.SchemataParser
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.EnumValueDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.ImportDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.NamespaceDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.ast.UnionMemberDecl
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Parse tree → AST. Constructs the grammar accepts but the language reserves (`service`, …) become
 * diagnostics in [diagnostics] rather than nodes.
 */
internal class AstBuilder(
    private val file: String,
    private val diagnostics: MutableList<Diagnostic>,
) {
    fun build(ctx: SchemataParser.FileContext): SourceFile =
        SourceFile(
            path = file,
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            namespace =
                NamespaceDecl(ctx.namespaceDecl().qualifiedName().text, ctx.namespaceDecl().span()),
            imports =
                ctx.importDecl().map {
                    ImportDecl(it.qualifiedName().text, it.IDENT()?.text, it.span())
                },
            declarations = ctx.topLevel().mapNotNull { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.TopLevelContext): Declaration? {
        ctx.reservedFutureDecl()?.let { reserved ->
            val keyword = reserved.start
            diagnostics +=
                Diagnostic(
                    LangCodes.RESERVED_KEYWORD,
                    "'${keyword.text}' is reserved for a future version of Schemata",
                    keyword.span(),
                )
            return null
        }
        return build(ctx.declaration())
    }

    private fun build(ctx: SchemataParser.DeclarationContext): Declaration =
        ctx.recordDecl()?.let { build(it) }
            ?: ctx.enumDecl()?.let { build(it) }
            ?: ctx.unionDecl()?.let { build(it) }
            ?: ctx.aliasDecl()?.let { build(it) }
            ?: error("unhandled declaration alternative at ${ctx.span()}")

    private fun build(ctx: SchemataParser.RecordDeclContext): RecordDecl {
        val members = ctx.recordMember()
        return RecordDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            fields = members.mapNotNull { it.field() }.map { build(it) },
            nested = members.mapNotNull { it.declaration() }.map { build(it) },
            reserved = members.mapNotNull { it.reservedStmt() }.flatMap { build(it) },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )
    }

    private fun build(ctx: SchemataParser.FieldContext): FieldDecl =
        FieldDecl(
            ordinal = ctx.ORDINAL()?.let { ordinal(it) },
            ordinalSpan = ctx.ORDINAL()?.symbol?.span(),
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            type = build(ctx.typeExpr()),
            default = ctx.literal()?.let { build(it) },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.EnumDeclContext): EnumDecl =
        EnumDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            values =
                ctx.enumValue().map {
                    EnumValueDecl(
                        ordinal = it.ORDINAL()?.let { o -> ordinal(o) },
                        ordinalSpan = it.ORDINAL()?.symbol?.span(),
                        name = it.IDENT().text,
                        nameSpan = it.IDENT().symbol.span(),
                        doc = doc(it.doc()),
                        annotations = it.annotation().map { a -> build(a) },
                        span = it.span(),
                    )
                },
            reserved = ctx.reservedStmt().flatMap { build(it) },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.UnionDeclContext): UnionDecl =
        UnionDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            members =
                ctx.unionMember().map {
                    UnionMemberDecl(
                        it.ORDINAL()?.let { o -> ordinal(o) },
                        it.ORDINAL()?.symbol?.span(),
                        build(it.typeExpr()),
                        it.span(),
                    )
                },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.AliasDeclContext): AliasDecl =
        AliasDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            type = build(ctx.typeExpr()),
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.ReservedStmtContext): List<ReservedItem> =
        ctx.reservedItem().map { item ->
            item.STRING_LITERAL()?.let { ReservedItem.Name(unquote(it.text), item.span()) }
                ?: run {
                    val ordinals = item.ORDINAL().map { ordinal(it) }
                    ReservedItem.Ordinals(ordinals.first(), ordinals.last(), item.span())
                }
        }

    private fun build(ctx: SchemataParser.TypeExprContext): TypeExpr =
        TypeExpr(
            name = ctx.qualifiedName().text,
            nameSpan = ctx.qualifiedName().span(),
            args = ctx.typeArgs()?.typeExpr()?.map { build(it) } ?: emptyList(),
            refinements =
                ctx.refinements()?.refinement()?.map { r ->
                    val ident = r.IDENT()
                    if (ident != null) Refinement.Named(ident.text, build(r.literal()), r.span())
                    else Refinement.Positional(build(r.literal()), r.span())
                } ?: emptyList(),
            nullable = ctx.QUESTION() != null,
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.AnnotationContext): Annotation =
        Annotation(
            name = ctx.IDENT().text,
            args =
                ctx.annotationArg().map { arg ->
                    val ident = arg.IDENT()
                    if (ident != null)
                        AnnotationArg.Named(ident.text, build(arg.annotationValue()), arg.span())
                    else
                        AnnotationArg.Positional(
                            AnnotationValue.Lit(build(arg.literal()), arg.span()),
                            arg.span(),
                        )
                },
            span = ctx.span(),
        )

    private fun build(ctx: SchemataParser.AnnotationValueContext): AnnotationValue =
        ctx.literal()?.let { AnnotationValue.Lit(build(it), ctx.span()) }
            ?: AnnotationValue.Tuple(ctx.IDENT().map { it.text }, ctx.span())

    private fun build(ctx: SchemataParser.LiteralContext): Literal {
        val span = ctx.span()
        ctx.INT_LITERAL()?.let {
            return Literal.IntLit(it.text.toLong(), span)
        }
        ctx.FLOAT_LITERAL()?.let {
            return Literal.FloatLit(it.text, span)
        }
        ctx.STRING_LITERAL()?.let {
            return Literal.StringLit(unquote(it.text), span)
        }
        ctx.TRUE()?.let {
            return Literal.BoolLit(true, span)
        }
        ctx.FALSE()?.let {
            return Literal.BoolLit(false, span)
        }
        return Literal.NameLit(ctx.IDENT().text, span)
    }

    private fun doc(ctx: SchemataParser.DocContext?): String? =
        ctx?.DOC_COMMENT()?.joinToString("\n") { it.text.removePrefix("///").trim() }

    private fun ordinal(node: TerminalNode): Int = node.text.removePrefix("#").toInt()

    private fun unquote(text: String): String =
        text.substring(1, text.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")

    private fun ParserRuleContext.span(): Span {
        val stop = stop ?: start
        return Span(
            file,
            start.line,
            start.charPositionInLine + 1,
            stop.line,
            stop.charPositionInLine + stop.text.length,
        )
    }

    private fun Token.span(): Span =
        Span(file, line, charPositionInLine + 1, line, charPositionInLine + text.length)
}
