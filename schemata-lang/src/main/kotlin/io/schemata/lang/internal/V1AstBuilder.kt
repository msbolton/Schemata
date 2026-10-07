package io.schemata.lang.internal

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.antlr.Schemata1Parser
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.BindingDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.EnumValueDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.ImportDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.NamespaceDecl
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.ast.UnionMemberDecl
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode

/**
 * Parse tree of the 1.x surface → AST, for `schemata upgrade` alone. Constructs the grammar accepts
 * but the language reserves (`operation`, `stream`) become diagnostics in [diagnostics] rather than
 * nodes.
 */
internal class V1AstBuilder(private val file: String, diagnostics: MutableList<Diagnostic>) {
    private val support = AstSupport(file, diagnostics)

    fun build(ctx: Schemata1Parser.FileContext): SourceFile {
        // One pass over the top level, so diagnostics come out in source order whether they sit in
        // a declaration or a service.
        val services = mutableListOf<ServiceDecl>()
        return SourceFile(
            path = file,
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            namespace =
                ctx.namespaceDecl().let {
                    NamespaceDecl(it.qualifiedName().text, it.qualifiedName().span(), it.span())
                },
            imports =
                ctx.importDecl().map {
                    ImportDecl(
                        namespace = it.qualifiedName().text,
                        alias = it.IDENT()?.text,
                        namespaceSpan = it.qualifiedName().span(),
                        aliasSpan = it.IDENT()?.symbol?.span(),
                        span = it.span(),
                    )
                },
            declarations =
                ctx.topLevel().mapNotNull { top ->
                    val service = top.serviceDecl()
                    if (service != null) {
                        services += build(service)
                        null
                    } else build(top)
                },
            span = ctx.span(),
            services = services,
        )
    }

    private fun build(ctx: Schemata1Parser.TopLevelContext): Declaration? {
        ctx.reservedFutureDecl()?.let { reserved ->
            support.reservedFuture(reserved.start)
            return null
        }
        return ctx.declaration()?.let { build(it) }
    }

    private fun build(ctx: Schemata1Parser.DeclarationContext): Declaration =
        ctx.recordDecl()?.let { build(it) }
            ?: ctx.enumDecl()?.let { build(it) }
            ?: ctx.unionDecl()?.let { build(it) }
            ?: ctx.aliasDecl()?.let { build(it) }
            ?: error("unhandled declaration alternative at ${ctx.span()}")

    private fun build(ctx: Schemata1Parser.RecordDeclContext): RecordDecl {
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

    private fun build(ctx: Schemata1Parser.FieldContext): FieldDecl =
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

    private fun build(ctx: Schemata1Parser.EnumDeclContext): EnumDecl =
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

    private fun build(ctx: Schemata1Parser.UnionDeclContext): UnionDecl =
        UnionDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            members =
                ctx.unionMember().map {
                    UnionMemberDecl(
                        it.ORDINAL()?.let { o -> ordinal(o) },
                        it.ORDINAL()?.symbol?.span(),
                        build(it.typeExpr()),
                        doc(it.doc()),
                        it.span(),
                    )
                },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: Schemata1Parser.AliasDeclContext): AliasDecl =
        AliasDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            type = build(ctx.typeExpr()),
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: Schemata1Parser.ServiceDeclContext): ServiceDecl {
        val members = ctx.serviceMember()
        return ServiceDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            operations = members.mapNotNull { it.operation() }.map { build(it) },
            reserved = members.mapNotNull { it.reservedStmt() }.flatMap { build(it) },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )
    }

    /**
     * The `(`, `)`, and `:` literals have no stable token names, so the request is told from the
     * response by where each payload sits relative to the `)`.
     */
    private fun build(ctx: Schemata1Parser.OperationContext): OperationDecl {
        val close = ctx.children.indexOfFirst { it is TerminalNode && it.text == ")" }
        val payloads = ctx.payload()
        val request = payloads.firstOrNull { ctx.children.indexOf(it) < close }?.let { build(it) }
        val response = payloads.firstOrNull { ctx.children.indexOf(it) > close }?.let { build(it) }
        return OperationDecl(
            ordinal = ctx.ORDINAL()?.let { ordinal(it) },
            ordinalSpan = ctx.ORDINAL()?.symbol?.span(),
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            request = request,
            response = response,
            binding = ctx.binding()?.let { build(it) },
            doc = doc(ctx.doc()),
            annotations = ctx.annotation().map { build(it) },
            span = ctx.span(),
        )
    }

    private fun build(ctx: Schemata1Parser.PayloadContext): PayloadDecl =
        PayloadDecl(build(ctx.typeExpr()), ctx.STREAM() != null, ctx.span())

    private fun build(ctx: Schemata1Parser.BindingContext): BindingDecl =
        support.binding(ctx.IDENT(), ctx.STRING_LITERAL(), ctx.span())

    private fun build(ctx: Schemata1Parser.ReservedStmtContext): List<ReservedItem> =
        ctx.reservedItem().map { item ->
            item.STRING_LITERAL()?.let {
                ReservedItem.Name(support.string(it, it.symbol.span()), item.span())
            }
                ?: run {
                    val ordinals = item.ORDINAL().map { ordinal(it) }
                    ReservedItem.Ordinals(ordinals.first(), ordinals.last(), item.span())
                }
        }

    private fun build(ctx: Schemata1Parser.TypeExprContext): TypeExpr =
        TypeExpr(
            name = ctx.qualifiedName().text,
            nameSpan = ctx.qualifiedName().span(),
            nameSegments = ctx.qualifiedName().IDENT().map { it.symbol.span() },
            args = ctx.typeArgs()?.typeExpr()?.map { build(it) } ?: emptyList(),
            refinements =
                ctx.refinements()?.refinement()?.map { r ->
                    val ident = r.IDENT()
                    if (ident != null)
                        Refinement.Named(
                            ident.text,
                            if (ident.text == "pattern") patternLiteral(r.literal())
                            else build(r.literal()),
                            r.span(),
                        )
                    else Refinement.Positional(build(r.literal()), r.span())
                } ?: emptyList(),
            nullable = ctx.QUESTION() != null,
            span = ctx.span(),
        )

    private fun build(ctx: Schemata1Parser.AnnotationContext): Annotation =
        Annotation(
            name = ctx.IDENT().text,
            args =
                ctx.annotationArg().map { arg ->
                    val key = arg.annotationKey()
                    if (key != null)
                        AnnotationArg.Named(key.text, build(arg.annotationValue()), arg.span())
                    else
                        AnnotationArg.Positional(
                            AnnotationValue.Lit(build(arg.literal()), arg.span()),
                            arg.span(),
                        )
                },
            span = ctx.span(),
        )

    private fun build(ctx: Schemata1Parser.AnnotationValueContext): AnnotationValue =
        ctx.literal()?.let { AnnotationValue.Lit(build(it), ctx.span()) }
            ?: AnnotationValue.Tuple(
                ctx.IDENT().map { it.text },
                ctx.IDENT().map { it.symbol.span() },
                ctx.span(),
            )

    private fun build(ctx: Schemata1Parser.LiteralContext): Literal {
        val span = ctx.span()
        ctx.INT_LITERAL()?.let {
            return support.int(it, span)
        }
        ctx.FLOAT_LITERAL()?.let {
            return Literal.FloatLit(it.text, span)
        }
        ctx.STRING_LITERAL()?.let {
            return Literal.StringLit(support.string(it, span), span)
        }
        ctx.TRUE()?.let {
            return Literal.BoolLit(true, span)
        }
        ctx.FALSE()?.let {
            return Literal.BoolLit(false, span)
        }
        return Literal.NameLit(ctx.IDENT().text, span)
    }

    private fun doc(ctx: Schemata1Parser.DocContext?): String? = support.doc(ctx?.DOC_COMMENT())

    private fun ordinal(node: TerminalNode): Int = support.ordinal(node)

    /**
     * The string of a `pattern` refinement is taken as written; any other literal is built as
     * usual.
     */
    private fun patternLiteral(ctx: Schemata1Parser.LiteralContext): Literal =
        ctx.STRING_LITERAL()?.let { support.pattern(it, ctx.span()) } ?: build(ctx)

    private fun ParserRuleContext.span(): Span = support.span(this)

    private fun Token.span(): Span = support.span(this)
}
