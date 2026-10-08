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
 * Parse tree → AST, including what the 1.x surface could not say: option blocks, postfix lists, and
 * inline enums and shapes. The `schema` header becomes the [NamespaceDecl] and its attributes the
 * file's annotations; a model's `@@` block attributes join its leading attributes in
 * [RecordDecl.annotations], after them. An attribute trailing the header or a field belongs to it
 * only when it starts on the same line; on a later line it leads the next declaration or member.
 * `operation` is reserved and becomes a diagnostic in [diagnostics] rather than a node.
 */
internal class AstBuilder(
    private val file: String,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private val support = AstSupport(file, diagnostics)

    /**
     * Attributes attach by line. The grammar reads the attributes after the header, or after a
     * field, greedily, so the builder keeps only those that start on the line they trail; the rest
     * lead whatever comes next (see [byLine]).
     */
    fun build(ctx: SchemataParser.FileContext): SourceFile {
        val doc = doc(ctx.doc())
        val header = ctx.schemaDecl()
        val name = header.qualifiedName()
        val (own, below) = byLine(header.attribute(), name.stop.line)
        val annotations = own.map { build(it) }
        var leading = below.map { build(it) }
        // Attributes below the header lead the first declaration or service. An import cannot
        // carry one, and neither can a reserved word or the end of the file.
        val first = ctx.topLevel().firstOrNull()
        if (
            ctx.importDecl().isNotEmpty() ||
                first == null ||
                (first.declaration() == null && first.serviceDecl() == null)
        ) {
            nothingToAttach(leading)
            leading = emptyList()
        }
        val imports =
            ctx.importDecl().map {
                ImportDecl(
                    namespace = it.qualifiedName().text,
                    alias = it.IDENT()?.text,
                    namespaceSpan = it.qualifiedName().span(),
                    aliasSpan = it.IDENT()?.symbol?.span(),
                    span = it.span(),
                )
            }
        // One pass over the top level, so diagnostics come out in source order whether they sit in
        // a declaration or a service.
        val services = mutableListOf<ServiceDecl>()
        val declarations = mutableListOf<Declaration>()
        for ((index, top) in ctx.topLevel().withIndex()) {
            val lead = if (index == 0) leading else emptyList()
            val service = top.serviceDecl()
            if (service != null) {
                val built = build(service)
                services +=
                    built.copy(
                        annotations = lead + built.annotations,
                        span = widen(built.span, lead),
                    )
            } else build(top)?.let { declarations += lead(it, lead) }
        }
        return SourceFile(
            path = file,
            doc = doc,
            annotations = annotations,
            namespace =
                NamespaceDecl(name.text, name.span(), support.span(header.start, name.stop)),
            imports = imports,
            declarations = declarations,
            span = ctx.span(),
            services = services,
        )
    }

    /**
     * Splits [attributes] at the first one that starts on a line other than [line], the line of the
     * construct they trail: the first part is that construct's, the rest lead what follows.
     */
    private fun <T : ParserRuleContext> byLine(
        attributes: List<T>,
        line: Int,
    ): Pair<List<T>, List<T>> {
        val split = attributes.indexOfFirst { it.start.line != line }
        return if (split < 0) attributes to emptyList()
        else attributes.subList(0, split) to attributes.subList(split, attributes.size)
    }

    /** Reported at the first of [orphans], attributes on their own line with nothing below them. */
    private fun nothingToAttach(orphans: List<Annotation>) {
        val first = orphans.firstOrNull() ?: return
        diagnostics +=
            Diagnostic(
                LangCodes.SYNTAX,
                "an attribute here has nothing to attach to",
                first.span,
                help =
                    "write a trailing attribute on the line of what it trails, or put it on the line above the declaration or field it describes",
            )
    }

    /** [declaration] with [leading] before its own attributes, its span grown to cover them. */
    private fun lead(declaration: Declaration, leading: List<Annotation>): Declaration {
        if (leading.isEmpty()) return declaration
        val annotations = leading + declaration.annotations
        val span = widen(declaration.span, leading)
        return when (declaration) {
            is RecordDecl -> declaration.copy(annotations = annotations, span = span)
            is EnumDecl -> declaration.copy(annotations = annotations, span = span)
            is UnionDecl -> declaration.copy(annotations = annotations, span = span)
            is AliasDecl -> declaration.copy(annotations = annotations, span = span)
        }
    }

    private fun widen(span: Span, leading: List<Annotation>): Span =
        leading.firstOrNull()?.let {
            span.copy(startLine = it.span.startLine, startColumn = it.span.startColumn)
        } ?: span

    private fun build(ctx: SchemataParser.TopLevelContext): Declaration? {
        ctx.reservedFutureDecl()?.let {
            support.reservedFuture(it.start)
            return null
        }
        return ctx.declaration()?.let { build(it) }
    }

    private fun build(ctx: SchemataParser.DeclarationContext): Declaration =
        ctx.modelDecl()?.let { build(it) }
            ?: ctx.enumDecl()?.let { build(it) }
            ?: ctx.unionDecl()?.let { build(it) }
            ?: ctx.aliasDecl()?.let { build(it) }
            ?: error("unhandled declaration alternative at ${ctx.span()}")

    private fun build(ctx: SchemataParser.ModelDeclContext): RecordDecl {
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

    /**
     * A field's attributes on a later line than the field lead the next member; a reserved
     * statement cannot carry them, and neither can the end of the body.
     */
    private fun members(ctx: List<SchemataParser.ModelMemberContext>): Members {
        val fields = mutableListOf<FieldDecl>()
        val nested = mutableListOf<Declaration>()
        val reserved = mutableListOf<ReservedItem>()
        var leading = emptyList<Annotation>()
        for (member in ctx) {
            val field = member.field()
            val declaration = member.declaration()
            if (field != null) {
                val (built, below) = build(field, leading)
                fields += built
                leading = below
            } else if (declaration != null) {
                nested += lead(build(declaration), leading)
                leading = emptyList()
            } else {
                nothingToAttach(leading)
                leading = emptyList()
                reserved += build(member.reservedStmt())
            }
        }
        nothingToAttach(leading)
        return Members(fields, nested, reserved)
    }

    /**
     * The field, with [leading] carried down from the member above, and the attributes it passes on
     * to the member below. Carried attributes come first, then those written above the field. The
     * field's line is that of the last token before its trailing attributes: the type's, or the
     * closing `}` of its options or of a multi-line inline shape. When a default follows the
     * trailing attributes they all sit inside the field, so none is passed on.
     */
    private fun build(
        ctx: SchemataParser.FieldContext,
        leading: List<Annotation>,
    ): Pair<FieldDecl, List<Annotation>> {
        val doc = doc(ctx.doc())
        val nameAt = ctx.children.indexOf(ctx.IDENT())
        val (above, trailing) = ctx.attribute().partition { ctx.children.indexOf(it) < nameAt }
        val beforeTrailing = (ctx.optionBlock() ?: ctx.fieldType()).stop
        val (own, below) =
            if (ctx.literal() != null) trailing to emptyList()
            else byLine(trailing, beforeTrailing.line)
        val annotations = leading + above.map { build(it) }
        val field =
            FieldDecl(
                doc = doc,
                ordinal = ctx.ORDINAL()?.let { support.ordinal(it) },
                ordinalSpan = ctx.ORDINAL()?.symbol?.span(),
                name = ctx.IDENT().text,
                nameSpan = ctx.IDENT().symbol.span(),
                type = build(ctx.fieldType()),
                options = options(ctx.optionBlock()),
                annotations = annotations + own.map { build(it) },
                default = ctx.literal()?.let { build(it) },
                span =
                    widen(
                        if (below.isEmpty()) ctx.span()
                        else support.span(ctx.start, own.lastOrNull()?.stop ?: beforeTrailing),
                        leading,
                    ),
            )
        return field to below.map { build(it) }
    }

    private fun build(ctx: SchemataParser.EnumDeclContext): EnumDecl {
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
        body: SchemataParser.EnumBodyContext,
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

    private fun build(ctx: SchemataParser.UnionDeclContext): UnionDecl {
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
                        options(it.optionBlock()),
                    )
                },
            doc = doc,
            annotations = annotations,
            span = ctx.span(),
        )
    }

    /** Options written after an alias's type apply to every use, so they travel with the type. */
    private fun build(ctx: SchemataParser.AliasDeclContext): AliasDecl {
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

    private fun build(ctx: SchemataParser.ServiceDeclContext): ServiceDecl {
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
     * The request is told from the response by where each payload sits relative to the `)`, found
     * by its token type.
     */
    private fun build(ctx: SchemataParser.OperationContext): OperationDecl {
        val doc = doc(ctx.doc())
        val annotations = ctx.attribute().map { build(it) }
        val close =
            ctx.children.indexOfFirst {
                it is TerminalNode && it.symbol.type == SchemataParser.RPAREN
            }
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

    private fun build(ctx: SchemataParser.PayloadContext): PayloadDecl =
        PayloadDecl(build(ctx.typeExpr()), ctx.STREAM() != null, ctx.span())

    private fun build(ctx: SchemataParser.ReservedStmtContext): List<ReservedItem> =
        ctx.reservedItem().map { item ->
            item.STRING_LITERAL()?.let {
                ReservedItem.Name(support.string(it, it.symbol.span()), item.span())
            }
                ?: run {
                    val ordinals = item.ORDINAL().map { support.ordinal(it) }
                    ReservedItem.Ordinals(ordinals.first(), ordinals.last(), item.span())
                }
        }

    /** [optionBlock] is the one written after the type, in a type argument or an alias. */
    private fun build(
        ctx: SchemataParser.TypeExprContext,
        optionBlock: SchemataParser.OptionBlockContext? = null,
    ): TypeExpr = named(ctx.typeCore(), postfix(ctx, ctx.QUESTION()), ctx.span(), optionBlock)

    private fun build(ctx: SchemataParser.FieldTypeContext): TypeExpr {
        val postfix = postfix(ctx, ctx.QUESTION())
        ctx.typeCore()?.let {
            return named(it, postfix, ctx.span(), null)
        }
        // An inline enum or shape has no name until the analyzer hoists it; the opening `enum` or
        // `{` stands in for one wherever a span for the name is needed.
        val inline = ctx.inlineType()
        val opening = inline.start.span()
        val inlineEnum =
            inline.enumBody()?.let { enumDecl("", opening, it, null, emptyList(), inline.span()) }
        val inlineShape =
            if (inlineEnum != null) null
            else {
                val members = members(inline.modelMember())
                RecordDecl(
                    name = "",
                    nameSpan = opening,
                    fields = members.fields,
                    nested = members.nested,
                    reserved = members.reserved,
                    doc = null,
                    annotations = inline.blockAttribute().map { build(it) },
                    span = inline.span(),
                )
            }
        return TypeExpr(
            name = "",
            nameSpan = opening,
            nameSegments = emptyList(),
            args = emptyList(),
            refinements = emptyList(),
            nullable = postfix.nullable,
            span = ctx.span(),
            list = postfix.list,
            listNullable = postfix.listNullable,
            inlineEnum = inlineEnum,
            inlineShape = inlineShape,
        )
    }

    /** What the `?`, `[]`, `?` after a type say. */
    private class Postfix(val nullable: Boolean, val list: Boolean, val listNullable: Boolean)

    /**
     * A `?` before `[]` makes the element nullable and one after it the list; with no `[]` the only
     * `?` is the type's own.
     */
    private fun postfix(ctx: ParserRuleContext, questions: List<TerminalNode>): Postfix {
        val open = ctx.children.indexOfFirst { it is TerminalNode && it.text == "[" }
        val list = open >= 0
        val at = questions.map { ctx.children.indexOf(it) }
        return Postfix(
            nullable = at.any { !list || it < open },
            list = list,
            listNullable = list && at.any { it > open },
        )
    }

    private fun named(
        core: SchemataParser.TypeCoreContext,
        postfix: Postfix,
        span: Span,
        optionBlock: SchemataParser.OptionBlockContext?,
    ): TypeExpr {
        val name = core.qualifiedName()
        return TypeExpr(
            name = name.text,
            nameSpan = name.span(),
            nameSegments = name.IDENT().map { it.symbol.span() },
            args =
                core.typeArgs()?.typeArg()?.map { build(it.typeExpr(), it.optionBlock()) }
                    ?: emptyList(),
            refinements =
                core.decimalArgs()?.INT_LITERAL()?.map {
                    val literal = it.symbol.span()
                    Refinement.Positional(support.int(it, literal), literal)
                } ?: emptyList(),
            nullable = postfix.nullable,
            span = span,
            list = postfix.list,
            listNullable = postfix.listNullable,
            options = options(optionBlock),
        )
    }

    /**
     * `match` names a regular expression, so its string is taken as written like 1.x's `pattern`
     * refinement; every other value is built as a literal.
     */
    private fun options(ctx: SchemataParser.OptionBlockContext?): List<Option> =
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

    private fun build(ctx: SchemataParser.AttributeContext): Annotation =
        Annotation(ctx.attributeName().text, ctx.attrArg().map { build(it) }, ctx.span())

    private fun build(ctx: SchemataParser.BlockAttributeContext): Annotation =
        Annotation(
            ctx.attributeName().text,
            ctx.attrArg().map { build(it) },
            ctx.span(),
            block = true,
        )

    /**
     * `key: value` is named; any literal, a bare name included, is `Positional(Lit(…))`. A bare
     * name is a `NameLit`.
     */
    private fun build(ctx: SchemataParser.AttrArgContext): AnnotationArg {
        ctx.attrKey()?.let {
            return AnnotationArg.Named(it.text, build(ctx.attrValue()), ctx.span())
        }
        val value = build(ctx.literal())
        return AnnotationArg.Positional(AnnotationValue.Lit(value, ctx.span()), ctx.span())
    }

    private fun build(ctx: SchemataParser.AttrValueContext): AnnotationValue =
        ctx.literal()?.let { AnnotationValue.Lit(build(it), ctx.span()) }
            ?: AnnotationValue.Tuple(
                ctx.IDENT().map { it.text },
                ctx.IDENT().map { it.symbol.span() },
                ctx.span(),
            )

    private fun build(ctx: SchemataParser.LiteralContext): Literal =
        literal(ctx.getChild(0) as TerminalNode, ctx.span())

    /** A literal is always one token; [span] is its rule's. */
    private fun literal(token: TerminalNode, span: Span): Literal =
        when (token.symbol.type) {
            SchemataParser.INT_LITERAL -> support.int(token, span)
            SchemataParser.FLOAT_LITERAL -> Literal.FloatLit(token.text, span)
            SchemataParser.STRING_LITERAL -> Literal.StringLit(support.string(token, span), span)
            SchemataParser.TRUE -> Literal.BoolLit(true, span)
            SchemataParser.FALSE -> Literal.BoolLit(false, span)
            else -> Literal.NameLit(token.text, span)
        }

    private fun doc(ctx: SchemataParser.DocContext?): String? = support.doc(ctx?.DOC_COMMENT())

    private fun ParserRuleContext.span(): Span = support.span(this)

    private fun Token.span(): Span = support.span(this)
}
