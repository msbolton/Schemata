package io.schemata.lang.format

import io.schemata.lang.ast.AliasDecl
import io.schemata.lang.ast.Declaration
import io.schemata.lang.ast.EnumDecl
import io.schemata.lang.ast.FieldDecl
import io.schemata.lang.ast.Option
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr
import io.schemata.lang.ast.UnionDecl

/**
 * Rewrites `list<T>` as `T[]`, the one way 2.0 spells a list, wherever the two say the same thing:
 * `list<T?>` is `T?[]`, `list<T>?` is `T[]?`, and the element's options join the slot's own block
 * after the list's. `list<…>` stays where `T[]` cannot say it: a list of lists (a type takes one
 * `[]`), a list of maps with a size bound of their own (the map's `minItems` would share the block
 * with the list's), an element option the slot's block already has, and a list written with
 * parenthesised refinements, which analysis reports.
 */
internal object CanonicalLists {
    fun file(f: SourceFile): SourceFile = f.copy(declarations = f.declarations.map(::declaration))

    private fun declaration(d: Declaration): Declaration =
        when (d) {
            is RecordDecl ->
                d.copy(fields = d.fields.map(::field), nested = d.nested.map(::declaration))
            is EnumDecl -> d
            is UnionDecl ->
                d.copy(
                    members =
                        d.members.map { m ->
                            val (type, options) = slot(m.type, m.options)
                            m.copy(type = type, options = options)
                        }
                )
            is AliasDecl -> d.copy(type = own(d.type))
        }

    private fun field(f: FieldDecl): FieldDecl {
        val shape = f.type.inlineShape
        if (shape != null)
            return f.copy(type = f.type.copy(inlineShape = declaration(shape) as RecordDecl))
        val (type, options) = slot(f.type, f.options)
        return f.copy(type = type, options = options)
    }

    /** A type that carries its options on itself: a type argument's, or an alias's. */
    private fun own(t: TypeExpr): TypeExpr {
        val (type, options) = slot(t.copy(options = emptyList()), t.options)
        return type.copy(options = options)
    }

    /** [t] written in a slot whose options are [options], and that slot's options after. */
    private fun slot(t: TypeExpr, options: List<Option>): Pair<TypeExpr, List<Option>> {
        val args = t.args.map(::own)
        val written = t.copy(args = args)
        if (t.name != "list" || t.list || args.size != 1 || t.refinements.isNotEmpty())
            return written to options
        val element = args.single()
        val elementOptions = element.options
        val keep =
            element.list ||
                element.name == "list" ||
                element.inlineEnum != null ||
                element.inlineShape != null ||
                elementOptions.any { it.name in SIZES } ||
                elementOptions.any { e -> options.any { it.name == e.name } }
        if (keep) return written to options
        val list =
            element.copy(
                list = true,
                listNullable = t.nullable,
                options = emptyList(),
                span = t.span,
            )
        return list to options + elementOptions
    }

    private val SIZES = setOf("minItems", "maxItems")
}
