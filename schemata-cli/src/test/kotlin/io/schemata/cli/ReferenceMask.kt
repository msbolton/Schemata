package io.schemata.cli

import io.schemata.core.ir.AnnotationValue
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.keyFields
import io.schemata.core.ir.selfAndNested
import io.schemata.core.ir.storedFields
import io.schemata.target.Names
import io.schemata.target.keyRecordName

/**
 * What a reference by key changes in a document target's output, and that output with those changes
 * left out, so the equivalence test can require the rest to match. [fields] are the names a
 * reference field is written under, before and after (`customer`, `customer_id`, and any name
 * override of either), and the stems of union members that stand for a keyed model; [keys] are the
 * `<Target>Key` types a composite key declares. [owners] says which declaration (by its simple
 * name) writes which of [fields], so a name is masked only inside the block of a model that
 * references by key under it, never in another model that merely shares the name; a block no entry
 * names is masked by [fields] as a whole.
 */
internal data class References(
    val fields: Set<String>,
    val keys: Set<String>,
    val owners: Map<String, Set<String>> = emptyMap(),
) {
    /**
     * Whether [line] names one of the references, as a diagnostic about one does (`field
     * 'Order.customer_id': …`).
     */
    fun mentions(line: String): Boolean =
        (fields + keys).any {
            Regex("(?<![A-Za-z0-9_])${Regex.escape(it)}(?![A-Za-z0-9_])").containsMatchIn(line)
        }

    companion object {
        val NONE = References(emptySet(), emptySet())

        /** The references of [schema]: every stored field, list, map, or union member by key. */
        fun of(schema: Schema): References {
            val fields = mutableSetOf<String>()
            val keys = mutableSetOf<String>()
            val owners = mutableMapOf<String, MutableSet<String>>()
            fun keyed(type: Type): RecordType? =
                when (type) {
                    is Ref ->
                        (schema.lookupOrNull(type.target) as? RecordType)?.takeIf {
                            !type.relation.embed && it.keyFields().isNotEmpty()
                        }
                    is ListOf -> keyed(type.element)
                    is MapOf -> keyed(type.value)
                    else -> null
                }
            fun keyRecord(model: RecordType) {
                keys += keyRecordName(model.qualifiedName).simpleName
                model.annotations.entries.values.forEach { values ->
                    (values["name"] as? AnnotationValue.Str)?.let { keys += it.value + "Key" }
                }
            }
            schema.namespaces
                .flatMap { ns -> ns.declarations.flatMap { it.selfAndNested() } }
                .forEach { decl ->
                    when (decl) {
                        is RecordType -> {
                            owners.getOrPut(decl.name) { mutableSetOf() }
                            decl.storedFields.forEach { field ->
                                val model = keyed(field.type) ?: return@forEach
                                val key = model.keyFields()
                                val overrides =
                                    field.annotations.entries.values.mapNotNull {
                                        (it["name"] as? AnnotationValue.Str)?.value
                                    }
                                (listOf(field.name) + overrides).forEach { name ->
                                    val own = owners.getOrPut(decl.name) { mutableSetOf() }
                                    fields += name
                                    own += name
                                    if (key.size == 1 && field.type is Ref) {
                                        fields += "${name}_${key.single().name}"
                                        own += "${name}_${key.single().name}"
                                    }
                                }
                                if (key.size > 1) keyRecord(model)
                            }
                        }
                        is UnionType -> {
                            owners.getOrPut(decl.name) { mutableSetOf() }
                            decl.members.forEach { member ->
                                val model = keyed(member.type) ?: return@forEach
                                fields += Names.snakeCase(model.name)
                                owners.getOrPut(decl.name) { mutableSetOf() } +=
                                    Names.snakeCase(model.name)
                                if (model.keyFields().size > 1) keyRecord(model)
                            }
                        }
                        else -> Unit
                    }
                }
            return References(fields, keys, owners)
        }
    }
}

/**
 * The two versions of one output with the constructs a reference by key changes left out, read by
 * the output's own structure rather than by any line that happens to name a reference:
 * - Protobuf: a field declared under a reference's name (`Customer customer = 2;`, `string
 *   customer_id = 2;`, a oneof case), a `message <Target>Key` block, and an `import` line only one
 *   version has;
 * - XSD: an `<xs:element>` or `<xs:attribute>` named for a reference, with its body, an
 *   `<xs:complexType name="<Target>Key…">` with its body, an `<xs:import>` only one version has,
 *   and an `xmlns:<prefix>` attribute only one version declares (the attribute, never the line or
 *   element that holds it);
 * - JSON Schema and OpenAPI: a property named for a reference, with its body, an entry of a
 *   `required` list naming one, and a `<Target>Key` definition or component.
 *
 * Blank runs collapse to one line and trailing commas go, since an entry removed last moves the
 * comma before it. The format is read off the output's path under the target's directory.
 */
internal object ReferenceMask {
    fun mask(path: String, before: String, after: String, refs: References): Pair<String, String> {
        val format = path.substringBefore('/')
        fun one(text: String, other: String): String {
            val lines =
                when (format) {
                    "proto" -> proto(text.lines(), other, refs)
                    "xsd" -> xsd(text.lines(), other, refs)
                    "jsonschema",
                    "openapi" -> json(text.lines(), refs)
                    else -> text.lines()
                }
            return tidy(lines)
        }
        return one(before, after) to one(after, before)
    }

    private fun proto(lines: List<String>, other: String, refs: References): List<String> {
        val otherLines = other.lines().toSet()
        val field = names(refs.fields, capture = true)
        val fieldLine =
            Regex("""^\s*(?:repeated |optional )?(?:map<[^>]*>|[\w.]+) $field = \d+;.*$""")
        val keyMessage = Regex("""^\s*message ${names(refs.keys)} \{$""")
        return blocks(lines, PROTO_MESSAGE, refs.owners) { line, scope ->
            when {
                PROTO_IMPORT.matches(line) && line !in otherLines -> Drop.LINE
                inScope(fieldLine, line, scope) -> Drop.LINE
                refs.keys.isNotEmpty() && keyMessage.matches(line) -> Drop.BLOCK
                else -> Drop.NONE
            }
        }
    }

    private fun xsd(lines: List<String>, other: String, refs: References): List<String> {
        val otherLines = other.lines().toSet()
        val otherNamespaces = XMLNS.findAll(other).map { it.value.trim() }.toSet()
        val element =
            Regex(
                """^\s*<xs:(?:element|attribute) name="${names(refs.fields, capture = true)}"[ >/].*$"""
            )
        val keyType = Regex("""^\s*<xs:complexType name="${names(refs.keys)}\w*"[ >].*$""")
        val withoutNamespaces =
            lines.mapNotNull { line ->
                val kept =
                    XMLNS.replace(line) { if (it.value.trim() in otherNamespaces) it.value else "" }
                if (kept.isBlank() && line.isNotBlank()) null else kept
            }
        return blocks(withoutNamespaces, XSD_TYPE, refs.owners) { line, scope ->
            when {
                XSD_IMPORT.matches(line) && line !in otherLines -> Drop.LINE
                inScope(element, line, scope) ->
                    if (line.trimEnd().endsWith("/>")) Drop.LINE else Drop.BLOCK
                refs.keys.isNotEmpty() && keyType.matches(line) -> Drop.BLOCK
                else -> Drop.NONE
            }
        }
    }

    private fun json(lines: List<String>, refs: References): List<String> {
        val property = Regex("""^\s*"${names(refs.fields, capture = true)}": .*$""")
        val required = Regex("""^\s*"${names(refs.fields, capture = true)}",?$""")
        val definition = Regex("""^\s*"(?:[\w.]*\.)?${names(refs.keys)}": .*$""")
        return blocks(lines, JSON_DEFINITION, refs.owners) { line, scope ->
            val named =
                inScope(property, line, scope) ||
                    (refs.keys.isNotEmpty() && definition.matches(line))
            when {
                named -> if (opens(line)) Drop.BLOCK else Drop.LINE
                inScope(required, line, scope) -> Drop.LINE
                else -> Drop.NONE
            }
        }
    }

    /** Whether a JSON line opens an object or array it does not also close. */
    private fun opens(line: String): Boolean {
        val t = line.trimEnd().removeSuffix(",")
        return t.endsWith("{") || t.endsWith("[")
    }

    private enum class Drop {
        NONE,
        LINE,
        BLOCK,
    }

    /**
     * Whether [line] matches [pattern], whose first group is the name a reference is written under,
     * and that name belongs to [scope]: the names the enclosing model writes, or any name when the
     * enclosing block is not a model [References.owners] knows.
     */
    private fun inScope(pattern: Regex, line: String, scope: Set<String>?): Boolean {
        val match = pattern.matchEntire(line) ?: return false
        return scope == null || match.groupValues[1] in scope
    }

    /**
     * [lines] less each line [drop] marks: a [Drop.LINE] alone, a [Drop.BLOCK] with every line
     * indented deeper under it and the closing line at its own indent. [drop] is also given the
     * names the nearest enclosing block writes, found by indentation: a line matching [opener]
     * (group 1 its name, a trailing `Type` ignored) opens a block that lasts until a line at its
     * own indent or shallower, and the block's name is looked up in [owners]; null when no
     * enclosing block is one of them.
     */
    private fun blocks(
        lines: List<String>,
        opener: Regex,
        owners: Map<String, Set<String>>,
        drop: (String, Set<String>?) -> Drop,
    ): List<String> {
        val kept = mutableListOf<String>()
        val open = mutableListOf<Pair<Int, String>>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isNotBlank()) {
                while (open.isNotEmpty() && open.last().first >= indentOf(line)) open.removeAt(
                    open.lastIndex
                )
            }
            val scope = open.lastOrNull { it.second in owners }?.let { owners[it.second] }
            when (drop(line, scope)) {
                Drop.NONE -> {
                    kept += line
                    opener.matchEntire(line)?.let { m ->
                        val name = m.groupValues[1]
                        val owner = if (name in owners) name else name.removeSuffix("Type")
                        open += indentOf(line) to owner
                    }
                }
                Drop.LINE -> {}
                Drop.BLOCK -> {
                    val indent = indentOf(line)
                    while (i + 1 < lines.size && indentOf(lines[i + 1]) > indent) i++
                    if (
                        i + 1 < lines.size &&
                            indentOf(lines[i + 1]) == indent &&
                            CLOSER.matches(lines[i + 1].trim())
                    )
                        i++
                }
            }
            i++
        }
        return kept
    }

    private fun tidy(lines: List<String>): String =
        lines
            .map { it.trimEnd().removeSuffix(",") }
            .fold(mutableListOf<String>()) { acc, l ->
                if (!(l.isBlank() && acc.lastOrNull()?.isBlank() == true)) acc += l
                acc
            }
            .joinToString("\n")

    private fun indentOf(line: String): Int =
        if (line.isBlank()) Int.MAX_VALUE else line.length - line.trimStart().length

    /**
     * An alternation of [names], quoted for a regex, as a capturing group when [capture]; never
     * matches when there are none.
     */
    private fun names(names: Set<String>, capture: Boolean = false): String =
        if (names.isEmpty()) "(?!)"
        else (if (capture) "(" else "(?:") + names.joinToString("|") { Regex.escape(it) } + ")"

    private val PROTO_MESSAGE = Regex("""^\s*message (\w+) \{$""")
    private val XSD_TYPE = Regex("""^\s*<xs:complexType name="(\w+)"[ >].*$""")
    private val JSON_DEFINITION = Regex("""^\s*"(?:[\w.]*\.)?(\w+)": \{$""")
    private val PROTO_IMPORT = Regex("""^\s*import "[^"]+";\s*$""")
    private val XSD_IMPORT = Regex("""^\s*<xs:import [^>]*/>\s*$""")
    private val XMLNS = Regex("""\s+xmlns:\w+="[^"]*"""")
    private val CLOSER = Regex("""^(?:[}\]]+[,;]?|</[A-Za-z:]+>)$""")
}
