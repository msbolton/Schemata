package io.schemata.importer

import io.schemata.lang.Parser
import io.schemata.lang.SchemataText
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.TypeExpr

/**
 * Reads a `schemata:` note back: the type as a user would write it, optionally followed by `;
 * default = <literal>`, or the default alone. The language's own parser does the reading, inside a
 * one-field record made up around the note, so a `"; "` inside a pattern or a default string cannot
 * mislead it.
 */
object NoteText {
    /**
     * [type] is null for a note that gives only a default; [nullable] is the type's own `?`;
     * [default] is the literal as Schemata source, quoted and escaped.
     */
    data class Parsed(val type: UnitType?, val nullable: Boolean, val default: String?)

    private const val DEFAULT = "default = "

    /** The note read back, or null when it does not read as a type, a default, or both. */
    fun parse(note: String): Parsed? {
        val text = note.trim()
        val defaultOnly = text.startsWith(DEFAULT)
        val field =
            if (defaultOnly) "f: int32 = ${text.removePrefix(DEFAULT)}"
            else "f: ${text.replaceFirst("; $DEFAULT", " = ")}"
        val file = Parser.parse("namespace n\nrecord R { $field }\n", "note").file ?: return null
        val record = file.declarations.singleOrNull() as? RecordDecl ?: return null
        val decl = record.fields.singleOrNull() ?: return null
        if (record.nested.isNotEmpty() || record.reserved.isNotEmpty()) return null
        val default = decl.default?.let(::literalText)
        if (defaultOnly) return if (default == null) null else Parsed(null, false, default)
        return Parsed(unitType(decl.type), decl.type.nullable, default)
    }

    private fun unitType(t: TypeExpr): UnitType =
        when {
            t.name == "list" && t.args.size == 1 ->
                UnitType.ListOf(unitType(t.args[0]), t.args[0].nullable, refinements(t.refinements))
            t.name == "map" && t.args.size == 2 ->
                UnitType.MapOf(
                    unitType(t.args[0]),
                    unitType(t.args[1]),
                    t.args[1].nullable,
                    refinements(t.refinements),
                )
            t.name in BUILTINS ->
                UnitType.Scalar(t.name, refinements(t.refinements, decimal = t.name == "decimal"))
            else -> UnitType.Ref(t.name)
        }

    /**
     * Named refinements as written; a decimal's two positional arguments are its precision `p` and
     * scale `s`.
     */
    private fun refinements(
        rs: List<Refinement>,
        decimal: Boolean = false,
    ): List<Pair<String, String>> {
        var positional = 0
        return rs.map { r ->
            when (r) {
                is Refinement.Named ->
                    r.name to
                        if (r.name == "pattern" && r.value is Literal.StringLit)
                            SchemataText.pattern((r.value as Literal.StringLit).value)
                        else literalText(r.value)
                is Refinement.Positional ->
                    (if (decimal && positional++ == 0) "p" else "s") to literalText(r.value)
            }
        }
    }

    private fun literalText(l: Literal): String =
        when (l) {
            is Literal.IntLit -> l.value.toString()
            is Literal.FloatLit -> l.text
            is Literal.StringLit -> SchemataText.string(l.value)
            is Literal.BoolLit -> l.value.toString()
            is Literal.NameLit -> l.name
        }

    private val BUILTINS =
        setOf(
            "bool",
            "int32",
            "int64",
            "float32",
            "float64",
            "decimal",
            "string",
            "uuid",
            "bytes",
            "date",
            "time",
            "instant",
            "duration",
        )
}
