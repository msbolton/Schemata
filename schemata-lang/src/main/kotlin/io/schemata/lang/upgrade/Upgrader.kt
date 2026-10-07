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
     * as it was, and one that is not 1.x (it does not start with `namespace`) fails with its 2.0
     * syntax errors.
     */
    fun upgrade(input: String, path: String): FormatResult {
        val source = Formatter.normalize(input)
        val current = Parser.parse(source, path)
        if (current.file != null) return FormatResult.Formatted(input)
        if (current.diagnostics.none { it.code == LangCodes.LEGACY_SYNTAX })
            return FormatResult.Failed(current.diagnostics)
        val parsed = Parser.parse1ForUpgrade(source, path)
        val file = parsed.file ?: return FormatResult.Failed(parsed.diagnostics)
        val mapper = Mapper(parsed.comments)
        val mapped = mapper.file(file)
        if (mapper.problems.isNotEmpty()) return FormatResult.Failed(mapper.problems)
        val text = Formatter.print(mapped, mapper.comments(), source)
        return FormatResult.Formatted(Formatter.checked(text, parsed, path))
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
 * - A union member's refinements become the member's options (`| string { max 34 }`).
 * - A name spelled `schema` or `model`, keywords in 2.0, becomes `schema_value` or `model_value`
 *   wherever it is declared or referred to. Only a lower_snake name can be spelled like a keyword,
 *   so the names that reach SQL are a namespace's segments and a field's: a namespace keeps its SQL
 *   schema name through `@sql(schema: …)` and a field its column through `@sql(column: …)`, unless
 *   it already names one. A rename onto a name already declared in the same scope is reported
 *   instead.
 * - What 2.0 cannot say is reported, not printed: a refinement on a payload (2.0 gives it no
 *   options), a positional refinement on anything but `decimal(p, s)`, and a bound given as a bare
 *   name.
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

    /**
     * A namespace with a renamed segment keeps its SQL schema name, which is its 1.x last segment,
     * through `@sql(schema: …)` on the header unless the file already names one.
     */
    fun file(f: SourceFile): SourceFile {
        val annotations = f.annotations.map(::renamed).toMutableList()
        val segments = f.namespace.name.split('.')
        if (segments.any { it in NEW_KEYWORDS } && annotations.none { sqlNames(it, "schema") })
            annotations += sqlName("schema", segments.last(), f.namespace.nameSpan)
        collisions(
            f.declarations.map { it.name to it.nameSpan } +
                f.services.map { it.name to it.nameSpan }
        )
        return f.copy(
            namespace = f.namespace.copy(name = renamed(f.namespace.name)),
            annotations = annotations,
            imports =
                f.imports.map {
                    it.copy(namespace = renamed(it.namespace), alias = it.alias?.let(::renamed))
                },
            declarations = f.declarations.map(::declaration),
            services = f.services.map(::service),
        )
    }

    /**
     * [text] with each dot-separated segment that is a 2.0 keyword (`schema`, `model`) suffixed
     * with `_value`, as the importers spell a keyword name; the result is still lower_snake. Every
     * name is renamed by this one rule, declarations and references alike, so a reference keeps
     * pointing at what it named.
     */
    private fun renamed(text: String): String =
        text.split('.').joinToString(".") { if (it in NEW_KEYWORDS) it + SUFFIX else it }

    /**
     * Reports each of [names], the names declared in one scope, whose rename is already declared
     * there: renaming it would make two of them one.
     */
    private fun collisions(names: List<Pair<String, Span>>) {
        val declared = names.map { it.first }.toSet()
        for ((name, at) in names) {
            if (name !in NEW_KEYWORDS || name + SUFFIX !in declared) continue
            problems +=
                Diagnostic(
                    LangCodes.SYNTAX,
                    "cannot rename '$name': '$name$SUFFIX' is already declared",
                    at,
                    help = "rename one of them in the 1.x file, then upgrade",
                )
        }
    }

    private fun renamed(a: Annotation): Annotation =
        a.copy(
            args =
                a.args.map {
                    when (it) {
                        is AnnotationArg.Named -> it.copy(value = renamed(it.value))
                        is AnnotationArg.Positional -> it.copy(value = renamed(it.value))
                    }
                }
        )

    private fun renamed(v: AnnotationValue): AnnotationValue =
        when (v) {
            is AnnotationValue.Lit -> v.copy(literal = renamed(v.literal))
            is AnnotationValue.Tuple -> v.copy(names = v.names.map(::renamed))
        }

    private fun renamed(l: Literal): Literal =
        if (l is Literal.NameLit) l.copy(name = renamed(l.name)) else l

    /** Whether [a] is an `@sql` naming [key] already. */
    private fun sqlNames(a: Annotation, key: String): Boolean =
        a.name == "sql" && a.args.any { it is AnnotationArg.Named && it.name == key }

    /**
     * `@sql(key: "value")`, written by the upgrader to keep a renamed name's SQL name. It has no
     * source text, so its positions are line 0, which the printer reads as "print from the value".
     */
    private fun sqlName(key: String, value: String, near: Span): Annotation {
        val at = Span(near.file, 0, 0, 0, 0)
        val literal = AnnotationValue.Lit(Literal.StringLit(value, at), at)
        return Annotation("sql", listOf(AnnotationArg.Named(key, literal, at)), at)
    }

    private fun declaration(d: Declaration): Declaration {
        val name = renamed(d.name)
        val annotations = d.annotations.map(::renamed)
        return when (d) {
            is RecordDecl -> record(d.copy(annotations = annotations))
            is EnumDecl -> {
                collisions(d.values.map { it.name to it.nameSpan })
                d.copy(
                    name = name,
                    annotations = annotations,
                    values =
                        d.values.map {
                            it.copy(
                                name = renamed(it.name),
                                annotations = it.annotations.map(::renamed),
                            )
                        },
                )
            }
            is UnionDecl ->
                d.copy(
                    name = name,
                    annotations = annotations,
                    members =
                        d.members.map {
                            val (type, options) = type(it.type)
                            it.copy(type = type, options = options)
                        },
                )
            is AliasDecl -> d.copy(name = name, annotations = annotations, type = slotted(d.type))
        }
    }

    private fun service(s: ServiceDecl): ServiceDecl {
        collisions(s.operations.map { it.name to it.nameSpan })
        return s.copy(
            name = renamed(s.name),
            annotations = s.annotations.map(::renamed),
            operations =
                s.operations.map { op ->
                    op.copy(
                        name = renamed(op.name),
                        annotations = op.annotations.map(::renamed),
                        request = op.request?.let(::payload),
                        response = op.response?.let(::payload),
                    )
                },
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
        collisions(d.fields.map { it.name to it.nameSpan })
        collisions(d.nested.map { it.name to it.nameSpan })
        return d.copy(
            name = renamed(d.name),
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
        val flags = mutableListOf<Option>()
        val kept = mutableListOf<Annotation>()
        for (a in f.annotations.map(::renamed)) {
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
        // A renamed field keeps its SQL column name, which is its name.
        if (f.name in NEW_KEYWORDS && kept.none { sqlNames(it, "column") })
            kept += sqlName("column", f.name, f.nameSpan)
        val (type, options) = type(f.type)
        return f.copy(
            name = renamed(f.name),
            default = f.default?.let(::renamed),
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

    /** [t] mapped where 2.0 takes no options: a payload. */
    private fun optionless(t: TypeExpr): TypeExpr {
        val (type, options) = type(t)
        if (options.isNotEmpty()) {
            problems +=
                Diagnostic(
                    LangCodes.SYNTAX,
                    "a payload takes no options in 2.0, so `${t.name}`'s refinements have no place",
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
        val named = t.refinements.filterIsInstance<Refinement.Named>()
        // An option's value is a number, a string, or a boolean, never a bare name.
        named
            .filter { it.value is Literal.NameLit }
            .forEach {
                problems +=
                    Diagnostic(
                        LangCodes.SYNTAX,
                        "`${it.name}` takes a number or a string, and 2.0 has no spelling for a name here",
                        it.value.span,
                        help = "write the bound as a literal, or remove it",
                    )
            }
        val own = named.map { Option(optionName(it.name, collection), it.value, it.span) }
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
        return t.copy(
            name = renamed(t.name),
            args = t.args.map(::slotted),
            refinements = positional,
        ) to own
    }

    private companion object {
        val NEW_KEYWORDS = setOf("schema", "model")
        const val SUFFIX = "_value"
    }

    private fun optionName(refinement: String, collection: Boolean): String =
        when (refinement) {
            "min" -> if (collection) "minItems" else "min"
            "max" -> if (collection) "maxItems" else "max"
            "pattern" -> "match"
            else -> refinement
        }
}
