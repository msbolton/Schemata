package io.schemata.importer

import io.schemata.lang.SchemataText

/**
 * Prints a [SchemataUnit] as Schemata source text. The output need not be pretty: one construct per
 * line with simple indentation is enough, since [io.schemata.lang.format.Formatter] decides the
 * final canonical layout.
 */
object SchemataEmitter {
    private const val INDENT = "  "

    fun emit(unit: SchemataUnit): String = buildString {
        unit.doc?.let { docLines(it, "").forEach(::appendLine) }
        unit.annotations.forEach { appendLine(annotation(it)) }
        appendLine("namespace ${unit.namespace}")
        if (unit.imports.isNotEmpty()) {
            appendLine()
            unit.imports.forEach { appendLine("import $it") }
        }
        unit.declarations.forEach { d ->
            appendLine()
            append(declaration(d, ""))
        }
    }

    private fun docLines(doc: String, indent: String): List<String> =
        doc.lines().map { if (it.isEmpty()) "$indent///" else "$indent/// $it" }

    private fun annotation(a: UnitAnnotation): String =
        "@${a.target}(${a.key}${a.value?.let { " = $it" } ?: ""})"

    private fun declaration(d: UnitDecl, indent: String): String = buildString {
        d.doc?.let { docLines(it, indent).forEach(::appendLine) }
        if (d.deprecated) appendLine("$indent@deprecated")
        d.annotations.forEach { appendLine(indent + annotation(it)) }
        when (d) {
            is UnitRecord -> append(record(d, indent))
            is UnitEnum -> append(enum(d, indent))
            is UnitUnion -> append(union(d, indent))
        }
    }

    private fun record(d: UnitRecord, indent: String): String = buildString {
        val inner = indent + INDENT
        checkOrdinals(d.name, d.fields.map { it.ordinal })
        appendLine(indent + "record ${d.name} {")
        d.fields.forEach { f -> fieldLines(f, inner).forEach(::appendLine) }
        d.nested.forEach { append(declaration(it, inner)) }
        reservedLine(d.reserved, inner)?.let(::appendLine)
        appendLine(indent + "}")
    }

    private fun fieldLines(f: UnitField, indent: String): List<String> = buildList {
        f.doc?.let { addAll(docLines(it, indent)) }
        if (f.deprecated) add("$indent@deprecated")
        f.annotations.forEach { add(indent + annotation(it)) }
        val type = typeString(f.type) + (if (f.nullable) "?" else "")
        val default = f.default?.let { " = $it" } ?: ""
        add("$indent${ordinalPrefix(f.ordinal)}${f.name}: $type$default")
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
            appendLine(inner + ordinalPrefix(m.ordinal) + typeString(m.type) + sep)
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

    private fun refinementsString(r: List<Pair<String, String>>): String =
        if (r.isEmpty()) "" else "(" + r.joinToString(", ") { "${it.first} = ${it.second}" } + ")"

    private fun scalarString(t: UnitType.Scalar): String {
        if (t.refinements.isEmpty()) return t.builtin
        val args =
            if (t.builtin == "decimal") {
                val positional =
                    t.refinements
                        .filter { it.first == "p" || it.first == "s" }
                        .sortedBy { if (it.first == "p") 0 else 1 }
                        .map { it.second }
                val named =
                    t.refinements
                        .filterNot { it.first == "p" || it.first == "s" }
                        .map { "${it.first} = ${it.second}" }
                positional + named
            } else {
                t.refinements.map { "${it.first} = ${it.second}" }
            }
        return "${t.builtin}(${args.joinToString(", ")})"
    }

    private fun typeString(t: UnitType): String =
        when (t) {
            is UnitType.Scalar -> scalarString(t)
            is UnitType.Ref -> t.name
            is UnitType.ListOf -> {
                val element = typeString(t.element) + (if (t.nullableElement) "?" else "")
                "list<$element>${refinementsString(t.refinements)}"
            }
            is UnitType.MapOf -> {
                val key = typeString(t.key)
                val value = typeString(t.value) + (if (t.nullableValue) "?" else "")
                "map<$key, $value>${refinementsString(t.refinements)}"
            }
        }
}
