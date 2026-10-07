package io.schemata.lang.upgrade

import io.schemata.lang.Diagnostic
import io.schemata.lang.LangCodes
import io.schemata.lang.Parser
import io.schemata.lang.Span
import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Annotation
import io.schemata.lang.ast.AnnotationArg
import io.schemata.lang.ast.AnnotationValue
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.Option
import io.schemata.lang.ast.PayloadDecl
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl
import io.schemata.lang.format.CommentTable
import io.schemata.lang.format.FormatResult
import io.schemata.lang.format.Formatter

/**
 * Rewrites a 1.x file in the 2.0 surface. The 1.x AST is mapped onto the 2.0 shape and printed by
 * the 2.0 formatter, so the result is already in the canonical layout and every comment and doc
 * survives.
 */
object Upgrader {
    /**
     * The 2.0 text of a 1.x file: Failed when it does not parse as 1.x, or when it writes something
     * the 2.0 surface cannot say; Formatted otherwise. A file that already reads as 2.0 comes back
     * as it was.
     */
    fun upgrade(input: String, path: String): FormatResult {
        val source = Formatter.normalize(input)
        if (Parser.parse2(source, path).file != null) return FormatResult.Formatted(input)
        val parsed = Parser.parse1ForUpgrade(source, path)
        val file = parsed.file ?: return FormatResult.Failed(parsed.diagnostics)
        val mapper = Mapper(parsed.comments)
        val mapped = mapper.file(file)
        if (mapper.problems.isNotEmpty()) return FormatResult.Failed(mapper.problems)
        val text = Formatter.print(mapped, mapper.comments(), source)
        return FormatResult.Formatted(Formatter.checked2(text, parsed, path))
    }

    /** The AST mapping alone, for tests. */
    fun map(file: SourceFile): SourceFile = Mapper(CommentTable.EMPTY).file(file)
}

/**
 * The 1.x AST → the 2.0 AST:
 * - A type's `min`, `max` and `pattern` refinements become the options `min`, `max` and `match` of
 *   the slot it sits in (a field, a type argument, an alias). A list's or map's own `min` and `max`
 *   bound its size, so they become `minItems` and `maxItems`. `decimal(p, s)` keeps its positional
 *   precision and scale, which are part of the type.
 * - `list<T>` becomes `T[]`: `list<T?>` is `T?[]` and `list<T>?` is `T[]?`. The element's options
 *   follow the list's own, in the same block, since a field has one. A list of lists keeps its
 *   outer `list<…>`, as a type takes one `[]`.
 * - On a field, `@sql(key)`, `@sql(unique)`, `@sql(index)` and `@sql(strategy = embed)` become the
 *   options `id`, `unique`, `index` and `embed`, ahead of the bounds; an `@sql` left with no
 *   arguments is dropped. Every other field annotation trails the field, except one carrying a
 *   comment of its own, which stays on its own line above the field so the comment keeps its line.
 * - On a record, `@sql(key = (a, b))` becomes `@@id(a, b)`, first among the block attributes, and
 *   every annotation becomes a block attribute, closing the body.
 * - Everything else (the namespace, imports, enums, unions, services, reserved, docs) keeps its
 *   text; the printer decides the 2.0 keywords and punctuation.
 * - What 2.0 cannot say is reported, not printed: a name spelled `schema` or `model` (keywords in
 *   2.0), a refinement on a union member or a payload (2.0 gives them no options), and a positional
 *   refinement on anything but `decimal(p, s)`.
 */
private class Mapper(private val table: CommentTable) {
    /** What the 2.0 surface cannot say, reported instead of printing something that misreads. */
    val problems = mutableListOf<Diagnostic>()

    /** Comments trailing an annotation the mapping dropped, and the element they now lead. */
    private val moved = mutableListOf<Pair<Span, Span>>()

    /** The comment table, with the comments of dropped annotations leading their owners. */
    fun comments(): CommentTable {
        if (moved.isEmpty()) return table
        val trailing = table.trailing.toMutableMap()
        val leading = table.leading.toMutableMap()
        for ((from, to) in moved) {
            val comments = trailing.remove(from) ?: continue
            leading[to] = leading[to].orEmpty() + comments
        }
        return CommentTable(
            table.fileLeading,
            leading,
            trailing,
            table.headerTrailing,
            table.endOfBlock,
            table.fileTrailing,
        )
    }

    fun file(f: SourceFile): SourceFile {
        name(f.namespace.name, f.namespace.nameSpan)
        f.annotations.forEach(::annotationNames)
        f.imports.forEach { i ->
            name(i.namespace, i.namespaceSpan)
            i.alias?.let { name(it, i.aliasSpan!!) }
        }
        return f.copy(
            declarations = f.declarations.map(::declaration),
            services = f.services.map(::service),
        )
    }

    /**
     * `schema` and `model` are keywords in 2.0 but plain identifiers in 1.x, so a 1.x name spelled
     * like one, or a qualified name with such a segment, has no 2.0 spelling.
     */
    private fun name(text: String, span: Span) {
        val keyword = text.split('.').firstOrNull { it in NEW_KEYWORDS } ?: return
        problems +=
            Diagnostic(
                LangCodes.SYNTAX,
                "`$keyword` is a keyword in 2.0 and cannot be a name",
                span,
                help = "rename it in the 1.x file, then upgrade",
            )
    }

    private fun annotationNames(a: Annotation) {
        for (arg in a.args) {
            val value =
                when (arg) {
                    is AnnotationArg.Named -> arg.value
                    is AnnotationArg.Positional -> arg.value
                }
            when (value) {
                is AnnotationValue.Lit -> literalName(value.literal)
                is AnnotationValue.Tuple ->
                    value.names.zip(value.nameSpans).forEach { (n, at) -> name(n, at) }
            }
        }
    }

    private fun literalName(literal: Literal) {
        if (literal is Literal.NameLit) name(literal.name, literal.span)
    }

    private fun declaration(d: Declaration): Declaration {
        name(d.name, d.nameSpan)
        d.annotations.forEach(::annotationNames)
        return when (d) {
            is RecordDecl -> record(d)
            is EnumDecl -> enum(d)
            is UnionDecl -> d.copy(members = d.members.map { it.copy(type = optionless(it.type)) })
            is AliasDecl -> d.copy(type = slotted(d.type))
        }
    }

    private fun enum(d: EnumDecl): EnumDecl {
        d.values.forEach {
            name(it.name, it.nameSpan)
            it.annotations.forEach(::annotationNames)
        }
        return d
    }

    private fun service(s: ServiceDecl): ServiceDecl {
        name(s.name, s.nameSpan)
        s.annotations.forEach(::annotationNames)
        return s.copy(
            operations =
                s.operations.map { op ->
                    name(op.name, op.nameSpan)
                    op.annotations.forEach(::annotationNames)
                    op.copy(
                        request = op.request?.let(::payload),
                        response = op.response?.let(::payload),
                    )
                }
        )
    }

    private fun payload(p: PayloadDecl): PayloadDecl = p.copy(type = optionless(p.type))

    private fun record(d: RecordDecl): RecordDecl {
        val keys = mutableListOf<Annotation>()
        val others = mutableListOf<Annotation>()
        for (a in d.annotations) {
            val key =
                a.args.firstOrNull {
                    a.name == "sql" &&
                        it is AnnotationArg.Named &&
                        it.name == "key" &&
                        it.value is AnnotationValue.Tuple
                } as AnnotationArg.Named?
            if (key == null) {
                others += a.copy(block = true)
                continue
            }
            val rest = a.args - key
            // The annotation's own comments stay with whichever of the two keeps its place.
            keys += id(key.value as AnnotationValue.Tuple, if (rest.isEmpty()) a.span else key.span)
            if (rest.isNotEmpty()) others += a.copy(args = rest, block = true)
        }
        return d.copy(
            fields = d.fields.map(::field),
            nested = d.nested.map(::declaration),
            annotations = keys + others,
        )
    }

    private fun id(names: AnnotationValue.Tuple, span: Span): Annotation =
        Annotation(
            "id",
            names.names.zip(names.nameSpans).map { (name, at) ->
                AnnotationArg.Positional(AnnotationValue.Lit(Literal.NameLit(name, at), at), at)
            },
            span,
            block = true,
        )

    private fun field(f: FieldDecl): FieldDecl {
        name(f.name, f.nameSpan)
        f.annotations.forEach(::annotationNames)
        f.default?.let(::literalName)
        val flags = mutableListOf<Option>()
        val kept = mutableListOf<Annotation>()
        for (a in f.annotations) {
            if (a.name != "sql") {
                kept += a
                continue
            }
            val rest =
                a.args.filter { arg ->
                    val flag = flag(arg)
                    if (flag != null) flags += Option(flag, null, arg.span)
                    flag == null
                }
            if (rest.isEmpty() && a.args.isNotEmpty()) moved += a.span to f.span
            else kept += a.copy(args = rest)
        }
        val (type, options) = type(f.type)
        return f.copy(
            type = type,
            options = flags + options,
            annotations = kept.map { trailing(it, f) },
        )
    }

    /**
     * A 2.0 field's attribute trails it when written after its name, so an annotation without a
     * comment of its own is placed just after the name; one with a comment keeps its own line, and
     * its place, above the field.
     */
    private fun trailing(a: Annotation, f: FieldDecl): Annotation {
        if (!table.trailing[a.span].isNullOrEmpty()) return a
        val name = f.nameSpan
        return a.copy(
            span =
                Span(name.file, name.endLine, name.endColumn + 1, name.endLine, name.endColumn + 1)
        )
    }

    /** The option an `@sql` argument becomes on a field, or null when it stays an argument. */
    private fun flag(arg: AnnotationArg): String? =
        when (arg) {
            is AnnotationArg.Positional ->
                when (
                    (arg.value as? AnnotationValue.Lit)?.literal.let {
                        (it as? Literal.NameLit)?.name
                    }
                ) {
                    "key" -> "id"
                    "unique" -> "unique"
                    "index" -> "index"
                    else -> null
                }
            is AnnotationArg.Named ->
                if (
                    arg.name == "strategy" &&
                        ((arg.value as? AnnotationValue.Lit)?.literal as? Literal.NameLit)?.name ==
                            "embed"
                )
                    "embed"
                else null
        }

    /** [t] mapped, with its options on itself: a type argument's or an alias's. */
    private fun slotted(t: TypeExpr): TypeExpr {
        val (type, options) = type(t)
        return type.copy(options = options)
    }

    /** [t] mapped where 2.0 takes no options: a union member or a payload. */
    private fun optionless(t: TypeExpr): TypeExpr {
        val (type, options) = type(t)
        if (options.isNotEmpty()) {
            problems +=
                Diagnostic(
                    LangCodes.SYNTAX,
                    "a union member or a payload takes no options in 2.0, so `${t.name}`'s refinements have no place",
                    t.span,
                    help =
                        "declare an alias with the refinements, `alias Name = ${t.name}(…)`, and use it here",
                )
        }
        return type
    }

    /**
     * [t] in its 2.0 form, and the options its named refinements become for the slot it sits in.
     */
    private fun type(t: TypeExpr): Pair<TypeExpr, List<Option>> {
        name(t.name, t.nameSpan)
        val positional = t.refinements.filterIsInstance<Refinement.Positional>()
        val decimal =
            t.name == "decimal" &&
                positional.size == 2 &&
                positional.all { it.value is Literal.IntLit }
        if (positional.isNotEmpty() && !decimal) {
            problems +=
                Diagnostic(
                    LangCodes.SYNTAX,
                    "`${t.name}` takes no positional refinements, and 2.0 has no spelling for them",
                    positional.first().span,
                    help = "name the refinement, `${t.name}(max = …)`, or remove it",
                )
        }
        val collection = (t.name == "list" || t.name == "map") && t.args.isNotEmpty()
        val own =
            t.refinements.filterIsInstance<Refinement.Named>().map {
                Option(optionName(it.name, collection), it.value, it.span)
            }
        if (t.name == "list" && t.args.size == 1) {
            val (element, elementOptions) = type(t.args.single())
            if (!element.list) {
                val list = element.copy(list = true, listNullable = t.nullable, span = t.span)
                return list to own + elementOptions
            }
            return t.copy(
                args = listOf(element.copy(options = elementOptions)),
                refinements = positional,
            ) to own
        }
        return t.copy(args = t.args.map(::slotted), refinements = positional) to own
    }

    private companion object {
        val NEW_KEYWORDS = setOf("schema", "model")
    }

    private fun optionName(refinement: String, collection: Boolean): String =
        when (refinement) {
            "min" -> if (collection) "minItems" else "min"
            "max" -> if (collection) "maxItems" else "max"
            "pattern" -> "match"
            else -> refinement
        }
}
