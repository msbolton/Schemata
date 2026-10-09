package io.schemata.importer

import io.schemata.lang.Parser
import io.schemata.lang.SchemataText
import io.schemata.lang.ast.Literal
import io.schemata.lang.ast.Option
import io.schemata.lang.ast.RecordDecl
import io.schemata.lang.ast.Refinement
import io.schemata.lang.ast.ReservedItem
import io.schemata.lang.ast.ServiceDecl
import io.schemata.lang.ast.SourceFile
import io.schemata.lang.ast.TypeExpr

/**
 * Reads a `schemata:` note back. A field's note is the type as a user would write it, options
 * included (`string { max 5 }`, `Line[] { minItems 1 }`), optionally followed by `; default =
 * <literal>`, or the default alone. The separator is found outside string literals and the
 * language's own parser reads the parts as a field's type and default, inside a one-field model
 * made up around the note, so a `"; default = "` inside a pattern or a default string cannot
 * mislead it. An operation's note and a service's `reserved` note are read the same way, inside a
 * service made up around them.
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
            if (defaultOnly) "f int32 = ${text.removePrefix(DEFAULT)}"
            else
                separator(text)?.let {
                    "f ${text.substring(0, it)} = ${text.substring(it + SEPARATOR.length)}"
                } ?: "f $text"
        val file = source("schema n\nmodel R {\n  $field\n}\n") ?: return null
        val record = file.declarations.singleOrNull() as? RecordDecl ?: return null
        val decl = record.fields.singleOrNull() ?: return null
        if (record.nested.isNotEmpty() || record.reserved.isNotEmpty()) return null
        if (record.annotations.isNotEmpty() || decl.annotations.isNotEmpty()) return null
        val default = decl.default?.let(::literalText)
        if (defaultOnly) return if (default == null) null else Parsed(null, false, default)
        val type = unitType(decl.type, decl.options) ?: return null
        return Parsed(type, ownNullable(decl.type), default)
    }

    private const val SEPARATOR = "; $DEFAULT"

    /** An operation note: `#4`, `get "/x"`, or `#4; get "/x"`. */
    data class OperationNote(val ordinal: Int?, val binding: String?)

    /**
     * The note read back, or null when it does not read as an ordinal, a binding, or both. The
     * ordinal comes first and a `; ` ends it; the binding is read by the language's own `binding`
     * rule and spelled as the formatter prints it, so a foreign note's odd spacing normalises.
     */
    fun parseOperationNote(note: String): OperationNote? {
        val text = note.trim()
        val ordinalText: String?
        val bindingText: String?
        if (text.startsWith("#")) {
            val at = text.indexOf("; ")
            ordinalText = if (at < 0) text else text.substring(0, at)
            bindingText = if (at < 0) null else text.substring(at + 2)
        } else {
            ordinalText = null
            bindingText = text
        }
        val ordinal =
            ordinalText?.let {
                if (!ORDINAL.matches(it)) return null
                it.substring(1).toIntOrNull()?.takeIf { n -> n >= 1 } ?: return null
            }
        val binding =
            bindingText?.let { b ->
                val service = service("op() $b")?.takeIf { it.reserved.isEmpty() }
                val decl = service?.operations?.singleOrNull()?.binding ?: return null
                "${decl.verb} ${SchemataText.string(decl.path)}"
            }
        return OperationNote(ordinal, binding)
    }

    /**
     * A service's `reserved` note (`reserved #6, "archive"`) read back, or null when it is not one
     * `reserved` statement.
     */
    fun parseReservedNote(note: String): List<UnitReserved>? {
        val service = service(note.trim()) ?: return null
        if (service.operations.isNotEmpty() || service.reserved.isEmpty()) return null
        // A range that runs backwards is not a reserved statement the importer can rebuild.
        if (service.reserved.any { it is ReservedItem.Ordinals && it.from > it.to }) return null
        return service.reserved.map {
            when (it) {
                is ReservedItem.Ordinals -> UnitReserved.Ordinals(it.from, it.to)
                is ReservedItem.Name -> UnitReserved.Name(it.name)
            }
        }
    }

    private val ORDINAL = Regex("#\\d+")

    /** [text] parsed, or null when it does not parse. */
    private fun source(text: String): SourceFile? = Parser.parse(text, "note").file

    /**
     * [body] parsed as the members of a service, when the made-up file holds that service alone.
     */
    private fun service(body: String): ServiceDecl? {
        val file = source("schema n\nservice S { $body }\n") ?: return null
        if (file.declarations.isNotEmpty()) return null
        return file.services.singleOrNull()
    }

    /**
     * Where the last `; default = ` outside a string literal starts in [text], or null. A `\` in a
     * string escapes the character after it, so an escaped quote does not end the string; a pattern
     * never ends in an odd run of backslashes, so its literal reads the same way.
     */
    private fun separator(text: String): Int? {
        var found: Int? = null
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && text.startsWith(SEPARATOR, i) -> found = i
            }
            i++
        }
        return found
    }

    /** Whether [t] itself may be null: a list's own `?` follows its `[]`. */
    private fun ownNullable(t: TypeExpr): Boolean = if (t.list) t.listNullable else t.nullable

    /**
     * [t] with [options], the options of the slot it sits in, read back as refinements, or null
     * when an option does not bound that kind of type. A list's `minItems` and `maxItems` bound its
     * size and its other options its element, which share the list's block; a map's bound its size,
     * its key's and value's sit on themselves.
     */
    private fun unitType(t: TypeExpr, options: List<Option>): UnitType? {
        if (t.inlineEnum != null || t.inlineShape != null) return null
        if (t.list) {
            val (sizes, rest) = options.partition { it.name in SIZES }
            val element =
                unitType(t.copy(list = false, listNullable = false, options = emptyList()), rest)
                    ?: return null
            return UnitType.ListOf(element, t.nullable, refinements(sizes) ?: return null)
        }
        val (sizes, rest) = options.partition { it.name in SIZES }
        return when {
            t.name == "list" && t.args.size == 1 -> {
                val element = t.args[0]
                if (rest.isNotEmpty()) return null
                UnitType.ListOf(
                    unitType(element, element.options) ?: return null,
                    ownNullable(element),
                    refinements(sizes) ?: return null,
                )
            }
            t.name == "map" && t.args.size == 2 -> {
                val (key, value) = t.args
                if (rest.isNotEmpty()) return null
                UnitType.MapOf(
                    unitType(key, key.options) ?: return null,
                    unitType(value, value.options) ?: return null,
                    ownNullable(value),
                    refinements(sizes) ?: return null,
                )
            }
            t.name in BUILTINS && t.args.isEmpty() -> {
                if (sizes.isNotEmpty()) return null
                val positional = t.refinements.filterIsInstance<Refinement.Positional>()
                val precision =
                    if (t.name == "decimal" && positional.size == 2)
                        listOf("p" to literalText(positional[0].value)) +
                            listOf("s" to literalText(positional[1].value))
                    else if (positional.isEmpty()) emptyList() else return null
                UnitType.Scalar(t.name, precision + (refinements(rest) ?: return null))
            }
            options.isEmpty() && t.args.isEmpty() -> UnitType.Ref(t.name)
            else -> null
        }
    }

    /**
     * [options] as the refinements they spell: `match` is a `pattern`, a size's `minItems` and
     * `maxItems` its `min` and `max`. Null when one is a flag or names no bound.
     */
    private fun refinements(options: List<Option>): List<Pair<String, String>>? =
        options.map { o ->
            val value = o.value ?: return null
            when (o.name) {
                "min",
                "max" -> o.name to literalText(value)
                "minItems" -> "min" to literalText(value)
                "maxItems" -> "max" to literalText(value)
                "match" ->
                    "pattern" to
                        ((value as? Literal.StringLit)?.let { SchemataText.pattern(it.value) }
                            ?: return null)
                else -> return null
            }
        }

    private val SIZES = setOf("minItems", "maxItems")

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
