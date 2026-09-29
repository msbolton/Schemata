package io.schemata.target.xsd

import io.schemata.core.ir.Annotations
import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
import io.schemata.core.ir.QualifiedName
import io.schemata.core.ir.RecordType
import io.schemata.core.ir.Ref
import io.schemata.core.ir.Scalar
import io.schemata.core.ir.Schema
import io.schemata.core.ir.TypeDecl
import io.schemata.core.ir.UnionType
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
        val files = schema.namespaces.map { FileLowering(schema, uris, it, diagnostics).lower() }
        return Lowered(XsdModel(files), diagnostics)
    }

    /**
     * One namespace's file. Enums and records with scalar or enum-referencing fields become types;
     * top-level records also get a global element. Imports are added by later work.
     */
    internal class FileLowering(
        private val schema: Schema,
        private val uris: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        /** Who owns each XSD name, keyed `"type:<name>"` / `"element:<name>"`. */
        private val claims = mutableMapOf<String, Pair<String, Span>>()

        /** The validated `@xsd(name)` override for a declaration, checked at most once. */
        private val nameOverrides = mutableMapOf<QualifiedName, String?>()

        fun lower(): XsdFile {
            val types = mutableListOf<XsdType>()
            val elements = mutableListOf<XsdElement>()
            namespace.declarations.forEach { decl ->
                types += types(decl, emptyList())
                if (decl is RecordType) elements += globalElement(decl)
            }
            return XsdFile(
                XsdNames.pathOf(namespace),
                uris.getValue(namespace.name),
                emptyList(),
                types,
                elements,
            )
        }

        /**
         * [path] is the enclosing declarations' names; nested declarations are flattened after
         * their own type. A declaration's own `@xsd(name)` override replaces its segment of the
         * path.
         */
        private fun types(decl: TypeDecl, path: List<String>): List<XsdType> {
            val here = path + (nameOverride(decl) ?: decl.name)
            val own =
                when (decl) {
                    is EnumType -> listOf(enum(decl, here))
                    is RecordType -> listOf(record(decl, here))
                    is UnionType -> emptyList()
                }
            own.singleOrNull()?.let {
                claim("type", it.name, "${kindOf(decl)} '${decl.name}'", decl.nameSpan)
            }
            return own + decl.nested.flatMap { types(it, here) }
        }

        private fun enum(enum: EnumType, path: List<String>): XsdEnumeration =
            XsdEnumeration(
                XsdNames.typeName(path),
                enum.doc,
                enum.values.map {
                    val where = "enum value '${enum.name}.${it.name}'"
                    XsdEnumValue(
                        overrideName(it.annotations, where, it.nameSpan) ?: it.name,
                        it.doc,
                    )
                },
            )

        private fun record(record: RecordType, path: List<String>): XsdComplex =
            XsdComplex(
                XsdNames.typeName(path),
                record.doc,
                record.fields.mapNotNull { field(record, it, path) },
            )

        /** A top-level record's global element, named in lower snake unless overridden. */
        private fun globalElement(record: RecordType): XsdElement {
            val override = nameOverride(record)
            val name = override ?: XsdNames.elementName(record.name)
            val typeName = XsdNames.typeName(listOf(override ?: record.name))
            claim("element", name, "record '${record.name}'", record.nameSpan)
            return XsdElement(name, XsdTypeRef.Named("tns", typeName, simple = false))
        }

        /**
         * Skips fields whose type is not yet lowered (collections, unions, other-namespace refs).
         */
        private fun field(record: RecordType, field: Field, path: List<String>): XsdElement? {
            val where = "field '${record.name}.${field.name}'"
            val type =
                when (val t = field.type) {
                    is Scalar -> scalarRef(t, where, field.span)
                    is Ref -> {
                        val target = schema.lookup(t.target)
                        if (target !is EnumType) return null
                        XsdTypeRef.Named("tns", XsdNames.typeName(t.target.path), simple = true)
                    }
                    else -> return null
                }
            val name = overrideName(field.annotations, where, field.nameSpan) ?: field.name
            claim("element", "${record.name}/$name", where, field.nameSpan)
            return XsdElement(
                name = name,
                type = type,
                minOccurs = if (field.nullable || field.default != null) 0 else 1,
                default = field.default?.let(XsdTypes::text),
                doc = field.doc,
            )
        }

        private fun kindOf(decl: TypeDecl): String =
            when (decl) {
                is RecordType -> "record"
                is EnumType -> "enum"
                is UnionType -> "union"
            }

        /** [decl]'s validated `@xsd(name)`, memoized so an invalid one is reported only once. */
        private fun nameOverride(decl: TypeDecl): String? =
            nameOverrides.getOrPut(decl.qualifiedName) {
                overrideName(decl.annotations, "${kindOf(decl)} '${decl.name}'", decl.nameSpan)
            }

        /** The `@xsd(name)` value, or null (with a diagnostic) when it is not a valid XML name. */
        private fun overrideName(annotations: Annotations, where: String, span: Span): String? {
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

        /** Reports [XsdCodes.NAME_COLLISION] when `"$kind:$name"` was already claimed. */
        private fun claim(kind: String, name: String, holder: String, span: Span) {
            val key = "$kind:$name"
            val previous = claims[key]
            if (previous == null) {
                claims[key] = holder to span
            } else {
                diagnostics +=
                    Diagnostic(
                        XsdCodes.NAME_COLLISION,
                        "$holder lowers to $kind '$name', already used by ${previous.first} " +
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
