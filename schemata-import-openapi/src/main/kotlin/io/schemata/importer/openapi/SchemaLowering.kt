package io.schemata.importer.openapi

import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.databind.JsonNode
import io.schemata.importer.ImportCodes
import io.schemata.importer.ImportNames
import io.schemata.importer.UnionMember
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
import io.schemata.target.Names as TargetNames
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

    /** The components, by document path and pointer, being classified through an `allOf`. */
    private val classifying = HashSet<String>()

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
        decl.kind = classify(node, doc) ?: DeclKind.MODEL
        dropped(doc, at, node)
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
     * Takes [name] at the top level of [namespace] for something other than a declaration, such as
     * a service, so a declaration [synthesize] makes there takes a suffix instead.
     */
    fun reserve(namespace: String, name: String) {
        space(namespace).names.claim(name)
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
        val kind = classify(node, doc)
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

    /**
     * What [node], a schema of [doc], declares as a component: a model, an enum, a union, or
     * nothing (null). An `allOf` that only annotates a reference to an enum or union declares
     * nothing, so each use takes that enum or union.
     */
    private fun classify(node: JsonNode, doc: OpenApiDoc): DeclKind? {
        val types = typesOf(node) - "null"
        val enum = node.get("enum")
        val choice = choiceOf(node)
        return when {
            node.has("\$ref") -> null
            enum != null ->
                if ((types.isEmpty() || types == listOf("string")) && isStringEnum(enum))
                    DeclKind.ENUM
                else null
            choice != null && isConstEnum(choice) -> DeclKind.ENUM
            node.has("allOf") -> if (wrapsChoice(node, doc)) null else DeclKind.MODEL
            choice != null && !node.has("properties") ->
                if (choice.count { !isNullSchema(it) } >= 2) DeclKind.UNION else null
            (types.isEmpty() || types == listOf("object")) && node.has("properties") ->
                DeclKind.MODEL
            types == listOf("object") && node.get("additionalProperties")?.isFalse() == true ->
                DeclKind.MODEL
            else -> null
        }
    }

    /** Whether [node]'s `allOf` only annotates a reference to an enum or union component. */
    private fun wrapsChoice(node: JsonNode, doc: OpenApiDoc): Boolean {
        val part = refPart(node) ?: return false
        val target = refs.peek(doc, node.get("allOf")[part].text("\$ref")!!) ?: return false
        val name = target.name ?: return false
        if (target.section != "schemas" || target.pointer != pointerOf(name)) return false
        val key = "${target.doc.path}${target.pointer}"
        if (!classifying.add(key)) return false
        try {
            val kind = classify(target.node, target.doc)
            return kind == DeclKind.ENUM || kind == DeclKind.UNION
        } finally {
            classifying.remove(key)
        }
    }

    /**
     * The position of an `allOf`'s one `$ref` part when every other part only annotates it, as 3.0
     * writes a description beside a reference; null otherwise.
     */
    private fun refPart(node: JsonNode): Int? {
        val allOf = node.get("allOf")?.takeIf { it.isArray } ?: return null
        val i = (0 until allOf.size()).singleOrNull { allOf[it].has("\$ref") } ?: return null
        val annotating =
            (0 until allOf.size()).all { j ->
                j == i ||
                    (allOf[j].isObject &&
                        allOf[j].properties().all {
                            it.key in ANNOTATIONS || it.key.startsWith("x-")
                        })
            }
        return i.takeIf { annotating }
    }

    /** `oneOf`, or else `anyOf`: the members of a choice. */
    private fun choiceOf(node: JsonNode): JsonNode? =
        (node.get("oneOf") ?: node.get("anyOf"))?.takeIf { it.isArray }

    private fun choiceKey(node: JsonNode): String = if (node.has("oneOf")) "oneOf" else "anyOf"

    /** The target's enum with value docs: a `oneOf` of `{"const": …, "description": …}`. */
    private fun isConstEnum(choice: JsonNode): Boolean =
        choice.size() > 0 &&
            choice.all { entry ->
                entry.get("const")?.isTextual == true &&
                    entry.properties().all { it.key == "const" || it.key == "description" }
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
        dropped(component.doc, at, component.node)
        decl.built = lowered(decl, component.doc, at, component.node)
        decl.building = false
    }

    private fun lowered(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitDecl =
        when (decl.kind) {
            DeclKind.MODEL -> record(decl, doc, at, node)
            DeclKind.ENUM -> enumDecl(decl, doc, at, node)
            DeclKind.UNION -> union(decl, doc, at, node)
        }

    /**
     * Reports the keywords of [node] that Schemata has no place for, all in one message: the
     * annotations `readOnly`, `writeOnly`, `example`, `examples`, `xml` and `externalDocs`, the
     * conditionals `not`, `if`, `then` and `else`, and the constraints `patternProperties`,
     * `uniqueItems` (a list has no uniqueness option) and `multipleOf`. A model's `oneOf` or
     * `anyOf` beside its properties is dropped too.
     */
    private fun dropped(doc: OpenApiDoc, at: Pointer, node: JsonNode) {
        if (!node.isObject) return
        val beside =
            if (node.has("properties")) listOf("oneOf", "anyOf").filter(node::has) else emptyList()
        val keys = DROPPED_KEYWORDS.filter(node::has) + beside
        if (keys.isEmpty()) return
        val listed = keys.joinToString(", ") { "'$it'" }
        say(
            doc,
            at,
            ImportCodes.DROPPED,
            "${if (keys.size == 1) "keyword" else "keywords"} $listed dropped",
        )
    }

    private fun record(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitRecord {
        val owner = Owner(decl.namespace, decl.ancestors + decl)
        val (properties, required) = collect(doc, at, node, mutableSetOf())
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
    private fun openness(doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitAnnotation? {
        val extra = node.get("additionalProperties") ?: return OPEN
        if (extra.isFalse()) return null
        if (extra.isObject && !extra.isEmpty) {
            say(
                doc,
                at / "additionalProperties",
                ImportCodes.APPROXIMATED,
                "additionalProperties beside properties is imported as an open model; its schema is not kept",
            )
        }
        return OPEN
    }

    /**
     * [node]'s properties, each with where it is, and the names it requires. The parts of an
     * `allOf` come first, flattened in order, a referenced part through its component; when two
     * parts give one property different schemas, the first is kept. [seen] holds the parts being
     * flattened, so a part that reaches itself is caught.
     */
    private fun collect(
        doc: OpenApiDoc,
        at: Pointer,
        node: JsonNode,
        seen: MutableSet<String>,
    ): Pair<List<Prop>, Set<String>> {
        val properties = LinkedHashMap<String, Prop>()
        val required = LinkedHashSet<String>()
        fun add(part: Pair<List<Prop>, Set<String>>, where: Pointer) {
            part.first.forEach { prop ->
                val first = properties.putIfAbsent(prop.wire, prop)
                if (first != null && first.schema != prop.schema) {
                    say(
                        doc,
                        where,
                        ImportCodes.APPROXIMATED,
                        "allOf parts disagree on property '${prop.wire}'; the first part's schema is kept",
                    )
                }
            }
            required += part.second
        }
        node.get("allOf")?.forEachIndexed { i, part ->
            val where = at / "allOf" / i
            val ref = part.text("\$ref")
            if (ref == null) {
                dropped(doc, where, part)
                add(collect(doc, where, part, seen), where)
                return@forEachIndexed
            }
            val target = refs.resolve(doc, where, ref) ?: return@forEachIndexed
            val key = "${target.doc.path}${target.pointer}"
            if (!seen.add(key)) {
                say(
                    doc,
                    where,
                    ImportCodes.UNRESOLVED,
                    "allOf part '$ref' includes itself",
                    "remove the part that leads back to this schema",
                )
                return@forEachIndexed
            }
            add(collect(target.doc, target.pointer, target.node, seen), where)
            seen.remove(key)
        }
        add(
            node
                .get("properties")
                ?.properties()
                ?.map { (wire, schema) -> Prop(doc, at / "properties" / wire, wire, schema) }
                .orEmpty() to required(node),
            at,
        )
        return properties.values.toList() to required
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

    /** An `enum` of strings, or the target's `oneOf` of `const`s whose descriptions are docs. */
    private fun enumDecl(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitEnum {
        val names = Scope()
        val choice = choiceOf(node)?.takeIf { !node.has("enum") }
        val values =
            if (choice != null) {
                choice.mapIndexed { i, entry ->
                    val where = at / choiceKey(node) / i
                    enumValue(
                        doc,
                        where,
                        names,
                        decl,
                        entry.text("const")!!,
                        entry.text("description"),
                    )
                }
            } else {
                node.get("enum").mapIndexedNotNull { i, value ->
                    value.textValue()?.let {
                        enumValue(doc, at / "enum" / i, names, decl, it, null)
                    }
                }
            }
        return UnitEnum(
            name = decl.name,
            values = values,
            doc = node.text("description"),
            annotations = listOfNotNull(wireName(decl)),
            deprecated = node.isTrue("deprecated"),
        )
    }

    /**
     * A union from a `oneOf` or `anyOf`. Each member is a reference, a scalar, or an inline schema
     * declared beside the union as `<Union>Member<n>`. In the target's own form each member is a
     * closed object of one required property, the member's tag. A scalar under a tag that names a
     * model is that model carried by its key; any other tag that is not the one the target would
     * write is approximated. A `null` member makes each use of the union nullable instead.
     */
    private fun union(decl: Decl, doc: OpenApiDoc, at: Pointer, node: JsonNode): UnitUnion {
        val key = choiceKey(node)
        val choice = choiceOf(node)!!
        val tagged = isTagged(choice)
        val owner = Owner(decl.namespace, decl.ancestors)
        val members =
            choice.mapIndexedNotNull { i, entry ->
                if (isNullSchema(entry)) return@mapIndexedNotNull null
                var where = at / key / i
                var schema = entry
                val tag = if (tagged) entry.get("properties").properties().first().key else null
                if (tag != null) {
                    where = where / "properties" / tag
                    schema = entry.get("properties").get(tag)
                }
                val place = Place(doc, where, owner, "${decl.name}Member${i + 1}")
                val typed = typed(place, schema)
                if (typed.nullable) {
                    say(
                        doc,
                        where,
                        ImportCodes.APPROXIMATED,
                        "union member's null is dropped; a union member cannot be null",
                        "make the field that holds the union nullable to keep the null",
                    )
                }
                var type =
                    if (typed.type is UnitType.Ref || typed.type is UnitType.Scalar) typed.type
                    else unknown(place).type
                if (tag != null) {
                    val stem = stemOf(type, typed.decl)
                    val keyed = (type as? UnitType.Scalar)?.takeIf { tag != stem }
                    val model = keyed?.let { keyedMember(place, tag, it) }
                    if (model != null) type = model
                    else if (tag != stem) {
                        say(
                            place,
                            ImportCodes.APPROXIMATED,
                            "union member tag '$tag' is not the member's own; the regenerated union tags it '$stem'",
                        )
                    }
                }
                Member(i, place, type, schema.text("description"))
            }
        discriminator(doc, at, node)
        return UnitUnion(
            name = decl.name,
            members = distinct(doc, at / key, members),
            doc = node.text("description"),
            annotations = listOfNotNull(wireName(decl)),
            deprecated = node.isTrue("deprecated"),
        )
    }

    /**
     * The model a tagged member holding [scalar] under [tag] stands for, declared beside the union,
     * or null when [tag] is no name such a model could have. The target writes a member that refers
     * to a keyed model as the model's key, tagged with the model's name, and leaves the model out
     * when nothing else reaches it, so the member is that model with one key field of the scalar's
     * type: compiled again, it is written the same way. A hand-written member of one scalar
     * property has the same shape, so the model and its made-up key are always reported.
     */
    private fun keyedMember(place: Place, tag: String, scalar: UnitType.Scalar): UnitType? {
        val wanted = ImportNames.upperCamel(tag)
        if (visible(place, wanted) || TargetNames.snakeCase(wanted) != tag) return null
        val model = inlineDecl(Place(place.doc, place.pointer, place.owner, wanted), DeclKind.MODEL)
        model.built =
            UnitRecord(
                model.name,
                listOf(
                    UnitField(
                        KEY_FIELD,
                        scalar,
                        false,
                        null,
                        null,
                        emptyList(),
                        options = KEY_OPTION,
                    )
                ),
                emptyList(),
                null,
                emptyList(),
            )
        say(
            place.doc,
            place.pointer,
            ImportCodes.APPROXIMATED,
            "member '$tag' is imported as model '${model.name}' carried by its key '$KEY_FIELD'",
            "if the member is not a reference to a keyed model, replace the model with the member's own type",
        )
        return UnitType.Ref(refText(model, place.owner))
    }

    /**
     * [members] as union members. A union lists each type once, so members that lower to one type
     * are each held in a model instead, `<Union>Member<n>` with the type as its one field `value`.
     */
    private fun distinct(doc: OpenApiDoc, at: Pointer, members: List<Member>): List<UnionMember> {
        val held = HashMap<Int, UnitType>()
        members
            .groupBy { member ->
                when (val type = member.type) {
                    is UnitType.Scalar -> "scalar ${type.builtin}"
                    is UnitType.Ref -> "ref ${type.name}"
                    else -> "other $type"
                }
            }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val positions = group.map { "${it.index + 1}" }
                val listed = positions.dropLast(1).joinToString(", ") + " and " + positions.last()
                val shared =
                    when (val type = group[0].type) {
                        is UnitType.Scalar -> type.builtin
                        is UnitType.Ref -> type.name
                        else -> "$type"
                    }
                say(
                    doc,
                    at,
                    ImportCodes.APPROXIMATED,
                    "union members $listed share type '$shared'; each imported as a model holding it",
                )
                group.forEach { member ->
                    val model = inlineDecl(member.place, DeclKind.MODEL)
                    model.built =
                        UnitRecord(
                            model.name,
                            listOf(UnitField("value", member.type, false, null, null, emptyList())),
                            emptyList(),
                            null,
                            emptyList(),
                        )
                    held[member.index] = UnitType.Ref(refText(model, member.place.owner))
                }
            }
        return members.map { UnionMember(held[it.index] ?: it.type, it.doc) }
    }

    /**
     * The target's externally tagged form: every member a closed object of one required property.
     */
    private fun isTagged(choice: JsonNode): Boolean =
        choice.any { !isNullSchema(it) } &&
            choice.all { entry ->
                if (isNullSchema(entry)) return@all true
                val properties = entry.get("properties")
                val required = entry.get("required")
                !entry.has("\$ref") &&
                    entry.get("additionalProperties")?.isFalse() == true &&
                    properties != null &&
                    properties.size() == 1 &&
                    required != null &&
                    required.size() == 1 &&
                    required[0].textValue() == properties.properties().first().key
            }

    /** The tag the target writes for a member: a declaration's wire name in lower snake case. */
    private fun stemOf(type: UnitType, decl: Decl?): String =
        when {
            decl != null ->
                decl.wire?.takeIf { it != decl.name } ?: TargetNames.snakeCase(decl.name)
            type is UnitType.Scalar -> type.builtin
            else -> "string"
        }

    /**
     * Schemata tags a union member by its name and writes no discriminator. A discriminator without
     * a mapping is dropped silently, as is one whose mapping gives each member the tag Schemata
     * would; any other mapping is approximated. A mapping value is a component name, or else a
     * reference; one that names nothing only makes the mapping one Schemata cannot keep.
     */
    private fun discriminator(doc: OpenApiDoc, at: Pointer, node: JsonNode) {
        val mapping = node.get("discriminator")?.get("mapping") ?: return
        val reproducible =
            mapping.properties().all { (tag, value) ->
                val name = value.textValue() ?: return@all false
                val component = doc.tree.path("components").path("schemas").has(name)
                val target = refs.peek(doc, if (component) pointerOf(name).text else name)
                val decl = target?.let(::componentAt)?.decl
                decl != null && stemOf(UnitType.Ref(decl.name), decl) == tag
            }
        if (!reproducible) {
            say(
                doc,
                at / "discriminator",
                ImportCodes.APPROXIMATED,
                "discriminator mapping is not kept; the union is tagged by member name",
            )
        }
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
            doc = node.text("description") ?: annotatingDoc(node),
            deprecated = node.isTrue("deprecated"),
            annotations = emptyList(),
        )
    }

    /** The description an `allOf` part beside its one reference carries. */
    private fun annotatingDoc(node: JsonNode): String? {
        val part = refPart(node) ?: return null
        return node.get("allOf").withIndex().firstNotNullOfOrNull { (j, it) ->
            if (j == part) null else it.text("description")
        }
    }

    /**
     * [node]'s type at [place], and whether it admits null: 3.0's `nullable: true`, a `null` in a
     * 3.1 type list or enum, and the target's `anyOf` of a schema and `{"type": "null"}` all do.
     */
    private fun typed(place: Place, node: JsonNode): Typed {
        dropped(place.doc, place.pointer, node)
        val choice = choiceOf(node)?.takeIf { !node.has("properties") }
        val nulls = choice?.count(::isNullSchema) ?: 0
        if (choice != null && nulls > 0 && choice.size() - nulls == 1) {
            val i = choice.indexOfFirst { !isNullSchema(it) }
            return typed(place.at(place.pointer / choiceKey(node) / i), choice[i])
                .copy(nullable = true)
        }
        val nullable =
            node.isTrue("nullable") ||
                "null" in typesOf(node) ||
                node.get("enum")?.any { it.isNull } == true ||
                nulls > 0
        val typed = typeOf(place, node)
        return if (nullable) typed.copy(nullable = true) else typed
    }

    private fun typeOf(place: Place, node: JsonNode): Typed {
        node.text("\$ref")?.let {
            return refType(place, it)
        }
        val allOf = node.get("allOf")
        val part = refPart(node)
        if (part != null) {
            // one part is that reference; beside annotating parts only an enum or union is kept
            // as it is, while a model's parts still flatten into a model of the field's own
            val where = place.pointer / "allOf" / part
            val ref = allOf[part].text("\$ref")!!
            val kind =
                if (allOf.size() == 1) null
                else refs.resolve(place.doc, where, ref)?.let(::componentAt)?.decl?.kind
            if (allOf.size() == 1 || kind == DeclKind.ENUM || kind == DeclKind.UNION) {
                return refType(place.at(where), ref)
            }
        }
        if (allOf != null) return hoist(place, node, DeclKind.MODEL)
        val choice = choiceOf(node)?.takeIf { !node.has("properties") }
        if (choice != null) {
            if (isConstEnum(choice)) return hoist(place, node, DeclKind.ENUM)
            val members = choice.withIndex().filter { !isNullSchema(it.value) }
            if (members.size == 1) {
                val (i, member) = members.single()
                return typed(place.at(place.pointer / choiceKey(node) / i), member)
            }
            if (members.size > 1) return hoist(place, node, DeclKind.UNION)
        }
        val enum = node.get("enum")
        if (enum != null && isStringEnum(enum)) return hoist(place, node, DeclKind.ENUM)
        val types = typesOf(node) - "null"
        val type =
            when {
                types.size == 1 -> types.single()
                types.isEmpty() -> inferred(node)
                else -> null
            }
        val typed =
            when (type) {
                "string" -> Typed(stringType(place, node))
                "integer" -> Typed(integerType(place, node))
                "number" -> Typed(numberType(place, node))
                "boolean" -> Typed(UnitType.Scalar("bool", emptyList()))
                "array" -> Typed(listType(place, node))
                "object" -> objectType(place, node)
                else -> unknown(place)
            }
        // a Schemata enum's values are names, so an enum of numbers or booleans keeps its type
        if (enum != null && type != null && type != "string") {
            say(
                place,
                ImportCodes.WIDENED,
                "$type enum imported as ${(typed.type as UnitType.Scalar).builtin}; its values are not kept",
            )
        }
        return typed
    }

    /**
     * An inline object, enum or union, declared where the core hoister would declare it: nested in
     * the innermost model as `<Model><Field>`, under a name nothing visible there already has.
     */
    private fun hoist(place: Place, node: JsonNode, kind: DeclKind): Typed {
        val decl = inlineDecl(place, kind)
        decl.built = lowered(decl, place.doc, place.pointer, node)
        return Typed(UnitType.Ref(refText(decl, place.owner)), decl = decl)
    }

    /**
     * A declaration of [kind] for an inline schema at [place], not yet built, nested where the core
     * hoister would nest it under a name nothing visible there already has.
     */
    private fun inlineDecl(place: Place, kind: DeclKind): Decl {
        val holder = place.owner.chain.lastOrNull()
        val space = space(place.owner.namespace)
        if (space.source == null) space.source = place.doc.path
        val names = holder?.names ?: space.names
        val wanted = place.inline
        var name = wanted
        var n = 1
        while (visible(place, name)) name = "$wanted${++n}"
        names.claim(name)
        if (name != wanted) {
            say(
                place.doc,
                place.pointer,
                ImportCodes.RENAMED,
                "inline schema is imported as '$name', since '$wanted' is taken",
                SUFFIX_HELP,
            )
        }
        val decl = Decl(place.owner.namespace, name, holder, null)
        decl.kind = kind
        (holder?.children ?: space.top) += decl
        return decl
    }

    /** Whether a declaration inline at [place] would find [name] already taken. */
    private fun visible(place: Place, name: String): Boolean {
        val space = space(place.owner.namespace)
        val holder = place.owner.chain.lastOrNull()
        return (holder?.names ?: space.names).taken(name) ||
            space.names.taken(name) ||
            place.owner.chain.any { it.names.taken(name) }
    }

    /** The type a schema without a `type` has from the keywords it carries. */
    private fun inferred(node: JsonNode): String? {
        val enum = node.get("enum")?.filter { !it.isNull }.orEmpty()
        return when {
            node.has("properties") || node.has("additionalProperties") -> "object"
            node.has("items") -> "array"
            enum.isEmpty() -> null
            enum.all { it.isTextual } -> "string"
            enum.all { it.isIntegralNumber } -> "integer"
            enum.all { it.isNumber } -> "number"
            enum.all { it.isBoolean } -> "boolean"
            else -> null
        }
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
        if (format != null) unknownFormat(place, format, "string")
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
        val min = bound(place, node, "minimum", "exclusiveMinimum", "min")
        val max = bound(place, node, "maximum", "exclusiveMaximum", "max")
        val written = node.text("format")
        val format = written?.takeIf { it == "int32" || it == "int64" }
        val fits = { b: BigDecimal? -> b == null || (b >= INT32_MIN && b <= INT32_MAX) }
        val int32 =
            fits(min) &&
                fits(max) &&
                (format == "int32" || (format == null && min != null && max != null))
        val builtin = if (int32) "int32" else "int64"
        if (written != null && format == null) unknownFormat(place, written, builtin)
        val (low, high) = if (int32) INT32_MIN to INT32_MAX else INT64_MIN to INT64_MAX
        return UnitType.Scalar(
            builtin,
            listOfNotNull(
                min?.takeIf { it.compareTo(low) != 0 }?.let { "min" to integral(it) },
                max?.takeIf { it.compareTo(high) != 0 }?.let { "max" to integral(it) },
            ),
        )
    }

    private fun numberType(place: Place, node: JsonNode): UnitType {
        val format = node.text("format")
        val builtin = if (format == "float") "float32" else "float64"
        if (format != null && format != "float" && format != "double") {
            unknownFormat(place, format, builtin)
        }
        return UnitType.Scalar(
            builtin,
            listOfNotNull(
                bound(place, node, "minimum", "exclusiveMinimum", "min")?.let {
                    "min" to number(it)
                },
                bound(place, node, "maximum", "exclusiveMaximum", "max")?.let {
                    "max" to number(it)
                },
            ),
        )
    }

    /**
     * A bound from [inclusive], or from [exclusive] approximated as inclusive: 3.0 writes
     * `exclusiveMinimum: true` beside `minimum`, 3.1 writes the bound as `exclusiveMinimum` itself.
     */
    private fun bound(
        place: Place,
        node: JsonNode,
        inclusive: String,
        exclusive: String,
        refinement: String,
    ): BigDecimal? {
        val flag = node.get(exclusive)
        val value =
            when {
                flag == null || flag.isFalse() -> return node.decimal(inclusive)
                flag.isNumber -> flag.decimalValue()
                flag.isBoolean -> node.decimal(inclusive) ?: return null
                else -> return node.decimal(inclusive)
            }
        val shown = number(value)
        say(place, ImportCodes.APPROXIMATED, "$exclusive $shown is imported as $refinement $shown")
        return value
    }

    private fun unknownFormat(place: Place, format: String, builtin: String) {
        say(
            place,
            ImportCodes.APPROXIMATED,
            "format '$format' has no Schemata type; imported as $builtin",
        )
    }

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
        if (node.has("properties") || extra?.isFalse() == true) {
            return hoist(place, node, DeclKind.MODEL)
        }
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

    /** A union member: its position in the choice, where it is, its type and its doc. */
    private class Member(val index: Int, val place: Place, val type: UnitType, val doc: String?)

    /** A type, whether it admits null, and the declaration it names, if it names one. */
    private data class Typed(
        val type: UnitType,
        val nullable: Boolean = false,
        val decl: Decl? = null,
    )

    private companion object {
        // the field a model made for a member carried by its key holds that key in
        const val KEY_FIELD = "id"
        // the option that makes a field its model's key
        val KEY_OPTION: List<Pair<String, String?>> = listOf("id" to null)
        val STRING = UnitType.Scalar("string", emptyList())
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

        /** Keys of an `allOf` part that describe the schema without giving it a type. */
        val ANNOTATIONS = setOf("description", "title")

        val DROPPED_KEYWORDS =
            listOf(
                "readOnly",
                "writeOnly",
                "example",
                "examples",
                "xml",
                "externalDocs",
                "not",
                "if",
                "then",
                "else",
                "patternProperties",
                "uniqueItems",
                "multipleOf",
            )

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
private fun admitsNull(node: JsonNode): Boolean =
    node.isTrue("nullable") ||
        "null" in typesOf(node) ||
        (node.get("oneOf") ?: node.get("anyOf"))?.any(::isNullSchema) == true

/** A number as Schemata source: an integer as its digits, any other as plain decimal text. */
private fun literal(node: JsonNode): String =
    if (node.isIntegralNumber) node.bigIntegerValue().toString()
    else node.decimalValue().toPlainString()

/** A bound as Schemata source: an integer as its digits, any other as plain decimal text. */
private fun number(n: BigDecimal): String = integralOrNull(n) ?: n.toPlainString()

private fun integral(n: BigDecimal): String = integralOrNull(n) ?: n.toPlainString()

private fun integralOrNull(n: BigDecimal): String? =
    try {
        n.toBigIntegerExact().toString()
    } catch (_: ArithmeticException) {
        null
    }
