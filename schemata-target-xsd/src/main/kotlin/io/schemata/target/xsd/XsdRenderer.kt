package io.schemata.target.xsd

import io.schemata.target.OutputFile

/** Prints an [XsdModel]. No decisions are made here. */
object XsdRenderer {
    fun render(model: XsdModel): List<OutputFile> =
        model.files.map { OutputFile(it.path, text(it)) }

    internal fun text(file: XsdFile): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"")
        append("\n           xmlns:tns=\"${escape(file.targetNamespace)}\"")
        file.imports.forEach {
            append("\n           xmlns:${it.prefix}=\"${escape(it.namespace)}\"")
        }
        append("\n           targetNamespace=\"${escape(file.targetNamespace)}\"")
        append("\n           elementFormDefault=\"qualified\"")
        appendLine("\n           attributeFormDefault=\"unqualified\">")
        file.imports.forEach {
            appendLine(
                "  <xs:import namespace=\"${escape(it.namespace)}\" schemaLocation=\"${escape(it.schemaLocation)}\"/>"
            )
        }
        appendLine("</xs:schema>")
    }

    internal fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
