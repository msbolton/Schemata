package io.schemata.importer

import io.schemata.lang.SchemataText

/**
 * Prints a [SchemataUnit] as Schemata source text. The output need not be pretty: one construct per
 * line with simple indentation and one space between the parts of a line is enough, since
 * [io.schemata.lang.format.Formatter] decides the final canonical layout, aligning the columns.
 *
 * A field prints as `name Type { options } @attributes = default`, all on its line: attributes
 * written after a field belong to it only when they start on its line. A record's annotations close
 * its body as block attributes (`@@xsd(name: "…")`), after its composite key, uniques, and indexes.
 * A type's refinements print as the options of the slot it sits in: a field's, a union member's, or
 * a type argument's. A list is `T[]`, its element's options sharing the field's block after the
 * list's own; a list of lists keeps the outer `list<…>`, since a type takes one `[]`.
 */
object SchemataEmitter {
    private const val INDENT = "  "

    fun emit(unit: SchemataUnit): String = buildString {
        unit.doc?.let { docLines(it, "").forEach(::appendLine) }
        appendLine("schema ${unit.namespace}" + trailing(unit.annotations.map(::annotation)))
        if (unit.imports.isNotEmpty()) {
            appendLine()
            unit.imports.forEach { appendLine("import $it") }
        }
        unit.declarations.forEach { d ->
            appendLine()
            append(declaration(d, ""))
        }
        unit.services.forEach {
            appendLine()
            append(service(it, ""))
        }
    }

    private fun service(s: UnitService, indent: String): String = buildString {
        val inner = indent + INDENT
        s.doc?.let { docLines(it, indent).forEach(::appendLine) }
        if (s.deprecated) appendLine("$indent@deprecated")
        s.annotations.forEach { appendLine(indent + annotation(it)) }
        checkOrdinals(s.name, s.operations.map { it.ordinal })
        appendLine(indent + "service ${s.name} {")
        s.operations.forEach { op -> operationLines(op, inner).forEach(::appendLine) }
        reservedLine(s.reserved, inner)?.let(::appendLine)
        appendLine(indent + "}")
    }

    private fun operationLines(op: UnitOperation, indent: String): List<String> = buildList {
        op.doc?.let { addAll(docLines(it, indent)) }
        if (op.deprecated) add("$indent@deprecated")
        op.annotations.forEach { add(indent + annotation(it)) }
        val request = op.request?.let { payload(it) } ?: ""
        val response = op.response?.let { ": " + payload(it) } ?: ""
        val binding = op.binding?.let { "  $it" } ?: ""
        add("$indent${ordinalPrefix(op.ordinal)}${op.name}($request)$response$binding")
    }

    private fun payload(p: UnitPayload): String = (if (p.stream) "stream " else "") + p.type.name

    private fun docLines(doc: String, indent: String): List<String> =
        doc.lines().map { if (it.isEmpty()) "$indent///" else "$indent/// $it" }

    private fun annotation(a: UnitAnnotation): String = "@" + attribute(a)

    /** `xsd(name: "…")` or `xsd(attribute)`: an annotation without its `@` or `@@`. */
    private fun attribute(a: UnitAnnotation): String =
        "${a.target}(${a.key}${a.value?.let { ": $it" } ?: ""})"

    /** [parts] each after one space, or nothing. */
    private fun trailing(parts: List<String>): String = parts.joinToString("") { " $it" }

    private fun declaration(d: UnitDecl, indent: String): String = buildString {
        d.doc?.let { docLines(it, indent).forEach(::appendLine) }
        // A record's annotations close its body instead; see [blockAttributes].
        if (d !is UnitRecord) {
            if (d.deprecated) appendLine("$indent@deprecated")
            d.annotations.forEach { appendLine(indent + annotation(it)) }
        }
        when (d) {
            is UnitRecord -> append(record(d, indent))
            is UnitEnum -> append(enum(d, indent))
            is UnitUnion -> append(union(d, indent))
        }
    }

    private fun record(d: UnitRecord, indent: String): String = buildString {
        val inner = indent + INDENT
        checkOrdinals(d.name, d.fields.map { it.ordinal })
        appendLine(indent + "model ${d.name} {")
        d.fields.forEach { f -> fieldLines(f, inner).forEach(::appendLine) }
        d.nested.forEach { append(declaration(it, inner)) }
        reservedLine(d.reserved, inner)?.let(::appendLine)
        blockAttributes(d).forEach { appendLine("$inner@@$it") }
        appendLine(indent + "}")
    }

    /** A record's block attributes, without their `@@`, in the order they close its body. */
    private fun blockAttributes(d: UnitRecord): List<String> = buildList {
        if (d.key.isNotEmpty()) add("id(${d.key.joinToString(", ")})")
        d.uniques.forEach { add("unique(${it.joinToString(", ")})") }
        d.indexes.forEach { add("index(${it.joinToString(", ")})") }
        if (d.deprecated) add("deprecated")
        d.annotations.forEach { add(attribute(it)) }
    }

    private fun fieldLines(f: UnitField, indent: String): List<String> = buildList {
        f.doc?.let { addAll(docLines(it, indent)) }
        val (type, typeOptions) = slot(f.type, f.nullable)
        val options = f.options.map { (name, value) -> option(name, value) } + typeOptions
        val attributes = buildList {
            if (f.deprecated) add("@deprecated")
            f.onDelete?.let { add("@relation(onDelete: $it)") }
            f.annotations.forEach { add(annotation(it)) }
        }
        val default = f.default?.let { " = $it" } ?: ""
        add(
            "$indent${ordinalPrefix(f.ordinal)}${f.name} $type${block(options)}" +
                trailing(attributes) +
                default
        )
    }

    private fun enum(d: UnitEnum, indent: String): String = buildString {
        val inner = indent + INDENT
        checkOrdinals(d.name, d.values.map { it.ordinal })
        appendLine(indent + "enum ${d.name} {")
        d.values.forEach { v ->
            v.doc?.let { docLines(it, inner).forEach(::appendLine) }
            if (v.deprecated) appendLine("$inner@deprecated")
            v.annotations.forEach { appendLine(inner + annotation(it)) }
            appendLine(inner + ordinalPrefix(v.ordinal) + v.name)
        }
        reservedLine(d.reserved, inner)?.let(::appendLine)
        appendLine(indent + "}")
    }

    private fun union(d: UnitUnion, indent: String): String = buildString {
        val inner = indent + INDENT
        checkOrdinals(d.name, d.members.map { it.ordinal })
        appendLine(indent + "union ${d.name} =")
        d.members.forEachIndexed { i, m ->
            m.doc?.let { docLines(it, inner).forEach(::appendLine) }
            val sep = if (i == d.members.lastIndex) "" else " |"
            appendLine(inner + ordinalPrefix(m.ordinal) + slotted(m.type, false) + sep)
        }
    }

    private fun ordinalPrefix(ordinal: Int?): String = ordinal?.let { "#$it " } ?: ""

    /**
     * The language takes ordinals on every member of a declaration or on none; an importer that
     * breaks that rule has a bug, which is better caught here than as an analysis error on output
     * the user did not write.
     */
    private fun checkOrdinals(name: String, ordinals: List<Int?>) {
        check(ordinals.all { it == null } || ordinals.all { it != null }) {
            "declaration '$name' has ordinals on some members but not all"
        }
    }

    private fun reservedLine(items: List<UnitReserved>, indent: String): String? {
        if (items.isEmpty()) return null
        val parts =
            items.map {
                when (it) {
                    is UnitReserved.Ordinals ->
                        if (it.from == it.to) "#${it.from}" else "#${it.from}..#${it.to}"
                    is UnitReserved.Name -> SchemataText.string(it.name)
                }
            }
        return indent + "reserved " + parts.joinToString(", ")
    }

    private fun option(name: String, value: String?): String =
        if (value == null) name else "$name $value"

    /** ` { a, b }`, or nothing for no options. */
    private fun block(options: List<String>): String =
        if (options.isEmpty()) "" else " { ${options.joinToString(", ")} }"

    /** [t] with its options after it, where the type carries them itself. */
    private fun slotted(t: UnitType, nullable: Boolean): String {
        val (type, options) = slot(t, nullable)
        return type + block(options)
    }

    /**
     * [t] as 2.0 writes it, `?` included when [nullable], and the options its refinements become
     * for the slot it sits in. A scalar's `min`, `max`, and `pattern` are the options `min`, `max`,
     * and `match`; a list's or map's own `min` and `max` bound its size, `minItems` and `maxItems`.
     */
    private fun slot(t: UnitType, nullable: Boolean): Pair<String, List<String>> {
        val (core, options) =
            when (t) {
                is UnitType.Scalar -> scalar(t)
                is UnitType.Ref -> t.name to emptyList()
                is UnitType.ListOf -> {
                    val own = sizes(t.refinements)
                    val element = t.element
                    // a map's own size bound would share the list's block: keep `list<…>`
                    if (
                        element is UnitType.ListOf ||
                            (element is UnitType.MapOf && element.refinements.isNotEmpty())
                    ) {
                        "list<${slotted(t.element, t.nullableElement)}>" to own
                    } else {
                        val (text, elementOptions) = slot(element, t.nullableElement)
                        "$text[]" to own + elementOptions
                    }
                }
                is UnitType.MapOf ->
                    "map<${slotted(t.key, false)}, ${slotted(t.value, t.nullableValue)}>" to
                        sizes(t.refinements)
            }
        return (if (nullable) "$core?" else core) to options
    }

    private fun sizes(r: List<Pair<String, String>>): List<String> =
        r.map { (key, value) ->
            val name =
                when (key) {
                    "min" -> "minItems"
                    "max" -> "maxItems"
                    else -> key
                }
            option(name, value)
        }

    /** A scalar and its options; `decimal`'s precision and scale stay in the type. */
    private fun scalar(t: UnitType.Scalar): Pair<String, List<String>> {
        val positional =
            if (t.builtin == "decimal")
                t.refinements
                    .filter { it.first == "p" || it.first == "s" }
                    .sortedBy { if (it.first == "p") 0 else 1 }
                    .map { it.second }
            else emptyList()
        val named =
            t.refinements
                .filterNot { t.builtin == "decimal" && (it.first == "p" || it.first == "s") }
                .map { (key, value) -> option(if (key == "pattern") "match" else key, value) }
        val type =
            if (positional.isEmpty()) t.builtin
            else "${t.builtin}(${positional.joinToString(", ")})"
        return type to named
    }
}
