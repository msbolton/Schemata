package io.schemata.target.xsd

import io.schemata.core.ir.Namespace
import io.schemata.core.ir.Schema
import io.schemata.lang.Diagnostic
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
     * One namespace's file; only the namespace itself is lowered so far, so types and elements stay
     * empty.
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
                emptyList(),
                emptyList(),
            )
    }
}
