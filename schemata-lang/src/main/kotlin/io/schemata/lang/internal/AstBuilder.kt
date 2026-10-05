package io.schemata.lang.internal

import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.SchemataText
import io.schemata.lang.Span
import io.schemata.lang.antlr.SchemataParser
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
 * Parse tree → AST. Constructs the grammar accepts but the language reserves (`operation`,
 * `stream`) become diagnostics in [diagnostics] rather than nodes.
 */
internal class AstBuilder(
    private val file: String,
    private val diagnostics: MutableList<Diagnostic>,
) {
    fun build(ctx: SchemataParser.FileContext): SourceFile {
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

    private fun build(ctx: SchemataParser.TopLevelContext): Declaration? {
        ctx.reservedFutureDecl()?.let { reserved ->
            val keyword = reserved.start
            diagnostics +=
                Diagnostic(
                    LangCodes.RESERVED_KEYWORD,
                    "'${keyword.text}' is reserved for a future version of Schemata",
                    keyword.span(),
                    help =
                        "rename the declaration; reserved words are listed in the language reference",
                )
            return null
        }
        return ctx.declaration()?.let { build(it) }
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
                        doc(it.doc()),
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

    private fun build(ctx: SchemataParser.ServiceDeclContext): ServiceDecl {
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
    private fun build(ctx: SchemataParser.OperationContext): OperationDecl {
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

    private fun build(ctx: SchemataParser.PayloadContext): PayloadDecl =
        PayloadDecl(build(ctx.typeExpr()), ctx.STREAM() != null, ctx.span())

    /**
     * The verb is an identifier in the grammar so that `get` and `post` stay legal names elsewhere;
     * here it must be an HTTP method (SCH0006). The path must be `/`-separated segments of
     * unreserved URL characters or `{lower_snake}` parameters (SCH0007).
     */
    private fun build(ctx: SchemataParser.BindingContext): BindingDecl {
        val verbNode = ctx.IDENT()
        val verb = verbNode.text
        if (verb !in VERBS) {
            diagnostics +=
                Diagnostic(
                    LangCodes.UNKNOWN_VERB,
                    "'$verb' is not an HTTP verb",
                    verbNode.symbol.span(),
                    help = "use one of get, post, put, patch, delete, head, options",
                )
        }
        val literal = ctx.STRING_LITERAL()
        val pathSpan = literal.symbol.span()
        val path = string(literal, pathSpan)
        val parameters = mutableListOf<String>()
        val problem = pathProblem(path, parameters)
        if (problem != null) {
            diagnostics +=
                Diagnostic(
                    LangCodes.MALFORMED_PATH,
                    "path ${SchemataText.string(path)} is malformed: $problem",
                    pathSpan,
                    help = "write the path as /segment/{param}; parameters are lower_snake",
                )
        }
        return BindingDecl(verb, verbNode.symbol.span(), path, pathSpan, parameters, ctx.span())
    }

    /**
     * Null when [path] is well formed; otherwise what is wrong, with [parameters] filled as far as
     * it got.
     */
    private fun pathProblem(path: String, parameters: MutableList<String>): String? {
        if (!path.startsWith("/")) return "it must start with /"
        if (path.length > 1 && path.endsWith("/")) return "it must not end with /"
        val body = path.substring(1)
        if (body.isEmpty()) return null
        for (segment in body.split("/")) {
            if (segment.isEmpty()) return "it has an empty segment"
            if (segment.startsWith("{") && segment.endsWith("}")) {
                val name = segment.substring(1, segment.length - 1)
                if (!LOWER_SNAKE.matches(name)) return "parameter \"$name\" is not lower_snake"
                parameters += name
            } else if (!SEGMENT.matches(segment)) {
                return "segment \"$segment\" holds a character outside A-Z a-z 0-9 . _ ~ -"
            }
        }
        return null
    }

    private fun build(ctx: SchemataParser.ReservedStmtContext): List<ReservedItem> =
        ctx.reservedItem().map { item ->
            item.STRING_LITERAL()?.let {
                ReservedItem.Name(string(it, it.symbol.span()), item.span())
            }
                ?: run {
                    val ordinals = item.ORDINAL().map { ordinal(it) }
                    ReservedItem.Ordinals(ordinals.first(), ordinals.last(), item.span())
                }
        }

    private fun build(ctx: SchemataParser.TypeExprContext): TypeExpr =
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

    private fun build(ctx: SchemataParser.AnnotationContext): Annotation =
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

    private fun build(ctx: SchemataParser.AnnotationValueContext): AnnotationValue =
        ctx.literal()?.let { AnnotationValue.Lit(build(it), ctx.span()) }
            ?: AnnotationValue.Tuple(
                ctx.IDENT().map { it.text },
                ctx.IDENT().map { it.symbol.span() },
                ctx.span(),
            )

    private fun build(ctx: SchemataParser.LiteralContext): Literal {
        val span = ctx.span()
        ctx.INT_LITERAL()?.let {
            val value =
                it.text.toLongOrNull()
                    ?: run {
                        diagnostics +=
                            Diagnostic(
                                LangCodes.NUMERIC_LITERAL_RANGE,
                                "number '${it.text}' is out of range",
                                span,
                                help = "use a value that fits in 64 bits",
                            )
                        0L
                    }
            return Literal.IntLit(value, span)
        }
        ctx.FLOAT_LITERAL()?.let {
            return Literal.FloatLit(it.text, span)
        }
        ctx.STRING_LITERAL()?.let {
            return Literal.StringLit(string(it, span), span)
        }
        ctx.TRUE()?.let {
            return Literal.BoolLit(true, span)
        }
        ctx.FALSE()?.let {
            return Literal.BoolLit(false, span)
        }
        return Literal.NameLit(ctx.IDENT().text, span)
    }

    /**
     * The text of a doc comment, which every target writes out; each control character XML cannot
     * carry is reported as SCH0005, as in a string.
     */
    private fun doc(ctx: SchemataParser.DocContext?): String? =
        ctx?.DOC_COMMENT()?.joinToString("\n") { node ->
            val token = node.symbol
            Strings.rawControls(token.text).forEach {
                report(it, token.line, token.charPositionInLine + 1, "a doc comment")
            }
            node.text.removePrefix("///").removePrefix(" ").trimEnd()
        }

    private fun ordinal(node: TerminalNode): Int =
        node.text.removePrefix("#").toIntOrNull()
            ?: run {
                diagnostics +=
                    Diagnostic(
                        LangCodes.NUMERIC_LITERAL_RANGE,
                        "ordinal '${node.text}' is out of range",
                        node.symbol.span(),
                        help = "use an ordinal that fits in 32 bits",
                    )
                0
            }

    /**
     * A string token's value; each escape the language does not define is reported as SCH0004, and
     * each control character XML cannot carry as SCH0005.
     */
    private fun string(node: TerminalNode, span: Span): String {
        val text = node.text
        val result = Strings.unescape(text.substring(1, text.length - 1))
        result.bad.forEach { report(it, span) }
        return result.value
    }

    /**
     * The string of a `pattern` refinement is taken as written; any other literal is built as
     * usual.
     */
    private fun patternLiteral(ctx: SchemataParser.LiteralContext): Literal =
        ctx.STRING_LITERAL()?.let {
            val span = ctx.span()
            Strings.rawControls(it.text.substring(1, it.text.length - 1)).forEach { bad ->
                report(bad, span)
            }
            Literal.StringLit(Strings.unquotePattern(it.text), span)
        } ?: build(ctx)

    /** [span] is a string literal's; its body starts one column in, after the quote. */
    private fun report(bad: BadText, span: Span) {
        // A string cannot span lines, so the bad text sits on the line the string starts on.
        report(bad, span.startLine, span.startColumn + 1, "a string")
    }

    /** [column] is where offset 0 of the text [bad] was found in sits; [what] names that text. */
    private fun report(bad: BadText, line: Int, column: Int, what: String) {
        val start = column + bad.offset
        val where = Span(file, line, start, line, start + bad.length - 1)
        diagnostics +=
            when (val reason = bad.reason) {
                BadText.UnknownEscape ->
                    Diagnostic(
                        LangCodes.BAD_ESCAPE,
                        "unknown escape '${bad.text}' in a string",
                        where,
                        help = ESCAPE_HELP,
                    )
                BadText.NotScalar ->
                    Diagnostic(
                        LangCodes.BAD_ESCAPE,
                        "'${bad.text}' is not a Unicode scalar value",
                        where,
                        help = ESCAPE_HELP,
                    )
                is BadText.Control ->
                    Diagnostic(
                        LangCodes.CONTROL_CHARACTER,
                        "control character U+%04X in %s".format(reason.point, what),
                        where,
                        help =
                            "write text; only tab, newline, and carriage return are allowed as control characters",
                    )
            }
    }

    private fun ParserRuleContext.span(): Span {
        val stop = stop ?: start
        val endColumn =
            if (stop.type == Token.EOF) stop.charPositionInLine + 1
            else stop.charPositionInLine + stop.text.codePointLength()
        return Span(file, start.line, start.charPositionInLine + 1, stop.line, endColumn)
    }

    private fun Token.span(): Span =
        Span(file, line, charPositionInLine + 1, line, charPositionInLine + text.codePointLength())

    private companion object {
        const val ESCAPE_HELP =
            "write \\\\ for a backslash; the escapes are \\\" \\\\ \\n \\t \\r \\u{…}"
        val VERBS = setOf("get", "post", "put", "patch", "delete", "head", "options")
        val LOWER_SNAKE = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
        val SEGMENT = Regex("[A-Za-z0-9._~-]+")
    }

    // ANTLR counts columns in Unicode code points; a token's own text is a normal UTF-16 Java
    // string, so an astral character inside it (an emoji, say) counts as one column here too,
    // rather than the two UTF-16 units `String.length` would give it.
    private fun String.codePointLength(): Int = codePointCount(0, length)
}
