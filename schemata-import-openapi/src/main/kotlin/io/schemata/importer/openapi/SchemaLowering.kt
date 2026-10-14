package io.schemata.importer.openapi

import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.databind.JsonNode
import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.UnitAnnotation
import io.schemata.importer.UnitDecl
import io.schemata.importer.UnitEnum
import io.schemata.importer.UnitEnumValue
import io.schemata.importer.UnitField
import io.schemata.importer.UnitRecord
import io.schemata.importer.UnitType
import io.schemata.importer.UnitUnion
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.SchemataText
import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.models.media.Schema
import java.math.BigDecimal

/** What a field takes from the schema of a property or parameter. */
data class FieldShape(
    val type: UnitType,
    val nullable: Boolean,
    val default: String?,
    val doc: String?,
    val deprecated: Boolean,
    val annotations: List<UnitAnnotation>,
)

/** The declaration a component becomes. A component that is none of these declares nothing. */
enum class DeclKind {
    MODEL,
    ENUM,
    UNION,
}

/**
 * Lowers the `components/schemas` of every document to declarations, each in the schema its dotted
 * name gives, and the schemas operations use to field types. Every component of the input documents
 * is lowered when this is built; a component of a file reached only through a `$ref` is lowered
 * when first referenced. [namespaceOf] names the schema of any document [refs] can return, inputs
 * and referenced files alike.
 */
class SchemaLowering(
    private val docs: List<OpenApiDoc>,
    private val refs: Refs,
    private val report: Report,
    private val namespaceOf: (OpenApiDoc) -> String,
) {
    private val spaces = LinkedHashMap<String, Space>()

    /** Each component by its document's path and its name. */
    private val components = HashMap<Pair<String, String>, Component>()

    /** Each component by its schema and wire path, so the same one in another document is found. */
    private val qualified = HashMap<String, Component>()

    /** Declarations in the order their components were registered, each lowered in turn. */
    private val order = mutableListOf<Decl>()

    /** The models of operations not yet synthesized, by schema and wanted name. */
    private val pending = HashMap<Pair<String, String>, Decl>()

    /**
     * The schemas, by document path and pointer, whose type is being taken at a use through a
     * `$ref`, to catch one that reaches itself.
     */
    private val inlining = HashSet<String>()

    /** What has been reported, so a construct reached twice is reported once. */
    private val said = HashSet<String>()

    private var ready = false

    /**
     * The top-level declarations that components registered after the inputs' were lowered joined,
     * whose nesting is checked when the declarations are read.
     */
    private val unchecked = LinkedHashSet<Decl>()

    /**
     * The schemas a component name can start with: every input document's own, and every one a
     * component name gives in the strict form.
     */
    private val known: Set<String> =
        docs.map(namespaceOf).toSet() +
            docs.flatMap { doc ->
                schemasOf(doc).mapNotNull { (name, _) -> OpenApiNames.strict(name)?.namespace }
            }

    init {
        docs.forEach { doc -> schemasOf(doc).forEach { (name, node) -> register(doc, name, node) } }
        var i = 0
        while (i < order.size) build(order[i++])
        spaces.values.forEach { space -> space.top.forEach(::checkParents) }
        ready = true
    }

    /**
     * Each schema's declarations, schemas in the order first seen, declarations likewise. A
     * component first reached after the inputs' were lowered, such as one of a referenced file an
     * operation names, has its nesting checked here, once every reference has been followed.
     */
    fun declarations(): Map<String, List<UnitDecl>> {
        unchecked.forEach(::checkParents)
        unchecked.clear()
        return spaces.mapValues { (_, space) -> space.top.mapNotNull(::assemble) }
    }

    /** The schemas [namespace]'s declarations refer to, in name order. */
    fun imports(namespace: String): List<String> = spaces[namespace]?.imports?.toList().orEmpty()

    /** The path of the first document that put a declaration in [namespace]. */
    fun sourcePath(namespace: String): String? = spaces[namespace]?.source

    /**
     * The shape of the field [field] of the model an operation will declare as [owner], from the
     * schema at [at] in [doc]. An inline object, enum or `oneOf` there becomes a declaration nested
     * in that model, which [synthesize] attaches when it is given [owner] as its wanted name. The
     * schema is read from [doc]'s tree at [at]; [schema] stands in only when [at] names nothing.
     * [doc] is the document that holds the node, after following any `$ref`, and [into] the schema
     * the model is declared in.
     */
    fun fieldType(
        doc: OpenApiDoc,
        at: Pointer,
        schema: Schema<*>,
        owner: String,
        field: String,
        into: String = namespaceOf(doc),
    ): FieldShape {
        // the field sits in [into]; a component the schema names belongs to [doc]'s schema, so a
        // reference to it from another schema is written qualified and that schema imported
        val namespace = into
        val holder = pending.getOrPut(namespace to owner) { Decl(namespace, owner, null, null) }
        val node = nodeAt(doc, at, schema)
        val place = Place(doc, at, Owner(namespace, listOf(holder)), inlineName(owner, field))
        return shape(place, node)
    }

    /**
     * [schema], an object, `allOf` or union schema an operation holds, lowered as a declaration
     * named [name] in schema [into] that nothing has claimed yet: the caller claims it with
     * [synthesize]. A shape inline on one of its properties is nested in it. The schema is read
     * from [doc]'s tree at [at], so a component given by its pointer is copied under [name];
     * [schema] stands in only when [at] names nothing.
     */
    fun declaration(
        doc: OpenApiDoc,
        at: Pointer,
        schema: Schema<*>,
        name: String,
        into: String = namespaceOf(doc),
    ): UnitDecl {
        val node = nodeAt(doc, at, schema)
        // declared in [into]; what [doc]'s schema declares is referred to qualified from there
        val decl = Decl(into, name, null, null)
        decl.kind = classify(node) ?: DeclKind.MODEL
        decl.built = lowered(decl, doc, at, node)
        return assemble(decl)!!
    }

    /**
     * The declarations [fieldType] nested for [owner] since the last call, removed, so a later
     * [synthesize] of [owner] does not attach them again. Operations are lowered one at a time, so
     * every pending model of that name, whatever schema its fields came from, is the caller's.
     */
    fun takeNested(owner: String): List<UnitDecl> =
        pending.keys
            .filter { it.second == owner }
            .flatMap { key -> pending.remove(key)!!.children.mapNotNull(::assemble) }

    /**
     * Declares [decl] at the top level of [namespace] under [wanted], or under [wanted] with a
     * numeric suffix when that is taken, and returns the name it got. The caller reports a changed
     * name. Declarations nested by [fieldType] calls for [wanted] join it.
     */
    fun synthesize(namespace: String, wanted: String, decl: UnitDecl): String {
        val space = space(namespace)
        val name = space.names.claim(wanted)
        val created = Decl(namespace, name, null, null)
        created.built = renamed(decl, name)
        pending.remove(namespace to wanted)?.let { created.children += it.children }
        space.top += created
        return name
    }

    /**
     * How a field in [fromNamespace], outside any model, refers to the component [target], such as
     * `Cart.Line` or `storefront.common.Problem`. The target must be a model, enum or union.
     */
    fun refName(target: Refs.Target, fromNamespace: String): String {
        val decl =
            requireNotNull(componentAt(target)?.decl) {
                "${target.doc.path}: ${target.pointer} is not a model, enum or union component"
            }
        return refText(decl, Owner(fromNamespace, emptyList()))
    }

    /** The declaration [target] lowers to, or null when it is not a component that declares one. */
    fun declarationKind(target: Refs.Target): DeclKind? = componentAt(target)?.decl?.kind

    // ---- registering components ----

    private fun schemasOf(doc: OpenApiDoc): List<Pair<String, JsonNode>> =
        doc.tree.path("components").path("schemas").properties().map { it.key to it.value }

    private fun pointerOf(name: String): Pointer = Pointer("#") / "components" / "schemas" / name

    /** The node at [at] in [doc]'s tree, or [schema] as a tree when [at] names nothing there. */
    private fun nodeAt(doc: OpenApiDoc, at: Pointer, schema: Schema<*>): JsonNode {
        val found = doc.tree.at(JsonPointer.compile(at.text.removePrefix("#")))
        return if (found.isMissingNode) Json31.mapper().valueToTree(schema) else found
    }

    /**
     * Files the component [name] of [doc] under its schema and declaration path. The same path in
     * another document is the same declaration when the two schemas are equal and an error when
     * they differ.
     */
    private fun register(doc: OpenApiDoc, name: String, node: JsonNode): Component {
        val own = namespaceOf(doc)
        val split = OpenApiNames.split(name, known + own, own)
        val namespace = split.namespace ?: own
        val key = "$namespace#${split.path.joinToString(".")}"
        val earlier = qualified[key]
        if (earlier != null) {
            if (earlier.node != node) {
                say(
                    doc,
                    pointerOf(name),
                    ImportCodes.UNRESOLVED,
                    "component '$name' differs from the one in ${earlier.doc.path}",
                    "import the documents separately, or make the two components the same",
                )
            }
            components[doc.path to name] = earlier
            return earlier
        }
        val kind = classify(node)
        if (kind == null) {
            say(
                doc,
                pointerOf(name),
                ImportCodes.APPROXIMATED,
                "component '$name' is not a model, enum or union; each use takes its type",
            )
        }
        val decl = kind?.let { declare(doc, name, namespace, split.path, it) }
        val component = Component(doc, name, node, decl)
        decl?.component = component
        qualified[key] = component
        components[doc.path to name] = component
        if (decl != null) {
            order += decl
            if (ready) {
                build(decl)
                // checked when the declarations are read, since a later reference can still
                // declare a parent this one is nested in
                unchecked += decl.ancestors.firstOrNull() ?: decl
            }
        }
        return component
    }

    /**
     * The declaration at [path] in [namespace], creating each step that is missing. A step no
     * component declares yet is held by an empty model until its component arrives.
     */
    private fun declare(
        doc: OpenApiDoc,
        name: String,
        namespace: String,
        path: List<String>,
        kind: DeclKind,
    ): Decl {
        val space = space(namespace)
        if (space.source == null) space.source = doc.path
        var parent: Decl? = null
        for (wire in path) {
            val byWire = parent?.byWire ?: space.byWire
            parent =
                byWire[wire]
                    ?: run {
                        val names = parent?.names ?: space.names
                        val wanted =
                            if (OpenApiNames.isUpperCamel(wire)) wire
                            else ImportNames.upperCamel(wire)
                        val claimed = names.claim(wanted)
                        if (claimed != wanted) {
                            say(
                                doc,
                                pointerOf(name),
                                ImportCodes.RENAMED,
                                "component '$name' is imported as '$claimed', since '$wanted' is taken",
                                SUFFIX_HELP,
                            )
                        }
                        val created = Decl(namespace, claimed, parent, wire)
                        byWire[wire] = created
                        (parent?.children ?: space.top) += created
                        created
                    }
        }
        return parent!!.also { it.kind = kind }
    }

    /** What [node] declares as a component: a model, an enum, or nothing (null). */
    private fun classify(node: JsonNode): DeclKind? {
        val types = typesOf(node) - "null"
        val enum = node.get("enum")
        return when {
            node.has("\$ref") -> null
            enum != null ->
                if ((types.isEmpty() || types == listOf("string")) && isStringEnum(enum))
                    DeclKind.ENUM
                else null
            (types.isEmpty() || types == listOf("object")) && node.has("properties") ->
                DeclKind.MODEL
            types == listOf("object") && node.get("additionalProperties")?.isFalse() == true ->
                DeclKind.MODEL
            else -> null
        }
    }

    private fun isStringEnum(enum: JsonNode): Boolean =
        enum.isArray && enum.any { it.isTextual } && enum.all { it.isTextual || it.isNull }

    private fun space(namespace: String): Space = spaces.getOrPut(namespace) { Space() }

    // ---- lowering components ----

    private fun build(decl: Decl) {
        if (decl.built != null || decl.building) return
        val component = decl.component ?: return
        decl.building = true
        val at = pointerOf(component.name)
        decl.built = lowered(decl, component.doc, at, component.node)
        decl.building = false
    }

    private fun lowered(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitDecl =
        when (decl.kind) {
            DeclKind.MODEL -> record(decl, doc, at, node)
            DeclKind.ENUM -> enumDecl(decl, doc, at, node)
            DeclKind.UNION -> error("unions are not lowered yet")
        }

    private fun record(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitRecord {
        val owner = Owner(decl.namespace, decl.ancestors + decl)
        val (properties, required) = collect(doc, at, node)
        return UnitRecord(
            name = decl.name,
            fields = fields(owner, properties, required),
            nested = emptyList(),
            doc = node.text("description"),
            annotations = listOfNotNull(wireName(decl), openness(doc, at, node)),
            deprecated = node.isTrue("deprecated"),
        )
    }

    /**
     * `@@jsonschema(open)` unless `additionalProperties` is `false`: a schema without the key, or
     * with `true` or `{}`, admits properties it does not list.
     */
    private fun openness(doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitAnnotation? =
        if (node.get("additionalProperties")?.isFalse() == true) null else OPEN

    /** [node]'s properties, each with where it is, and the names it requires. */
    private fun collect(
        doc: OpenApiDoc,
        at: Pointer,
        node: JsonNode,
    ): Pair<List<Prop>, Set<String>> {
        val properties =
            node.get("properties")?.properties()?.map { (wire, schema) ->
                Prop(doc, at / "properties" / wire, wire, schema)
            }
        return properties.orEmpty() to required(node)
    }

    private fun required(node: JsonNode): Set<String> =
        node.get("required")?.mapNotNull { it.textValue() }?.toSet().orEmpty()

    /** [properties] as fields of the innermost model of [owner], in order. */
    private fun fields(
        owner: Owner,
        properties: List<Prop>,
        required: Set<String>,
    ): List<UnitField> {
        val names = Scope()
        val model = owner.chain.last().name
        return properties.map { (doc, where, wire, schema) ->
            val wanted = if (ImportNames.isLowerSnake(wire)) wire else ImportNames.lowerSnake(wire)
            val name = names.claim(wanted)
            if (name != wanted) {
                say(
                    doc,
                    where,
                    ImportCodes.RENAMED,
                    "property '$wire' is imported as '$name', since '$wanted' is taken",
                    SUFFIX_HELP,
                )
            }
            val shape = shape(Place(doc, where, owner, inlineName(model, name)), schema)
            UnitField(
                name = name,
                type = shape.type,
                // the target leaves a field out of `required` when it is nullable or has a
                // default, so a field outside it with no default is the nullable one
                nullable = shape.nullable || (wire !in required && shape.default == null),
                default = shape.default,
                doc = shape.doc,
                annotations = listOfNotNull(jsonName(name, wire)) + shape.annotations,
                deprecated = shape.deprecated,
            )
        }
    }

    private fun enumDecl(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitEnum {
        val names = Scope()
        val values =
            node.get("enum").mapIndexedNotNull { i, value ->
                value.textValue()?.let { enumValue(doc, at / "enum" / i, names, decl, it, null) }
            }
        return UnitEnum(
            name = decl.name,
            values = values,
            doc = node.text("description"),
            annotations = listOfNotNull(wireName(decl)),
            deprecated = node.isTrue("deprecated"),
        )
    }

    /** One enum value named from its [wire] text, recorded in [decl] so a default can find it. */
    private fun enumValue(
        doc: OpenApiDoc,
        at: Pointer,
        names: Scope,
        decl: Decl,
        wire: String,
        valueDoc: String?,
    ): UnitEnumValue {
        val wanted = if (ImportNames.isLowerSnake(wire)) wire else ImportNames.lowerSnake(wire)
        val name = names.claim(wanted)
        if (name != wanted) {
            say(
                doc,
                at,
                ImportCodes.RENAMED,
                "enum value '$wire' is imported as '$name', since '$wanted' is taken",
                SUFFIX_HELP,
            )
        }
        decl.values.putIfAbsent(wire, name)
        return UnitEnumValue(name, valueDoc, listOfNotNull(jsonName(name, wire)))
    }

    // ---- field shapes and types ----

    private fun shape(place: Place, node: JsonNode): FieldShape {
        val typed = typed(place, node)
        val default = node.get("default")?.let { defaultOf(place, it, typed) }
        return FieldShape(
            type = typed.type,
            nullable = typed.nullable,
            default = default,
            doc = node.text("description"),
            deprecated = node.isTrue("deprecated"),
            annotations = emptyList(),
        )
    }

    /**
     * [node]'s type at [place], and whether it admits null: 3.0's `nullable: true`, a `null` in a
     * 3.1 type list or enum, and the target's `anyOf` of a schema and `{"type": "null"}` all do.
     */
    private fun typed(place: Place, node: JsonNode): Typed {
        val pair = node.get("anyOf") ?: node.get("oneOf")
        if (pair != null && pair.size() == 2 && pair.count(::isNullSchema) == 1) {
            val key = if (node.has("anyOf")) "anyOf" else "oneOf"
            val i = pair.indexOfFirst { !isNullSchema(it) }
            return typed(place.at(place.pointer / key / i), pair[i]).copy(nullable = true)
        }
        val nullable =
            node.isTrue("nullable") ||
                "null" in typesOf(node) ||
                node.get("enum")?.any { it.isNull } == true
        val typed = typeOf(place, node)
        return if (nullable) typed.copy(nullable = true) else typed
    }

    private fun typeOf(place: Place, node: JsonNode): Typed {
        node.text("\$ref")?.let {
            return refType(place, it)
        }
        val allOf = node.get("allOf")
        if (allOf != null && allOf.size() == 1 && allOf[0].has("\$ref")) {
            return refType(place.at(place.pointer / "allOf" / 0), allOf[0].text("\$ref")!!)
        }
        val types = typesOf(node) - "null"
        val type =
            when {
                types.size == 1 -> types.single()
                types.isEmpty() -> inferred(node)
                else -> null
            }
        return when (type) {
            "string" -> Typed(stringType(place, node))
            "integer" -> Typed(integerType(place, node))
            "number" -> Typed(numberType(place, node))
            "boolean" -> Typed(UnitType.Scalar("bool", emptyList()))
            "array" -> Typed(listType(place, node))
            "object" -> objectType(place, node)
            else -> unknown(place)
        }
    }

    /** The type a schema without a `type` has from the keywords it carries. */
    private fun inferred(node: JsonNode): String? =
        when {
            node.has("properties") || node.has("additionalProperties") -> "object"
            node.has("items") -> "array"
            node.get("enum")?.let(::isStringEnum) == true -> "string"
            else -> null
        }

    private fun unknown(place: Place): Typed {
        say(
            place.doc,
            place.pointer,
            ImportCodes.APPROXIMATED,
            "schema is imported as string, since Schemata has no type for it",
        )
        return Typed(STRING)
    }

    private fun stringType(place: Place, node: JsonNode): UnitType {
        val format = node.text("format")
        val pattern = node.text("pattern")
        if (node.text("contentEncoding") == "base64" || format == "byte") {
            return bytes(place, node, base64 = true)
        }
        if (format == "binary") return bytes(place, node, base64 = false)
        val builtin = FORMATS[format]
        if (builtin != null) {
            // the target writes a uuid's own pattern beside its format
            if (pattern != null && !(builtin == "uuid" && pattern == UUID_PATTERN)) {
                droppedPattern(place, pattern, builtin)
            }
            return UnitType.Scalar(builtin, emptyList())
        }
        if (format == null && pattern == TIME_PATTERN) return UnitType.Scalar("time", emptyList())
        if (format == null && pattern != null)
            decimal(pattern)?.let {
                return it
            }
        return UnitType.Scalar(
            "string",
            listOfNotNull(
                node.int("minLength")?.let { "min" to it.toString() },
                node.int("maxLength")?.let { "max" to it.toString() },
                pattern?.let { "pattern" to SchemataText.pattern(it) },
            ),
        )
    }

    private fun droppedPattern(place: Place, pattern: String, builtin: String) {
        say(
            place.doc,
            place.pointer,
            ImportCodes.DROPPED,
            "pattern '$pattern' dropped; $builtin takes none",
        )
    }

    /**
     * `bytes`, its lengths in bytes. A base64 text of `4n` characters holds `3n` bytes, so a base64
     * length that is not a multiple of 4 is approximated.
     */
    private fun bytes(place: Place, node: JsonNode, base64: Boolean): UnitType {
        node.text("pattern")?.let { droppedPattern(place, it, "bytes") }
        fun length(key: String, refinement: String): Pair<String, String>? {
            val length = node.int(key) ?: return null
            if (!base64) return refinement to length.toString()
            val bytes = length / 4 * 3
            if (length % 4 != 0L) {
                say(
                    place.doc,
                    place.pointer,
                    ImportCodes.APPROXIMATED,
                    "base64 $key $length is imported as $refinement $bytes",
                )
            }
            return refinement to bytes.toString()
        }
        return UnitType.Scalar(
            "bytes",
            listOfNotNull(length("minLength", "min"), length("maxLength", "max")),
        )
    }

    /**
     * `decimal(p, s)` when [pattern] is the one the target writes for it:
     * `^-?[0-9]{1,p-s}(\.[0-9]{1,s})?$`, with no fraction group when the scale is 0, and `^-?0` as
     * the integer part when the precision equals the scale.
     */
    private fun decimal(pattern: String): UnitType? {
        val match = DECIMAL.matchEntire(pattern) ?: return null
        val (zero, whole, fraction) = match.destructured
        val scale = fraction.toIntOrNull() ?: 0
        val precision =
            if (zero.isNotEmpty()) scale.takeIf { it > 0 } ?: return null else whole.toInt() + scale
        return UnitType.Scalar("decimal", listOf("p" to "$precision", "s" to "$scale"))
    }

    /**
     * `int32` when the format says so, or when there is no format and both bounds fit one; `int64`
     * otherwise. A bound equal to the type's own limit is no refinement: the target writes both
     * limits on every integer.
     */
    private fun integerType(place: Place, node: JsonNode): UnitType {
        val min = node.decimal("minimum")
        val max = node.decimal("maximum")
        val format = node.text("format")?.takeIf { it == "int32" || it == "int64" }
        val fits = { b: BigDecimal? -> b == null || (b >= INT32_MIN && b <= INT32_MAX) }
        val int32 =
            fits(min) &&
                fits(max) &&
                (format == "int32" || (format == null && min != null && max != null))
        val (low, high) = if (int32) INT32_MIN to INT32_MAX else INT64_MIN to INT64_MAX
        return UnitType.Scalar(
            if (int32) "int32" else "int64",
            listOfNotNull(
                min?.takeIf { it.compareTo(low) != 0 }?.let { "min" to integral(it) },
                max?.takeIf { it.compareTo(high) != 0 }?.let { "max" to integral(it) },
            ),
        )
    }

    private fun numberType(place: Place, node: JsonNode): UnitType =
        UnitType.Scalar(
            if (node.text("format") == "float") "float32" else "float64",
            listOfNotNull(
                node.get("minimum")?.takeIf { it.isNumber }?.let { "min" to literal(it) },
                node.get("maximum")?.takeIf { it.isNumber }?.let { "max" to literal(it) },
            ),
        )

    private fun listType(place: Place, node: JsonNode): UnitType {
        val items = node.get("items")
        val element =
            if (items == null || !items.isObject) unknown(place)
            else typed(place.at(place.pointer / "items"), items)
        return UnitType.ListOf(element.type, element.nullable, sizes(node, "minItems", "maxItems"))
    }

    /**
     * A map when `additionalProperties` is a schema, its keys from `propertyNames`; a free-form
     * object, which says nothing of its values, is approximated as `map<string, string>`.
     */
    private fun objectType(place: Place, node: JsonNode): Typed {
        val extra = node.get("additionalProperties")
        if (node.has("properties") || extra?.isFalse() == true) return unknown(place)
        if (extra == null || !extra.isObject || extra.isEmpty) {
            say(
                place.doc,
                place.pointer,
                ImportCodes.APPROXIMATED,
                "free-form object is imported as map<string, string>",
            )
            return Typed(UnitType.MapOf(STRING, STRING, false, emptyList()))
        }
        val value = typed(place.at(place.pointer / "additionalProperties"), extra)
        return Typed(
            UnitType.MapOf(
                keyType(node.get("propertyNames")),
                value.type,
                value.nullable,
                sizes(node, "minProperties", "maxProperties"),
            )
        )
    }

    /**
     * A map's key: the target writes an integer key as a string of digits, which comes back as
     * `int64`; a string key keeps its lengths and pattern.
     */
    private fun keyType(names: JsonNode?): UnitType {
        if (names == null || !names.isObject) return STRING
        val pattern = names.text("pattern")
        if (pattern == INTEGER_KEY) return UnitType.Scalar("int64", emptyList())
        return UnitType.Scalar(
            "string",
            listOfNotNull(
                names.int("minLength")?.let { "min" to it.toString() },
                names.int("maxLength")?.let { "max" to it.toString() },
                pattern?.let { "pattern" to SchemataText.pattern(it) },
            ),
        )
    }

    private fun sizes(node: JsonNode, min: String, max: String): List<Pair<String, String>> =
        listOfNotNull(
            node.int(min)?.let { "min" to it.toString() },
            node.int(max)?.let { "max" to it.toString() },
        )

    /**
     * The type of a `$ref`. A model, enum or union component is referred to by name; any other
     * component, and a schema inside one, is lowered where it is used.
     */
    private fun refType(place: Place, ref: String): Typed {
        val target = refs.resolve(place.doc, place.pointer, ref) ?: return Typed(STRING)
        val component = componentAt(target) ?: return inlineSchema(target, place)
        val decl = component.decl ?: return inlineComponent(component, place)
        return Typed(
            UnitType.Ref(refText(decl, place.owner)),
            nullable = admitsNull(component.node),
            decl = decl,
        )
    }

    /** The component [target] names when it names a whole one under `components/schemas`. */
    private fun componentAt(target: Refs.Target): Component? {
        val name = target.name ?: return null
        if (target.section != "schemas" || target.pointer != pointerOf(name)) return null
        return components[target.doc.path to name] ?: register(target.doc, name, target.node)
    }

    /** Each use of a component that is not a model, enum or union takes its type. */
    private fun inlineComponent(component: Component, place: Place): Typed {
        val at = pointerOf(component.name)
        return followed(
            component.doc,
            at,
            "component '${component.name}' refers to itself and is not a model, enum or union",
            "make the component an object with properties",
        ) {
            typed(Place(component.doc, at, place.owner, place.inline), component.node)
        }
    }

    /**
     * Each use of a schema inside a component, such as `#/components/schemas/Pet/properties/a`,
     * takes its type, since only a whole component declares anything.
     */
    private fun inlineSchema(target: Refs.Target, place: Place): Typed =
        followed(
            target.doc,
            target.pointer,
            "schema refers to itself and is not a model, enum or union",
            "move the schema into a component that is an object with properties",
        ) {
            typed(Place(target.doc, target.pointer, place.owner, place.inline), target.node)
        }

    /**
     * The type [take] gives the schema at [at] in [doc], reached through a `$ref`. A schema reached
     * again while its own type is being taken refers to itself with no model, enum or union to stop
     * at, so it is SCH2401 with [message] and [help], and string.
     */
    private inline fun followed(
        doc: OpenApiDoc,
        at: Pointer,
        message: String,
        help: String,
        take: () -> Typed,
    ): Typed {
        val key = "${doc.path}${at.text}"
        if (!inlining.add(key)) {
            say(doc, at, ImportCodes.UNRESOLVED, message, help)
            return Typed(STRING)
        }
        try {
            return take()
        } finally {
            inlining.remove(key)
        }
    }

    /**
     * How a field inside [owner] refers to [decl]: by its path from the innermost model both share,
     * by its full name when a model on the way nests a declaration of the same name, which would
     * otherwise be found first, and by its schema and path from another schema.
     */
    private fun refText(decl: Decl, owner: Owner): String {
        val full = decl.namespace + "." + decl.path.joinToString(".")
        if (decl.namespace != owner.namespace) {
            space(owner.namespace).imports += decl.namespace
            return full
        }
        val parents = decl.ancestors
        var shared = 0
        while (
            shared < parents.size &&
                shared < owner.chain.size &&
                parents[shared] === owner.chain[shared]
        ) shared++
        val rest = decl.path.drop(shared)
        val hidden = owner.chain.drop(shared).any { it.names.taken(rest.first()) }
        return if (hidden) full else rest.joinToString(".")
    }

    /**
     * A default as Schemata source, or null with SCH2405 when the field's type has no literal for
     * it. A decimal's default is a string in the document, which keeps its scale.
     */
    private fun defaultOf(place: Place, value: JsonNode, typed: Typed): String? {
        if (value.isNull) return null
        val type = typed.type
        val literal =
            when {
                typed.decl?.kind == DeclKind.ENUM -> {
                    build(typed.decl)
                    value.textValue()?.let { typed.decl.values[it] }
                }
                type !is UnitType.Scalar -> null
                type.builtin == "string" -> value.textValue()?.let(SchemataText::string)
                type.builtin == "bool" -> value.takeIf { it.isBoolean }?.asText()
                type.builtin == "int32" || type.builtin == "int64" ->
                    value.takeIf { it.isNumber }?.decimalValue()?.let(::integralOrNull)
                type.builtin == "float32" || type.builtin == "float64" ->
                    value.takeIf { it.isNumber }?.let(::literal)
                type.builtin == "decimal" ->
                    when {
                        value.isNumber -> literal(value)
                        value.isTextual && DECIMAL_LITERAL.matches(value.textValue()) ->
                            value.textValue()
                        else -> null
                    }
                else -> null
            }
        if (literal == null) {
            say(
                place.doc,
                place.pointer,
                ImportCodes.DROPPED,
                "default $value is dropped, since the field's type cannot hold it",
            )
        }
        return literal
    }

    // ---- assembling ----

    /**
     * Reports a step of a declaration path that no model declares: an empty model holds it when no
     * component has that path, and when a component that is not a model does, it is an error.
     */
    private fun checkParents(decl: Decl) {
        if (decl.children.isNotEmpty() && (decl.built == null || decl.kind != DeclKind.MODEL)) {
            val child = decl.children.firstNotNullOf { it.firstComponent() }
            val path = decl.path.joinToString(".")
            val declared =
                decl.built != null ||
                    qualified.containsKey("${decl.namespace}#${decl.wirePath.joinToString(".")}")
            if (declared) {
                say(
                    child.doc,
                    pointerOf(child.name),
                    ImportCodes.UNRESOLVED,
                    "component '${child.name}' is nested in '$path', which is not a model",
                    "rename the component so its path ends in a model",
                )
            } else {
                say(
                    child.doc,
                    pointerOf(child.name),
                    ImportCodes.APPROXIMATED,
                    "component '${child.name}' is nested in '$path', which no component declares; an empty model holds it",
                )
            }
        }
        decl.children.forEach(::checkParents)
    }

    private fun assemble(decl: Decl): UnitDecl? {
        val nested = decl.children.mapNotNull(::assemble)
        return when (val built = decl.built) {
            null -> UnitRecord(decl.name, emptyList(), nested, null, listOfNotNull(wireName(decl)))
            is UnitRecord -> built.copy(nested = built.nested + nested)
            else -> built
        }
    }

    /** `@jsonschema(name: "<wire>")` when a declaration's name is not the document's. */
    private fun wireName(decl: Decl): UnitAnnotation? =
        decl.wire
            ?.takeIf { it != decl.name }
            ?.let { UnitAnnotation("jsonschema", "name", SchemataText.string(it)) }

    private fun renamed(decl: UnitDecl, name: String): UnitDecl =
        when (decl) {
            is UnitRecord -> decl.copy(name = name)
            is UnitEnum -> decl.copy(name = name)
            is UnitUnion -> decl.copy(name = name)
        }

    private fun say(
        doc: OpenApiDoc,
        at: Pointer?,
        code: DiagnosticCode,
        message: String,
        help: String = ImportCodes.helpFor(code),
    ) {
        if (said.add("${doc.path}|$at|$message")) report.at(doc, at, code, message, help)
    }

    private fun say(place: Place, code: DiagnosticCode, message: String) =
        say(place.doc, place.pointer, code, message)

    private class Space {
        val top = mutableListOf<Decl>()
        val byWire = HashMap<String, Decl>()
        val names = Scope()
        val imports = sortedSetOf<String>()
        var source: String? = null
    }

    /** A component of one document and the declaration it lowers to, if it lowers to one. */
    private class Component(
        val doc: OpenApiDoc,
        val name: String,
        val node: JsonNode,
        val decl: Decl?,
    )

    /**
     * A declaration being built: a component's, a step of a dotted path no component declares yet,
     * an inline shape lifted out of a field, or the model of an operation. [wire] is the name the
     * document gives it, when it gives one.
     */
    private class Decl(
        val namespace: String,
        val name: String,
        val parent: Decl?,
        val wire: String?,
    ) {
        var kind = DeclKind.MODEL
        var component: Component? = null
        var built: UnitDecl? = null
        var building = false
        val children = mutableListOf<Decl>()
        val byWire = HashMap<String, Decl>()
        val names = Scope()

        /** An enum's values: each wire text to the value's name. */
        val values = HashMap<String, String>()

        val path: List<String>
            get() = parent?.path.orEmpty() + name

        val wirePath: List<String>
            get() = parent?.wirePath.orEmpty() + (wire ?: name)

        val ancestors: List<Decl>
            get() = parent?.let { it.ancestors + it }.orEmpty()

        fun firstComponent(): Component? =
            component ?: children.firstNotNullOfOrNull { it.firstComponent() }
    }

    /** The models a field sits in, outermost first, which an inline shape there joins. */
    private class Owner(val namespace: String, val chain: List<Decl>)

    /**
     * A schema's document and pointer, the models it sits in, and the name an inline shape there is
     * declared under.
     */
    private class Place(
        val doc: OpenApiDoc,
        val pointer: Pointer,
        val owner: Owner,
        val inline: String,
    ) {
        fun at(pointer: Pointer): Place = Place(doc, pointer, owner, inline)
    }

    /** A property of a model's schema, with the document and pointer it was written at. */
    private data class Prop(
        val doc: OpenApiDoc,
        val at: Pointer,
        val wire: String,
        val schema: JsonNode,
    )

    /** A type, whether it admits null, and the declaration it names, if it names one. */
    private data class Typed(
        val type: UnitType,
        val nullable: Boolean = false,
        val decl: Decl? = null,
    )

    private companion object {
        val STRING = UnitType.Scalar("string", emptyList())
        const val SUFFIX_HELP = "rename one of them in the document, or keep the suffixed name"
        val OPEN = UnitAnnotation("jsonschema", "open", null)

        const val UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        const val TIME_PATTERN = "^[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?$"
        const val INTEGER_KEY = "^(0|-?[1-9][0-9]*)$"

        val DECIMAL =
            Regex(
                """\^-\?(?:(0)|\[0-9]\{1,([1-9][0-9]*)\})(?:\(\\\.\[0-9]\{1,([1-9][0-9]*)\}\)\?)?\$"""
            )
        val DECIMAL_LITERAL = Regex("-?[0-9]+(\\.[0-9]+)?")

        val FORMATS =
            mapOf(
                "uuid" to "uuid",
                "date" to "date",
                "time" to "time",
                "date-time" to "instant",
                "duration" to "duration",
            )

        val INT32_MIN = BigDecimal("-2147483648")
        val INT32_MAX = BigDecimal("2147483647")
        val INT64_MIN = BigDecimal("-9223372036854775808")
        val INT64_MAX = BigDecimal("9223372036854775807")
    }
}

/** `@jsonschema(name: "<wire>")` when a field's or value's [name] is not its [wire] text. */
private fun jsonName(name: String, wire: String): UnitAnnotation? =
    if (name == wire) null else UnitAnnotation("jsonschema", "name", SchemataText.string(wire))

/** The name the core hoister gives an inline shape on [field] of [model]: `<Model><Field>`. */
private fun inlineName(model: String, field: String): String = model + ImportNames.upperCamel(field)

private fun JsonNode.text(key: String): String? = get(key)?.textValue()

private fun JsonNode.isTrue(key: String): Boolean =
    get(key)?.let { it.isBoolean && it.booleanValue() } == true

private fun JsonNode.isFalse(): Boolean = isBoolean && !booleanValue()

private fun JsonNode.int(key: String): Long? = get(key)?.takeIf { it.isIntegralNumber }?.longValue()

private fun JsonNode.decimal(key: String): BigDecimal? =
    get(key)?.takeIf { it.isNumber }?.decimalValue()

/** A schema's `type`, a string in 3.0 and a string or a list in 3.1, as a list. */
private fun typesOf(node: JsonNode): List<String> {
    val type = node.get("type") ?: return emptyList()
    return if (type.isArray) type.mapNotNull { it.textValue() } else listOfNotNull(type.textValue())
}

/** `{"type": "null"}`, the null half of the target's nullable `anyOf`. */
private fun isNullSchema(node: JsonNode): Boolean =
    typesOf(node) == listOf("null") || node.get("const")?.isNull == true

/** Whether a component's own schema admits null, which every use of it then does. */
private fun admitsNull(node: JsonNode): Boolean = node.isTrue("nullable") || "null" in typesOf(node)

/** A number as Schemata source: an integer as its digits, any other as plain decimal text. */
private fun literal(node: JsonNode): String =
    if (node.isIntegralNumber) node.bigIntegerValue().toString()
    else node.decimalValue().toPlainString()

private fun integral(n: BigDecimal): String = integralOrNull(n) ?: n.toPlainString()

private fun integralOrNull(n: BigDecimal): String? =
    try {
        n.toBigIntegerExact().toString()
    } catch (_: ArithmeticException) {
        null
    }
