package io.schemata.lang.internal

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.lang.antlr.Schemata2Parser
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
import io.schemata.lang.ast.OperationDecl
import io.schemata.lang.ast.Option
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
 * Parse tree of the 2.0 surface → the same AST the 1.x surface builds, plus what only 2.0 can say:
 * option blocks, postfix lists, and inline enums and shapes. The `schema` header becomes the
 * [NamespaceDecl] and its attributes the file's annotations; a model's `@@` block attributes join
 * its leading attributes in [RecordDecl.annotations], after them. `operation` is reserved and
 * becomes a diagnostic in [diagnostics] rather than a node.
 */
internal class Ast2Builder(private val file: String, diagnostics: MutableList<Diagnostic>) {
    private val support = AstSupport(file, diagnostics)

    fun build(ctx: Schemata2Parser.FileContext): SourceFile {
        // One pass over the top level, so diagnostics come out in source order whether they sit in
        // a declaration or a service.
        val services = mutableListOf<ServiceDecl>()
        val header = ctx.schemaDecl()
        val name = header.qualifiedName()
        return SourceFile(
            path = file,
            doc = doc(ctx.doc()),
            namespace =
                NamespaceDecl(name.text, name.span(), support.span(header.start, name.stop)),
            annotations = header.attribute().map { build(it) },
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

    private fun build(ctx: Schemata2Parser.TopLevelContext): Declaration? {
        ctx.reservedFutureDecl()?.let {
            support.reservedFuture(it.start)
            return null
        }
        return ctx.declaration()?.let { build(it) }
    }

    private fun build(ctx: Schemata2Parser.DeclarationContext): Declaration =
        ctx.modelDecl()?.let { build(it) }
            ?: ctx.enumDecl()?.let { build(it) }
            ?: ctx.unionDecl()?.let { build(it) }
            ?: ctx.aliasDecl()?.let { build(it) }
            ?: error("unhandled declaration alternative at ${ctx.span()}")

    private fun build(ctx: Schemata2Parser.ModelDeclContext): RecordDecl {
        val doc = doc(ctx.doc())
        val leading = ctx.attribute().map { build(it) }
        val members = members(ctx.modelMember())
        return RecordDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            fields = members.fields,
            nested = members.nested,
            reserved = members.reserved,
            doc = doc,
            annotations = leading + ctx.blockAttribute().map { build(it) },
            span = ctx.span(),
        )
    }

    /** A model body's members, split by kind, each kind in source order. */
    private class Members(
        val fields: List<FieldDecl>,
        val nested: List<Declaration>,
        val reserved: List<ReservedItem>,
    )

    private fun members(ctx: List<Schemata2Parser.ModelMemberContext>): Members {
        val fields = mutableListOf<FieldDecl>()
        val nested = mutableListOf<Declaration>()
        val reserved = mutableListOf<ReservedItem>()
        for (member in ctx) {
            member.field()?.let { fields += build(it) }
            member.declaration()?.let { nested += build(it) }
            member.reservedStmt()?.let { reserved += build(it) }
        }
        return Members(fields, nested, reserved)
    }

    private fun build(ctx: Schemata2Parser.FieldContext): FieldDecl =
        FieldDecl(
            doc = doc(ctx.doc()),
            ordinal = ctx.ORDINAL()?.let { support.ordinal(it) },
            ordinalSpan = ctx.ORDINAL()?.symbol?.span(),
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            type = build(ctx.typeExpr()),
            options = options(ctx.optionBlock()),
            annotations = ctx.attribute().map { build(it) },
            default = ctx.literal()?.let { build(it) },
            span = ctx.span(),
        )

    private fun build(ctx: Schemata2Parser.EnumDeclContext): EnumDecl {
        val doc = doc(ctx.doc())
        val annotations = ctx.attribute().map { build(it) }
        return enumDecl(
            ctx.IDENT().text,
            ctx.IDENT().symbol.span(),
            ctx.enumBody(),
            doc,
            annotations,
            ctx.span(),
        )
    }

    private fun enumDecl(
        name: String,
        nameSpan: Span,
        body: Schemata2Parser.EnumBodyContext,
        doc: String?,
        annotations: List<Annotation>,
        span: Span,
    ): EnumDecl =
        EnumDecl(
            name = name,
            nameSpan = nameSpan,
            values =
                body.enumValue().map {
                    EnumValueDecl(
                        doc = doc(it.doc()),
                        annotations = it.attribute().map { a -> build(a) },
                        ordinal = it.ORDINAL()?.let { o -> support.ordinal(o) },
                        ordinalSpan = it.ORDINAL()?.symbol?.span(),
                        name = it.IDENT().text,
                        nameSpan = it.IDENT().symbol.span(),
                        span = it.span(),
                    )
                },
            reserved = body.reservedStmt().flatMap { build(it) },
            doc = doc,
            annotations = annotations,
            span = span,
        )

    private fun build(ctx: Schemata2Parser.UnionDeclContext): UnionDecl {
        val doc = doc(ctx.doc())
        val annotations = ctx.attribute().map { build(it) }
        return UnionDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            members =
                ctx.unionMember().map {
                    val memberDoc = doc(it.doc())
                    UnionMemberDecl(
                        it.ORDINAL()?.let { o -> support.ordinal(o) },
                        it.ORDINAL()?.symbol?.span(),
                        build(it.typeExpr()),
                        memberDoc,
                        it.span(),
                    )
                },
            doc = doc,
            annotations = annotations,
            span = ctx.span(),
        )
    }

    /** Options written after an alias's type apply to every use, so they travel with the type. */
    private fun build(ctx: Schemata2Parser.AliasDeclContext): AliasDecl {
        val doc = doc(ctx.doc())
        val annotations = ctx.attribute().map { build(it) }
        return AliasDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            type = build(ctx.typeExpr(), ctx.optionBlock()),
            doc = doc,
            annotations = annotations,
            span = ctx.span(),
        )
    }

    private fun build(ctx: Schemata2Parser.ServiceDeclContext): ServiceDecl {
        val doc = doc(ctx.doc())
        val annotations = ctx.attribute().map { build(it) }
        val members = ctx.serviceMember()
        return ServiceDecl(
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            operations = members.mapNotNull { it.operation() }.map { build(it) },
            reserved = members.mapNotNull { it.reservedStmt() }.flatMap { build(it) },
            doc = doc,
            annotations = annotations,
            span = ctx.span(),
        )
    }

    /**
     * The `(`, `)`, and `:` literals have no stable token names, so the request is told from the
     * response by where each payload sits relative to the `)`.
     */
    private fun build(ctx: Schemata2Parser.OperationContext): OperationDecl {
        val doc = doc(ctx.doc())
        val annotations = ctx.attribute().map { build(it) }
        val close = ctx.children.indexOfFirst { it is TerminalNode && it.text == ")" }
        val payloads = ctx.payload()
        val request = payloads.firstOrNull { ctx.children.indexOf(it) < close }?.let { build(it) }
        val response = payloads.firstOrNull { ctx.children.indexOf(it) > close }?.let { build(it) }
        return OperationDecl(
            ordinal = ctx.ORDINAL()?.let { support.ordinal(it) },
            ordinalSpan = ctx.ORDINAL()?.symbol?.span(),
            name = ctx.IDENT().text,
            nameSpan = ctx.IDENT().symbol.span(),
            request = request,
            response = response,
            binding =
                ctx.binding()?.let { support.binding(it.IDENT(), it.STRING_LITERAL(), it.span()) },
            doc = doc,
            annotations = annotations,
            span = ctx.span(),
        )
    }

    private fun build(ctx: Schemata2Parser.PayloadContext): PayloadDecl =
        PayloadDecl(build(ctx.typeExpr()), ctx.STREAM() != null, ctx.span())

    private fun build(ctx: Schemata2Parser.ReservedStmtContext): List<ReservedItem> =
        ctx.reservedItem().map { item ->
            item.STRING_LITERAL()?.let {
                ReservedItem.Name(support.string(it, it.symbol.span()), item.span())
            }
                ?: run {
                    val ordinals = item.ORDINAL().map { support.ordinal(it) }
                    ReservedItem.Ordinals(ordinals.first(), ordinals.last(), item.span())
                }
        }

    /**
     * A `?` before `[]` makes the element nullable and one after it the list; with no `[]` the only
     * `?` is the type's own. [optionBlock] is the one written after the type, when its position
     * allows one (a type argument or an alias).
     */
    private fun build(
        ctx: Schemata2Parser.TypeExprContext,
        optionBlock: Schemata2Parser.OptionBlockContext? = null,
    ): TypeExpr {
        val open = ctx.children.indexOfFirst { it is TerminalNode && it.text == "[" }
        val list = open >= 0
        val questions = ctx.QUESTION().map { ctx.children.indexOf(it) }
        val nullable = questions.any { !list || it < open }
        val listNullable = list && questions.any { it > open }
        val core = ctx.typeCore()
        val name = core.qualifiedName()
        if (name != null) {
            return TypeExpr(
                name = name.text,
                nameSpan = name.span(),
                nameSegments = name.IDENT().map { it.symbol.span() },
                args =
                    core.typeArgs()?.typeArg()?.map { build(it.typeExpr(), it.optionBlock()) }
                        ?: emptyList(),
                refinements =
                    core.decimalArgs()?.INT_LITERAL()?.map {
                        val span = it.symbol.span()
                        Refinement.Positional(support.int(it, span), span)
                    } ?: emptyList(),
                nullable = nullable,
                span = ctx.span(),
                list = list,
                listNullable = listNullable,
                options = options(optionBlock),
            )
        }
        // An inline enum or shape has no name until the analyzer hoists it; the opening `enum` or
        // `{` stands in for one wherever a span for the name is needed.
        val opening = core.start.span()
        val inlineEnum =
            core.enumBody()?.let { enumDecl("", opening, it, null, emptyList(), core.span()) }
        val inlineShape =
            if (inlineEnum != null) null
            else {
                val members = members(core.modelMember())
                RecordDecl(
                    name = "",
                    nameSpan = opening,
                    fields = members.fields,
                    nested = members.nested,
                    reserved = members.reserved,
                    doc = null,
                    annotations = core.blockAttribute().map { build(it) },
                    span = core.span(),
                )
            }
        return TypeExpr(
            name = "",
            nameSpan = opening,
            nameSegments = emptyList(),
            args = emptyList(),
            refinements = emptyList(),
            nullable = nullable,
            span = ctx.span(),
            list = list,
            listNullable = listNullable,
            options = options(optionBlock),
            inlineEnum = inlineEnum,
            inlineShape = inlineShape,
        )
    }

    /**
     * `match` names a regular expression, so its string is taken as written like 1.x's `pattern`
     * refinement; every other value is built as a literal.
     */
    private fun options(ctx: Schemata2Parser.OptionBlockContext?): List<Option> =
        ctx?.option()?.map { option ->
            val name = option.IDENT().text
            val value =
                option.optionValue()?.let { v ->
                    val token = v.getChild(0) as TerminalNode
                    if (name == "match" && v.STRING_LITERAL() != null)
                        support.pattern(token, v.span())
                    else literal(token, v.span())
                }
            Option(name, value, option.span())
        } ?: emptyList()

    private fun build(ctx: Schemata2Parser.AttributeContext): Annotation =
        Annotation(ctx.attributeName().text, ctx.attrArg().map { build(it) }, ctx.span())

    private fun build(ctx: Schemata2Parser.BlockAttributeContext): Annotation =
        Annotation(ctx.attributeName().text, ctx.attrArg().map { build(it) }, ctx.span())

    /**
     * `key: value` is named; a bare name is `Positional(Lit(NameLit(name)))`, as is any literal.
     */
    private fun build(ctx: Schemata2Parser.AttrArgContext): AnnotationArg {
        ctx.attrKey()?.let {
            return AnnotationArg.Named(it.text, build(ctx.attrValue()), ctx.span())
        }
        val value =
            ctx.IDENT()?.let { Literal.NameLit(it.text, it.symbol.span()) } ?: build(ctx.literal())
        return AnnotationArg.Positional(AnnotationValue.Lit(value, ctx.span()), ctx.span())
    }

    private fun build(ctx: Schemata2Parser.AttrValueContext): AnnotationValue =
        ctx.literal()?.let { AnnotationValue.Lit(build(it), ctx.span()) }
            ?: AnnotationValue.Tuple(
                ctx.IDENT().map { it.text },
                ctx.IDENT().map { it.symbol.span() },
                ctx.span(),
            )

    private fun build(ctx: Schemata2Parser.LiteralContext): Literal =
        literal(ctx.getChild(0) as TerminalNode, ctx.span())

    /** A literal is always one token; [span] is its rule's. */
    private fun literal(token: TerminalNode, span: Span): Literal =
        when (token.symbol.type) {
            Schemata2Parser.INT_LITERAL -> support.int(token, span)
            Schemata2Parser.FLOAT_LITERAL -> Literal.FloatLit(token.text, span)
            Schemata2Parser.STRING_LITERAL -> Literal.StringLit(support.string(token, span), span)
            Schemata2Parser.TRUE -> Literal.BoolLit(true, span)
            Schemata2Parser.FALSE -> Literal.BoolLit(false, span)
            else -> Literal.NameLit(token.text, span)
        }

    private fun doc(ctx: Schemata2Parser.DocContext?): String? = support.doc(ctx?.DOC_COMMENT())

    private fun ParserRuleContext.span(): Span = support.span(this)

    private fun Token.span(): Span = support.span(this)
}
