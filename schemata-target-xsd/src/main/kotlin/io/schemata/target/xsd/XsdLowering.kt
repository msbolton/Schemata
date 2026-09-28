package io.schemata.target.xsd

import io.schemata.core.ir.Builtin
import io.schemata.core.ir.EnumType
import io.schemata.core.ir.Field
import io.schemata.core.ir.Namespace
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
     * imports and global elements are added by later work.
     */
    internal class FileLowering(
        private val schema: Schema,
        private val uris: Map<String, String>,
        private val namespace: Namespace,
        private val diagnostics: MutableList<Diagnostic>,
    ) {
        fun lower(): XsdFile =
            XsdFile(
                XsdNames.pathOf(namespace),
                uris.getValue(namespace.name),
                emptyList(),
                namespace.declarations.flatMap { types(it, emptyList()) },
                emptyList(),
            )

        /**
         * [path] is the enclosing declarations' names; nested declarations are flattened after
         * their own type.
         */
        private fun types(decl: TypeDecl, path: List<String>): List<XsdType> {
            val here = path + decl.name
            val own =
                when (decl) {
                    is EnumType -> listOf(enum(decl, here))
                    is RecordType -> listOf(record(decl, here))
                    is UnionType -> emptyList()
                }
            return own + decl.nested.flatMap { types(it, here) }
        }

        private fun enum(enum: EnumType, path: List<String>): XsdEnumeration =
            XsdEnumeration(
                XsdNames.typeName(path),
                enum.doc,
                enum.values.map {
                    XsdEnumValue(XsdNames.override(it.annotations, "name") ?: it.name, it.doc)
                },
            )

        private fun record(record: RecordType, path: List<String>): XsdComplex =
            XsdComplex(
                XsdNames.typeName(path),
                record.doc,
                record.fields.mapNotNull { field(record, it, path) },
            )

        /**
         * Skips fields whose type is not yet lowered (collections, unions, other-namespace refs).
         */
        private fun field(record: RecordType, field: Field, path: List<String>): XsdElement? {
            val type =
                when (val t = field.type) {
                    is Scalar -> scalarRef(t, "field '${record.name}.${field.name}'", field.span)
                    is Ref -> {
                        val target = schema.lookup(t.target)
                        if (target !is EnumType) return null
                        XsdTypeRef.Named("tns", XsdNames.typeName(t.target.path), simple = true)
                    }
                    else -> return null
                }
            return XsdElement(
                name = XsdNames.override(field.annotations, "name") ?: field.name,
                type = type,
                minOccurs = if (field.nullable || field.default != null) 0 else 1,
                default = field.default?.let(XsdTypes::text),
                doc = field.doc,
            )
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
