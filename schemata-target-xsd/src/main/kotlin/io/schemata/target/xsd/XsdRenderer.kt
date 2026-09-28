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
        file.types.forEach { append(type(it, "  ")) }
        file.elements.forEach { append(element(it, "  ")) }
        appendLine("</xs:schema>")
    }

    private fun type(t: XsdType, indent: String): String = buildString {
        when (t) {
            is XsdComplex -> {
                appendLine("$indent<xs:complexType name=\"${t.name}\">")
                doc(t.doc, "$indent  ")
                append(sequence(t.sequence, t.attributes, "$indent  "))
                appendLine("$indent</xs:complexType>")
            }
            is XsdChoice -> {
                appendLine("$indent<xs:complexType name=\"${t.name}\">")
                doc(t.doc, "$indent  ")
                appendLine("$indent  <xs:choice>")
                t.members.forEach { append(element(it, "$indent    ")) }
                appendLine("$indent  </xs:choice>")
                appendLine("$indent</xs:complexType>")
            }
            is XsdEnumeration -> {
                appendLine("$indent<xs:simpleType name=\"${t.name}\">")
                doc(t.doc, "$indent  ")
                appendLine("$indent  <xs:restriction base=\"xs:string\">")
                t.values.forEach { v ->
                    if (v.doc == null)
                        appendLine("$indent    <xs:enumeration value=\"${escape(v.value)}\"/>")
                    else {
                        appendLine("$indent    <xs:enumeration value=\"${escape(v.value)}\">")
                        doc(v.doc, "$indent      ")
                        appendLine("$indent    </xs:enumeration>")
                    }
                }
                appendLine("$indent  </xs:restriction>")
                appendLine("$indent</xs:simpleType>")
            }
        }
    }

    /**
     * `<xs:sequence>…</xs:sequence>` then attributes; an empty sequence still prints an empty
     * `xs:sequence`.
     */
    private fun sequence(
        elements: List<XsdElement>,
        attributes: List<XsdAttribute>,
        indent: String,
    ): String = buildString {
        if (elements.isEmpty()) appendLine("$indent<xs:sequence/>")
        else {
            appendLine("$indent<xs:sequence>")
            elements.forEach { append(element(it, "$indent  ")) }
            appendLine("$indent</xs:sequence>")
        }
        attributes.forEach { append(attribute(it, indent)) }
    }

    private fun element(e: XsdElement, indent: String): String = buildString {
        val attrs = buildString {
            append(" name=\"${escape(e.name)}\"")
            if (e.type is XsdTypeRef.Builtin) append(" type=\"${e.type.xsName}\"")
            if (e.type is XsdTypeRef.Named) append(" type=\"${e.type.prefix}:${e.type.name}\"")
            if (e.minOccurs != 1) append(" minOccurs=\"${e.minOccurs}\"")
            if (e.maxOccurs != 1) append(" maxOccurs=\"${e.maxOccurs ?: "unbounded"}\"")
            if (e.nillable) append(" nillable=\"true\"")
            e.default?.let { append(" default=\"${escape(it)}\"") }
        }
        val inline = e.type !is XsdTypeRef.Builtin && e.type !is XsdTypeRef.Named
        if (e.doc == null && !inline && e.unique == null) {
            appendLine("$indent<xs:element$attrs/>")
            return@buildString
        }
        appendLine("$indent<xs:element$attrs>")
        doc(e.doc, "$indent  ")
        when (val t = e.type) {
            is XsdTypeRef.Restricted -> append(restriction(t, "$indent  "))
            is XsdTypeRef.Anonymous -> {
                appendLine("$indent  <xs:complexType>")
                append(sequence(t.sequence, t.attributes, "$indent    "))
                appendLine("$indent  </xs:complexType>")
            }
            is XsdTypeRef.Extension -> append(extension(t, "$indent  "))
            is XsdTypeRef.Builtin,
            is XsdTypeRef.Named -> Unit
        }
        e.unique?.let {
            appendLine("$indent  <xs:unique name=\"${escape(it)}\">")
            appendLine("$indent    <xs:selector xpath=\"tns:entry\"/>")
            appendLine("$indent    <xs:field xpath=\"@key\"/>")
            appendLine("$indent  </xs:unique>")
        }
        appendLine("$indent</xs:element>")
    }

    private fun attribute(a: XsdAttribute, indent: String): String = buildString {
        val attrs = buildString {
            append(" name=\"${escape(a.name)}\"")
            if (a.type is XsdTypeRef.Builtin) append(" type=\"${a.type.xsName}\"")
            if (a.type is XsdTypeRef.Named) append(" type=\"${a.type.prefix}:${a.type.name}\"")
            if (a.required) append(" use=\"required\"")
            a.default?.let { append(" default=\"${escape(it)}\"") }
        }
        val restricted = a.type as? XsdTypeRef.Restricted
        if (a.doc == null && restricted == null) {
            appendLine("$indent<xs:attribute$attrs/>")
            return@buildString
        }
        appendLine("$indent<xs:attribute$attrs>")
        doc(a.doc, "$indent  ")
        restricted?.let { append(restriction(it, "$indent  ")) }
        appendLine("$indent</xs:attribute>")
    }

    private fun restriction(t: XsdTypeRef.Restricted, indent: String): String = buildString {
        appendLine("$indent<xs:simpleType>")
        appendLine("$indent  <xs:restriction base=\"${t.base}\">")
        t.facets.forEach { appendLine("$indent    <xs:${it.name} value=\"${escape(it.value)}\"/>") }
        appendLine("$indent  </xs:restriction>")
        appendLine("$indent</xs:simpleType>")
    }

    /**
     * simpleContent when the base is a builtin, restricted, or simple named type; complexContent
     * otherwise.
     */
    private fun extension(t: XsdTypeRef.Extension, indent: String): String = buildString {
        val simple =
            t.base is XsdTypeRef.Builtin ||
                t.base is XsdTypeRef.Restricted ||
                (t.base is XsdTypeRef.Named && t.base.simple)
        val content = if (simple) "xs:simpleContent" else "xs:complexContent"
        val base =
            when (val b = t.base) {
                is XsdTypeRef.Builtin -> b.xsName
                is XsdTypeRef.Named -> "${b.prefix}:${b.name}"
                is XsdTypeRef.Restricted -> b.base
                else -> error("an extension base is a builtin or a named type")
            }
        appendLine("$indent<xs:complexType>")
        appendLine("$indent  <$content>")
        appendLine("$indent    <xs:extension base=\"$base\">")
        t.attributes.forEach { append(attribute(it, "$indent      ")) }
        appendLine("$indent    </xs:extension>")
        appendLine("$indent  </$content>")
        appendLine("$indent</xs:complexType>")
    }

    private fun StringBuilder.doc(doc: String?, indent: String) {
        if (doc == null) return
        appendLine("$indent<xs:annotation>")
        appendLine("$indent  <xs:documentation>${escape(doc)}</xs:documentation>")
        appendLine("$indent</xs:annotation>")
    }

    internal fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
