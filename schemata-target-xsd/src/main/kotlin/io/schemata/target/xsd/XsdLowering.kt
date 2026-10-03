package io.schemata.target.xsd

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.ListOf
import io.schemata.core.ir.MapOf
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.Type
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionMember
import io.schemata.core.ir.UnionType
import io.schemata.core.ir.Value
import io.schemata.core.ir.declarationPath
import io.schemata.core.ir.kindWord
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered
import io.schemata.target.NameClaims
import io.schemata.target.OverrideNames
import io.schemata.target.TypeText
import io.schemata.target.bool
import io.schemata.target.collidingNamespaces
import io.schemata.target.flag
import io.schemata.target.string
import io.schemata.target.unionMemberStem

/** Lowers the IR to an [XsdModel]; every decision and every lossy report lives here. */
object XsdLowering {
    fun lower(schema: Schema): Lowered<XsdModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val uris = LinkedHashMap<String, String>()
        schema.namespaces.forEach { ns ->
            val override = ns.annotations.string("xsd", "namespace")
            if (override != null && !XsdNames.isAbsoluteUri(override)) {
                diagnostics +=
                    Diagnostic(
                        XsdCodes.INVALID_OVERRIDE,
                        "namespace '${ns.name}': @xsd(namespace = \"$override\") is not an absolute URI",
                        ns.span,
                        help =
                            "use an absolute URI without a fragment, such as `urn:example:orders`",
                    )
                uris[ns.name] = "urn:schemata:${ns.name}"
            } else {
                uris[ns.name] = XsdNames.namespaceOf(ns)
            }
        }
        collidingNamespaces(schema.namespaces) { uris.getValue(it.name) }
            .forEach { group ->
                diagnostics +=
                    Diagnostic(
                        XsdCodes.NAMESPACE_COLLISION,
                        "namespaces ${group.joinToString(" and ") { it.name }} both lower to target namespace '${uris.getValue(group.first().name)}'",
                        group[1].span,
                        help = "set `@xsd(namespace = \"…\")` on one of them",
                    )
            }
        val names = SchemaNames(schema, diagnostics)
        val files =
            schema.namespaces.map { FileLowering(schema, names, uris, it, diagnostics).lower() }
        return Lowered(XsdModel(files), diagnostics)
    }

    /**
     * Names shared by every file: a declaration's XSD type name and the validated `@xsd(name)`
     * overrides of declarations and enum values. Each override is checked once for the whole
     * schema, however many files refer to the declaration.
     */
    internal class SchemaNames(private val schema: Schema, diagnostics: MutableList<Diagnostic>) {
        val overrides =
            OverrideNames(
                "xsd",
                XsdCodes.INVALID_OVERRIDE,
                diagnostics,
                { if (XsdNames.isNCName(it)) null else "is not a valid XML name" },
            ) {
                "use letters, digits, underscores, hyphens, and dots, starting with a letter or underscore"
            }

        /**
         * The type name of the declaration at [qn]: each enclosing declaration's segment is its
         * override when it has a valid one, so `@xsd(name = "Purchase") record Order` nesting
         * `record Line` gives `PurchaseType` and `PurchaseLineType`.
         */
        fun xsdTypeName(qn: QualifiedName): String =
            XsdNames.typeName(
                schema.declarationPath(qn).map { overrides.nameOverride(it) ?: it.name }
            )

        /** A default's attribute text; an enum default is the value's name in the enumeration. */
        fun defaultText(value: Value): String {
            if (value !is EnumRef) return XsdTypes.text(value)
            val enum = schema.lookup(value.enum) as EnumType
            return overrides.enumValueName(enum, enum.values.first { it.name == value.value })
        }
    }

    /**
     * One namespace's file. Enums, records, and unions become types; top-level records also get a
     * global element. A reference into another namespace adds an import for it, prefixed `nsN` in
     * first-use order.
     */
    internal class FileLowering(
        private val schema: Schema,
        private val names: SchemaNames,
        private val uris: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        /**
         * Who owns each XSD name: `"type:<name>"` for a complex, enumeration, or choice type,
         * `"unique:<name>"` for a map's `xs:unique` constraint, `"element:<name>"` for a top-level
         * record's global element, and `"element:<declaring path>/<field or member name>"` for a
         * field's or union member's element, scoped by the declaring record or union's full path so
         * two of them sharing a simple name never collide on a same-named field or member.
         */
        private val claims =
            NameClaims(
                XsdCodes.NAME_COLLISION,
                "rename one of them, or set `@xsd(name = \"…\")` on one",
                diagnostics,
            )

        /** Imports accumulated as cross-namespace references are lowered, in first-use order. */
        private val imports = mutableListOf<XsdImport>()

        /** Prefixes already allocated for another namespace's references, in first-use order. */
        private val prefixes = mutableMapOf<String, String>()

        fun lower(): XsdFile {
            val types = mutableListOf<XsdType>()
            val elements = mutableListOf<XsdElement>()
            namespace.declarations.forEach { decl ->
                types += types(decl, emptyList())
                if (decl is RecordType && decl.annotations.bool("xsd", "root") != false) {
                    elements += globalElement(decl)
                }
            }
            return XsdFile(
                XsdNames.pathOf(namespace),
                uris.getValue(namespace.name),
                imports,
                types,
                elements,
                elementFormDefault =
                    namespace.annotations.string("xsd", "element_form") ?: "qualified",
                attributeFormDefault =
                    namespace.annotations.string("xsd", "attribute_form") ?: "unqualified",
            )
        }

        /**
         * [path] is the enclosing declarations' names, each replaced by its `@xsd(name)` override;
         * it scopes field and member claims. Nested declarations are flattened after their own
         * type.
         */
        private fun types(decl: TypeDecl, path: List<String>): List<XsdType> {
            val here = path + (names.overrides.nameOverride(decl) ?: decl.name)
            val own =
                when (decl) {
                    is EnumType -> enum(decl)
                    is RecordType -> record(decl, here)
                    is UnionType -> choice(decl, here)
                }
            claims.claim(
                key = "type:${own.name}",
                holder = "${decl.kindWord} '${decl.name}'",
                span = decl.nameSpan,
                display = own.name,
                kind = "type",
            )
            return listOf(own) + decl.nested.flatMap { types(it, here) }
        }

        private fun enum(enum: EnumType): XsdEnumeration {
            val typeName = names.xsdTypeName(enum.qualifiedName)
            return XsdEnumeration(
                typeName,
                enum.doc,
                enum.values.map { value ->
                    val string = names.overrides.enumValueName(enum, value)
                    claims.claim(
                        key = "value:$typeName/$string",
                        holder = "enum value '${enum.name}.${value.name}'",
                        span = value.nameSpan,
                        display = string,
                        kind = "enumeration value",
                    )
                    XsdEnumValue(string, value.doc)
                },
            )
        }

        /**
         * A record's fields in order: `@xsd(mixed)` makes the type mixed and adds nothing to the
         * sequence, `@xsd(any_attribute)` becomes the type's attribute wildcard, `@xsd(any)` an
         * element wildcard in place, `@xsd(attribute)` an attribute, and every other field an
         * element.
         */
        private fun record(record: RecordType, path: List<String>): XsdComplex {
            val sequence = mutableListOf<XsdParticle>()
            val attributes = mutableListOf<XsdAttribute>()
            var anyAttribute: XsdAnyAttribute? = null
            var mixed = false
            record.fields.forEach { f ->
                when {
                    f.annotations.flag("xsd", "mixed") -> if (checkMixed(record, f)) mixed = true
                    f.annotations.flag("xsd", "any_attribute") ->
                        anyAttribute(record, f)?.let { anyAttribute = it }
                    f.annotations.flag("xsd", "any") -> any(record, f)?.let { sequence += it }
                    f.annotations.flag("xsd", "attribute") ->
                        attribute(record, f, path)?.let { attributes += it }
                    f.annotations.flag("xsd", "any_type") ->
                        anyTypeField(record, f, path)?.let { sequence += it }
                    else -> sequence += field(record, f, path)
                }
            }
            record.fields.forEach { checkWildcardKeys(record, it) }
            return XsdComplex(
                names.xsdTypeName(record.qualifiedName),
                record.doc,
                sequence,
                attributes,
                anyAttribute,
                mixed,
            )
        }

        /** A `string` with no refinements. */
        private fun isPlainString(type: Type): Boolean =
            type is Scalar && type.builtin == Builtin.STRING && type.refinements.isEmpty

        /**
         * A non-nullable `list<string>` of plain strings; the list's own bounds are free, and
         * [nullableElements] admits `list<string?>`.
         */
        private fun isStringList(field: Field, nullableElements: Boolean = false): Boolean {
            val t = field.type
            return !field.nullable &&
                t is ListOf &&
                (nullableElements || !t.nullableElement) &&
                isPlainString(t.element)
        }

        /**
         * Reports `@xsd([key])` on [field], whose shape cannot carry it; [takes] names the shapes
         * that can.
         */
        private fun notAllowed(record: RecordType, field: Field, key: String, takes: String) {
            diagnostics +=
                Diagnostic(
                    XsdCodes.ATTRIBUTE_NOT_ALLOWED,
                    "${fieldWhere(record, field)}: @xsd($key) is not allowed on a " +
                        "${TypeText.of(field.type, field.nullable)}; it takes $takes",
                    field.span,
                    help = "remove the annotation, or declare the field as $takes",
                )
        }

        /**
         * True when [field] can stand for a mixed type's character data: a plain `string`, nullable
         * or not. The field itself has no element.
         */
        private fun checkMixed(record: RecordType, field: Field): Boolean {
            if (isPlainString(field.type)) return true
            notAllowed(record, field, "mixed", "a string or string?")
            return false
        }

        /** The record's attribute wildcard for a `map<string, string>` field; null otherwise. */
        private fun anyAttribute(record: RecordType, field: Field): XsdAnyAttribute? {
            val t = field.type
            if (
                field.nullable ||
                    t !is MapOf ||
                    t.nullableValue ||
                    !t.refinements.isEmpty ||
                    !isPlainString(t.key) ||
                    !isPlainString(t.value)
            ) {
                notAllowed(record, field, "any_attribute", "a map<string, string>")
                return null
            }
            return XsdAnyAttribute(
                field.annotations.string("xsd", "wildcard"),
                field.annotations.string("xsd", "process") ?: "lax",
            )
        }

        /**
         * An element wildcard in place of [field]: once for a `string` (optional for `string?`),
         * repeated within the list's bounds for a `list<string>`. A wildcard has no name, so
         * nothing is claimed.
         */
        private fun any(record: RecordType, field: Field): XsdAny? {
            val t = field.type
            val (min, max) =
                when {
                    isPlainString(t) -> (if (field.nullable) 0 else 1) to 1
                    isStringList(field) -> {
                        val r = (t as ListOf).refinements
                        (r.min?.toInt() ?: 0) to r.max?.toInt()
                    }
                    else -> {
                        notAllowed(record, field, "any", "a string, string?, or list<string>")
                        return null
                    }
                }
            return XsdAny(
                min,
                max,
                field.annotations.string("xsd", "wildcard"),
                field.annotations.string("xsd", "process") ?: "lax",
            )
        }

        /**
         * [field] as an element of type `xs:anyType`: a `string` or `string?` once, a
         * `list<string>` repeated (nillable for `list<string?>`).
         */
        private fun anyTypeField(
            record: RecordType,
            field: Field,
            path: List<String>,
        ): XsdElement? {
            if (!isPlainString(field.type) && !isStringList(field, nullableElements = true)) {
                notAllowed(record, field, "any_type", "a string, string?, or list<string>")
                return null
            }
            return field(record, field, path).copy(type = XsdTypeRef.Builtin("xs:anyType"))
        }

        /**
         * `@xsd(process)` and `@xsd(wildcard)` describe a wildcard, so they need `@xsd(any)` or
         * `@xsd(any_attribute)` on the same field.
         */
        private fun checkWildcardKeys(record: RecordType, field: Field) {
            if (
                field.annotations.flag("xsd", "any") ||
                    field.annotations.flag("xsd", "any_attribute")
            )
                return
            listOf("process", "wildcard")
                .filter { field.annotations.string("xsd", it) != null }
                .forEach { key ->
                    diagnostics +=
                        Diagnostic(
                            XsdCodes.ATTRIBUTE_NOT_ALLOWED,
                            "${fieldWhere(record, field)}: @xsd($key) needs @xsd(any) or " +
                                "@xsd(any_attribute) on the same field",
                            field.span,
                            help = "add @xsd(any) or @xsd(any_attribute), or remove @xsd($key)",
                        )
                }
        }

        /**
         * A field claiming `@xsd(attribute)` as an `XsdAttribute`, claimed in the same per-record
         * scope as elements so a field named like an element in the same record collides. Only a
         * scalar or an enum reference can be an attribute; any other shape is reported and skipped.
         */
        private fun attribute(record: RecordType, field: Field, path: List<String>): XsdAttribute? {
            val where = fieldWhere(record, field)
            val shape = attributeShape(field.type)
            if (shape != null) {
                diagnostics +=
                    Diagnostic(
                        XsdCodes.ATTRIBUTE_NOT_ALLOWED,
                        "$where: @xsd(attribute) is not allowed on a $shape",
                        field.span,
                        help =
                            "remove the annotation; only scalar and enum fields lower to attributes",
                    )
                return null
            }
            val name = claimFieldName(field, path, where, "attribute")
            return XsdAttribute(
                name = name,
                type = typeRef(field.type, where, field.span),
                required = !field.nullable && field.default == null,
                default = field.default?.let(names::defaultText),
                doc = field.doc,
            )
        }

        /** Null when [type] can be an attribute (a scalar or an enum), else its shape's word. */
        private fun attributeShape(type: Type): String? =
            when (type) {
                is Scalar -> null
                is Ref ->
                    when (schema.lookup(type.target)) {
                        is EnumType -> null
                        is RecordType -> "record"
                        is UnionType -> "union"
                    }
                is ListOf -> "list"
                is MapOf -> "map"
            }

        private fun fieldWhere(record: RecordType, field: Field): String =
            "field '${record.name}.${field.name}'"

        /**
         * [field]'s element or attribute name (its `@xsd(name)`, else its own name), claimed in the
         * record's scope, which elements and attributes share; [displayKind] is the word the
         * collision message uses for it.
         */
        private fun claimFieldName(
            field: Field,
            path: List<String>,
            where: String,
            displayKind: String,
        ): String {
            val name =
                names.overrides.overrideName(field.annotations, where, field.nameSpan) ?: field.name
            claims.claim(
                key = "element:${path.joinToString(".")}/$name",
                holder = where,
                span = field.nameSpan,
                display = name,
                kind = displayKind,
            )
            return name
        }

        private fun choice(union: UnionType, path: List<String>): XsdChoice =
            XsdChoice(
                names.xsdTypeName(union.qualifiedName),
                union.doc,
                union.members.map { unionMember(union, it, path) },
            )

        /**
         * A union member as an element: a `Ref` is named for the referenced declaration (its
         * `@xsd(name)` override, else [XsdNames.elementName] of its own name), a scalar for its
         * builtin's type name. Claimed per union, keyed by the union's own path so two unions never
         * collide with each other's members.
         */
        private fun unionMember(
            union: UnionType,
            member: UnionMember,
            path: List<String>,
        ): XsdElement {
            val name = unionMemberStem(member.type, schema) { names.overrides.nameOverride(it) }
            val declName =
                (member.type as? Ref)?.let { schema.lookup(it.target).name }
                    ?: (member.type as Scalar).builtin.typeName
            claims.claim(
                key = "element:${path.joinToString(".")}/$name",
                holder = "union member '$declName'",
                span = member.span,
                display = name,
                kind = "element",
            )
            return XsdElement(
                name,
                typeRef(member.type, "union '${union.name}' member", member.span),
                doc = member.doc,
            )
        }

        /** A top-level record's global element, named in lower snake unless overridden. */
        private fun globalElement(record: RecordType): XsdElement {
            val name = names.overrides.nameOverride(record) ?: XsdNames.elementName(record.name)
            claims.claim(
                key = "element:$name",
                holder = "record '${record.name}'",
                span = record.nameSpan,
                display = name,
                kind = "element",
            )
            return XsdElement(
                name,
                XsdTypeRef.Named("tns", names.xsdTypeName(record.qualifiedName), simple = false),
            )
        }

        /**
         * A record's field as an element; the claim is keyed by the record's declaring path (not
         * its XSD type name) so two records that happen to lower to the same type name still get
         * independent field claims, while two same-named records nested under different parents
         * never collide either.
         */
        private fun field(record: RecordType, field: Field, path: List<String>): XsdElement {
            val where = fieldWhere(record, field)
            val name = claimFieldName(field, path, where, "element")
            return when (val t = field.type) {
                is Scalar,
                is Ref ->
                    XsdElement(
                        name = name,
                        type = typeRef(t, where, field.span),
                        minOccurs = if (field.nullable || field.default != null) 0 else 1,
                        default = field.default?.let(names::defaultText),
                        doc = field.doc,
                    )
                is ListOf -> {
                    if (field.nullable) {
                        diagnostics +=
                            Diagnostic(
                                XsdCodes.LOSSY,
                                "$where: a nullable list has no XSD representation; lowered to " +
                                    "an optional repeated element",
                                field.span,
                                help =
                                    "declare the list as `list<T>`; an absent list already means empty",
                            )
                    }
                    val element = listElement(name, t, uniqueBase(record, name), where, field.span)
                    element.copy(
                        minOccurs = if (field.nullable) 0 else element.minOccurs,
                        doc = field.doc,
                    )
                }
                is MapOf ->
                    mapElement(name, t, uniqueBase(record, name), where, field.span)
                        .copy(minOccurs = if (field.nullable) 0 else 1, doc = field.doc)
            }
        }

        /**
         * The base for any map nested anywhere under [record]'s field [name], at any depth; a map
         * directly on the field uses it bare, one nested a level down appends `_item` per level, so
         * every `xs:unique` name in the file stays distinct.
         */
        private fun uniqueBase(record: RecordType, name: String): String =
            "${names.xsdTypeName(record.qualifiedName)}_$name"

        /** [type] as a reference: a builtin or restricted scalar, or a named record/enum/union. */
        private fun typeRef(type: Type, where: String, span: Span): XsdTypeRef =
            when (type) {
                is Scalar -> scalarRef(type, where, span)
                is Ref -> {
                    val target = schema.lookup(type.target)
                    XsdTypeRef.Named(
                        prefixFor(type.target.namespace),
                        names.xsdTypeName(type.target),
                        simple = target is EnumType,
                    )
                }
                is ListOf,
                is MapOf ->
                    error("typeRef does not accept a collection; lower it as an element instead")
            }

        /**
         * `"tns"` for this file's own namespace; otherwise the prefix allocated for [ns] on its
         * first reference (`ns1`, `ns2`, …), recording the import that goes with it.
         */
        private fun prefixFor(ns: String): String {
            if (ns == namespace.name) return "tns"
            return prefixes.getOrPut(ns) {
                val prefix = "ns${prefixes.size + 1}"
                val other = schema.namespaces.first { it.name == ns }
                imports +=
                    XsdImport(
                        uris.getValue(ns),
                        XsdNames.relativePath(XsdNames.pathOf(namespace), XsdNames.pathOf(other)),
                        prefix,
                    )
                prefix
            }
        }

        /**
         * A repeated element for [list], its bounds and nillability from its refinements. Any map
         * nested inside it names its uniqueness constraint from [uniqueBase].
         */
        private fun listElement(
            name: String,
            list: ListOf,
            uniqueBase: String,
            where: String,
            span: Span,
        ): XsdElement =
            XsdElement(
                name = name,
                type = itemTypeRef(list.element, uniqueBase, where, span),
                minOccurs = list.refinements.min?.toInt() ?: 0,
                maxOccurs = list.refinements.max?.toInt(),
                nillable = list.nullableElement,
            )

        /**
         * A list or map element's item type: a direct reference, or an inner collection nested
         * under the name `item`.
         */
        private fun itemTypeRef(
            element: Type,
            uniqueBase: String,
            where: String,
            span: Span,
        ): XsdTypeRef =
            when (element) {
                is Scalar,
                is Ref -> typeRef(element, where, span)
                is ListOf,
                is MapOf -> nestedItem(element, uniqueBase, where, span)
            }

        /**
         * The wrapper element for a map field: `entry` elements keyed by an attribute, with a
         * uniqueness constraint named `"${uniqueBase}_key"`. A map nested one level deeper — inside
         * this map's value, or inside a list — extends [uniqueBase] with `_item`, so every
         * `xs:unique` in the file has a distinct name however deeply collections nest.
         */
        private fun mapElement(
            name: String,
            map: MapOf,
            uniqueBase: String,
            where: String,
            span: Span,
        ): XsdElement {
            val key = XsdAttribute("key", typeRef(map.key, where, span), required = true)
            val unique = "${uniqueBase}_key"
            claims.claim(
                key = "unique:$unique",
                holder = where,
                span = span,
                display = unique,
                kind = "uniqueness constraint",
            )
            val entry =
                XsdElement(
                    name = "entry",
                    type = entryTypeRef(map.value, key, uniqueBase, where, span),
                    minOccurs = map.refinements.min?.toInt() ?: 0,
                    maxOccurs = map.refinements.max?.toInt(),
                    nillable = map.nullableValue,
                )
            return XsdElement(
                name = name,
                type = XsdTypeRef.Anonymous(listOf(entry)),
                unique = unique,
            )
        }

        /**
         * A map entry's type: an extension carrying the key attribute for a plain scalar, enum,
         * record, or union value; a `value` element wrapping a refined scalar, since an extension
         * cannot carry facets; or a nested collection lowered as `item`. A record value that
         * already has an attribute named `key` is reported and lowered as the record itself.
         */
        private fun entryTypeRef(
            value: Type,
            key: XsdAttribute,
            uniqueBase: String,
            where: String,
            span: Span,
        ): XsdTypeRef =
            when (value) {
                is Scalar -> {
                    val ref = typeRef(value, where, span)
                    if (ref is XsdTypeRef.Restricted)
                        XsdTypeRef.Anonymous(listOf(XsdElement("value", ref)), listOf(key))
                    else XsdTypeRef.Extension(ref, listOf(key))
                }
                is Ref -> {
                    val ref = typeRef(value, where, span)
                    val clash = keyAttribute(value)
                    if (clash == null) XsdTypeRef.Extension(ref, listOf(key))
                    else {
                        diagnostics +=
                            Diagnostic(
                                XsdCodes.NAME_COLLISION,
                                "$where: map entries lower to attribute 'key', already used by " +
                                    "${fieldWhere(clash.first, clash.second)} " +
                                    "(${clash.second.nameSpan.file}:${clash.second.nameSpan.startLine})",
                                span,
                                help = "rename one of them, or set `@xsd(name = \"…\")` on one",
                            )
                        ref
                    }
                }
                is ListOf,
                is MapOf -> nestedItem(value, uniqueBase, where, span, listOf(key))
            }

        /**
         * The record and field behind [value]'s own attribute named `key`, which a map entry
         * extending it cannot add again; null when [value] is not such a record.
         */
        private fun keyAttribute(value: Ref): Pair<RecordType, Field>? {
            val record = schema.lookup(value.target) as? RecordType ?: return null
            val field =
                record.fields.firstOrNull { f ->
                    f.annotations.flag("xsd", "attribute") &&
                        attributeShape(f.type) == null &&
                        (f.annotations.string("xsd", "name")?.takeIf(XsdNames::isNCName)
                            ?: f.name) == "key"
                } ?: return null
            return record to field
        }

        /**
         * The anonymous wrapper for [element] (a list or a map) lowered under the name `item`,
         * carrying [attributes]; its own nested maps, if any, extend [uniqueBase] one more level
         * with `_item`.
         */
        private fun nestedItem(
            element: Type,
            uniqueBase: String,
            where: String,
            span: Span,
            attributes: List<XsdAttribute> = emptyList(),
        ): XsdTypeRef.Anonymous {
            val itemBase = "${uniqueBase}_item"
            val item =
                when (element) {
                    is ListOf -> listElement("item", element, itemBase, where, span)
                    is MapOf -> mapElement("item", element, itemBase, where, span)
                    is Scalar,
                    is Ref -> error("nestedItem only accepts a list or a map")
                }
            return XsdTypeRef.Anonymous(listOf(item), attributes)
        }

        /** The type ref for a scalar field, reporting a pattern XSD 1.0 cannot express as lossy. */
        private fun scalarRef(scalar: Scalar, where: String, span: Span): XsdTypeRef {
            val xsName = XsdTypes.xsName(scalar.builtin)
            var refinements = scalar.refinements
            val pattern = refinements.pattern
            if (
                pattern != null &&
                    (scalar.builtin == Builtin.STRING || scalar.builtin == Builtin.BYTES)
            ) {
                val converted = XsdTypes.pattern(pattern)
                val bad = converted.unsupported
                if (bad != null) {
                    diagnostics +=
                        Diagnostic(
                            XsdCodes.LOSSY,
                            "$where: pattern uses $bad, which XSD 1.0 cannot express; dropped",
                            span,
                            help =
                                "rewrite the pattern without $bad, or enforce it in application code",
                        )
                    refinements = refinements.copy(pattern = null)
                }
            }
            val facets = XsdTypes.facets(scalar.builtin, refinements)
            return if (facets.isEmpty()) XsdTypeRef.Builtin(xsName)
            else XsdTypeRef.Restricted(xsName, facets)
        }
    }
}
