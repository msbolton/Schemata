package io.schemata.target.xsd

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumRef
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.EnumValue
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
import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import io.schemata.target.Lowered

/** Lowers the IR to an [XsdModel]; every decision and every lossy report lives here. */
object XsdLowering {
    fun lower(schema: Schema): Lowered<XsdModel> {
        val diagnostics = mutableListOf<Diagnostic>()
        val uris = LinkedHashMap<String, String>()
        schema.namespaces.forEach { ns ->
            XsdNames.override(ns.annotations, "namespace")?.let {
                if (!XsdNames.isAbsoluteUri(it)) {
                    diagnostics +=
                        Diagnostic(
                            XsdCodes.INVALID_OVERRIDE,
                            "namespace '${ns.name}': @xsd(namespace = \"$it\") is not an absolute URI",
                            ns.span,
                            help = "use an absolute URI such as `urn:example:orders`",
                        )
                }
            }
            uris[ns.name] = XsdNames.namespaceOf(ns)
        }
        uris.entries
            .groupBy({ it.value }, { it.key })
            .values
            .filter { it.size > 1 }
            .forEach { names ->
                val second = schema.namespaces.first { it.name == names[1] }
                diagnostics +=
                    Diagnostic(
                        XsdCodes.NAMESPACE_COLLISION,
                        "namespaces ${names.joinToString(" and ")} both lower to target namespace '${uris.getValue(names.first())}'",
                        second.span,
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
    internal class SchemaNames(
        private val schema: Schema,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        private val declOverrides = mutableMapOf<QualifiedName, String?>()
        private val valueOverrides = mutableMapOf<Pair<QualifiedName, String>, String?>()
        private val typeNames = mutableMapOf<QualifiedName, String>()

        /**
         * The type name of the declaration at [qn]: each enclosing declaration's segment is its
         * override when it has a valid one, so `@xsd(name = "Purchase") record Order` nesting
         * `record Line` gives `PurchaseType` and `PurchaseLineType`.
         */
        fun xsdTypeName(qn: QualifiedName): String =
            typeNames.getOrPut(qn) {
                val segments =
                    qn.path.indices.map { i ->
                        val decl = schema.lookup(QualifiedName(qn.namespace, qn.path.take(i + 1)))
                        nameOverride(decl) ?: decl.name
                    }
                XsdNames.typeName(segments)
            }

        /** [decl]'s validated `@xsd(name)`, memoized so an invalid one is reported only once. */
        fun nameOverride(decl: TypeDecl): String? =
            declOverrides.memo(decl.qualifiedName) {
                overrideName(decl.annotations, "${kindOf(decl)} '${decl.name}'", decl.nameSpan)
            }

        /** [value]'s name in the enumeration: its valid `@xsd(name)`, else its own name. */
        fun enumValueName(enum: EnumType, value: EnumValue): String =
            valueOverrides.memo(enum.qualifiedName to value.name) {
                overrideName(
                    value.annotations,
                    "enum value '${enum.name}.${value.name}'",
                    value.nameSpan,
                )
            } ?: value.name

        /**
         * Like `getOrPut`, but a stored null counts as computed, so [compute] runs once per key.
         */
        private fun <K> MutableMap<K, String?>.memo(key: K, compute: () -> String?): String? {
            if (key !in this) this[key] = compute()
            return getValue(key)
        }

        /** A default's attribute text; an enum default is the value's name in the enumeration. */
        fun defaultText(value: Value): String {
            if (value !is EnumRef) return XsdTypes.text(value)
            val enum = schema.lookup(value.enum) as EnumType
            return enumValueName(enum, enum.values.first { it.name == value.value })
        }

        /** The `@xsd(name)` value, or null (with a diagnostic) when it is not a valid XML name. */
        fun overrideName(annotations: Annotations, where: String, span: Span): String? {
            val value = XsdNames.override(annotations, "name") ?: return null
            if (XsdNames.isNCName(value)) return value
            diagnostics +=
                Diagnostic(
                    XsdCodes.INVALID_OVERRIDE,
                    "$where: @xsd(name = \"$value\") is not a valid XML name",
                    span,
                    help =
                        "use letters, digits, underscores, hyphens, and dots, starting with a letter or underscore",
                )
            return null
        }
    }

    private fun kindOf(decl: TypeDecl): String =
        when (decl) {
            is RecordType -> "record"
            is EnumType -> "enum"
            is UnionType -> "union"
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
         * `"element:<name>"` for a top-level record's global element, and `"element:<declaring
         * path>/<field or member name>"` for a field's or union member's element, scoped by the
         * declaring record or union's full path so two of them sharing a simple name never collide
         * on a same-named field or member.
         */
        private val claims = mutableMapOf<String, Pair<String, Span>>()

        /** Imports accumulated as cross-namespace references are lowered, in first-use order. */
        private val imports = mutableListOf<XsdImport>()

        /** Prefixes already allocated for another namespace's references, in first-use order. */
        private val prefixes = mutableMapOf<String, String>()

        fun lower(): XsdFile {
            val types = mutableListOf<XsdType>()
            val elements = mutableListOf<XsdElement>()
            namespace.declarations.forEach { decl ->
                types += types(decl, emptyList())
                if (decl is RecordType && XsdNames.bool(decl.annotations, "root") != false) {
                    elements += globalElement(decl)
                }
            }
            return XsdFile(
                XsdNames.pathOf(namespace),
                uris.getValue(namespace.name),
                imports,
                types,
                elements,
            )
        }

        /**
         * [path] is the enclosing declarations' names, each replaced by its `@xsd(name)` override;
         * it scopes field and member claims. Nested declarations are flattened after their own
         * type.
         */
        private fun types(decl: TypeDecl, path: List<String>): List<XsdType> {
            val here = path + (names.nameOverride(decl) ?: decl.name)
            val own =
                when (decl) {
                    is EnumType -> enum(decl)
                    is RecordType -> record(decl, here)
                    is UnionType -> choice(decl, here)
                }
            claim("type", own.name, "${kindOf(decl)} '${decl.name}'", decl.nameSpan)
            return listOf(own) + decl.nested.flatMap { types(it, here) }
        }

        private fun enum(enum: EnumType): XsdEnumeration =
            XsdEnumeration(
                names.xsdTypeName(enum.qualifiedName),
                enum.doc,
                enum.values.map { XsdEnumValue(names.enumValueName(enum, it), it.doc) },
            )

        private fun record(record: RecordType, path: List<String>): XsdComplex {
            val sequence = mutableListOf<XsdElement>()
            val attributes = mutableListOf<XsdAttribute>()
            record.fields.forEach { f ->
                if (XsdNames.flag(f.annotations, "attribute")) {
                    attribute(record, f, path)?.let { attributes += it }
                } else {
                    sequence += field(record, f, path)
                }
            }
            return XsdComplex(
                names.xsdTypeName(record.qualifiedName),
                record.doc,
                sequence,
                attributes,
            )
        }

        /**
         * A field claiming `@xsd(attribute)` as an `XsdAttribute`, claimed in the same per-record
         * scope as elements so a field named like an element in the same record collides. Only a
         * scalar or an enum reference can be an attribute; any other shape is reported and skipped.
         */
        private fun attribute(record: RecordType, field: Field, path: List<String>): XsdAttribute? {
            val where = fieldWhere(record, field)
            val shape =
                when (val t = field.type) {
                    is Scalar -> null
                    is Ref ->
                        when (schema.lookup(t.target)) {
                            is EnumType -> null
                            is RecordType -> "record"
                            is UnionType -> "union"
                        }
                    is ListOf -> "list"
                    is MapOf -> "map"
                }
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
            val name = names.overrideName(field.annotations, where, field.nameSpan) ?: field.name
            claim(
                "element",
                "${path.joinToString(".")}/$name",
                where,
                field.nameSpan,
                displayName = name,
                displayKind = displayKind,
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
            val (name, declName) =
                when (val t = member.type) {
                    is Ref -> {
                        val target = schema.lookup(t.target)
                        (names.nameOverride(target) ?: XsdNames.elementName(target.name)) to
                            target.name
                    }
                    is Scalar -> t.builtin.typeName to t.builtin.typeName
                    is ListOf,
                    is MapOf -> error("union member cannot be a collection")
                }
            claim(
                "element",
                "${path.joinToString(".")}/$name",
                "union member '$declName'",
                member.span,
                displayName = name,
            )
            return XsdElement(
                name,
                typeRef(member.type, "union '${union.name}' member", member.span),
                doc = member.doc,
            )
        }

        /** A top-level record's global element, named in lower snake unless overridden. */
        private fun globalElement(record: RecordType): XsdElement {
            val name = names.nameOverride(record) ?: XsdNames.elementName(record.name)
            claim("element", name, "record '${record.name}'", record.nameSpan)
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
                    listElement(name, t, uniqueBase(record, name), where, field.span)
                        .copy(doc = field.doc)
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
                unique = "${uniqueBase}_key",
            )
        }

        /**
         * A map entry's type: an extension carrying the key attribute for a plain scalar, enum,
         * record, or union value; a `value` element wrapping a refined scalar, since an extension
         * cannot carry facets; or a nested collection lowered as `item`.
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
                is Ref -> XsdTypeRef.Extension(typeRef(value, where, span), listOf(key))
                is ListOf,
                is MapOf -> nestedItem(value, uniqueBase, where, span, listOf(key))
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

        /**
         * Reports [XsdCodes.NAME_COLLISION] when `"$kind:$key"` was already claimed. [key] scopes
         * uniqueness (a record or union's declaring path, for a field or member claim);
         * [displayName] is the plain name shown in the message, defaulting to [key] for claims that
         * are already unscoped (types and global elements); [displayKind] is the word the message
         * uses for what the holder lowers to, so an attribute sharing the element scope still reads
         * as an attribute.
         */
        private fun claim(
            kind: String,
            key: String,
            holder: String,
            span: Span,
            displayName: String = key,
            displayKind: String = kind,
        ) {
            val fullKey = "$kind:$key"
            val previous = claims[fullKey]
            if (previous == null) {
                claims[fullKey] = holder to span
            } else {
                diagnostics +=
                    Diagnostic(
                        XsdCodes.NAME_COLLISION,
                        "$holder lowers to $displayKind '$displayName', already used by ${previous.first} " +
                            "(${previous.second.file}:${previous.second.startLine})",
                        span,
                        help = "rename one of them, or set `@xsd(name = \"…\")` on one",
                    )
            }
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
