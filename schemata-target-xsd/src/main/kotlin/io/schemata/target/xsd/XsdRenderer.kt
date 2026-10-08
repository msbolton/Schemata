package io.schemata.target.xsd

import io.schemata.target.OutputFile

/** Prints an [XsdModel]. No decisions are made here. */
object XsdRenderer {
    fun render(model: XsdModel): List<OutputFile> =
        model.files.map { OutputFile(it.path, text(it)) }

    internal fun text(file: XsdFile): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"")
        append("\n           xmlns:tns=\"${escapeAttribute(file.targetNamespace)}\"")
        file.imports.forEach {
            append("\n           xmlns:${it.prefix}=\"${escapeAttribute(it.namespace)}\"")
        }
        append("\n           targetNamespace=\"${escapeAttribute(file.targetNamespace)}\"")
        append("\n           elementFormDefault=\"${file.elementFormDefault}\"")
        appendLine("\n           attributeFormDefault=\"${file.attributeFormDefault}\">")
        file.imports.forEach {
            appendLine(
                "  <xs:import namespace=\"${escapeAttribute(it.namespace)}\" schemaLocation=\"${escapeAttribute(it.schemaLocation)}\"/>"
            )
        }
        file.types.forEach { append(type(it, "  ")) }
        file.elements.forEach { append(element(it, "  ")) }
        appendLine("</xs:schema>")
    }

    private fun type(t: XsdType, indent: String): String = buildString {
        when (t) {
            is XsdComplex -> {
                val mixed = if (t.mixed) " mixed=\"true\"" else ""
                appendLine("$indent<xs:complexType name=\"${t.name}\"$mixed>")
                doc(t.doc, "$indent  ")
                append(sequence(t.sequence, t.attributes, t.anyAttribute, "$indent  ", t.all))
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
                        appendLine(
                            "$indent    <xs:enumeration value=\"${escapeAttribute(v.value)}\"/>"
                        )
                    else {
                        appendLine(
                            "$indent    <xs:enumeration value=\"${escapeAttribute(v.value)}\">"
                        )
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
     * `<xs:sequence>…</xs:sequence>` (`xs:all` when [all]), then attributes, then the attribute
     * wildcard; an empty group still prints as an empty element.
     */
    private fun sequence(
        particles: List<XsdParticle>,
        attributes: List<XsdAttribute>,
        anyAttribute: XsdAnyAttribute?,
        indent: String,
        all: Boolean = false,
    ): String = buildString {
        val group = if (all) "xs:all" else "xs:sequence"
        if (particles.isEmpty()) appendLine("$indent<$group/>")
        else {
            appendLine("$indent<$group>")
            particles.forEach {
                when (it) {
                    is XsdElement -> append(element(it, "$indent  "))
                    is XsdAny -> append(any(it, "$indent  "))
                }
            }
            appendLine("$indent</$group>")
        }
        attributes.forEach { append(attribute(it, indent)) }
        anyAttribute?.let { a ->
            val namespace = a.namespace?.let { " namespace=\"${escapeAttribute(it)}\"" } ?: ""
            appendLine(
                "$indent<xs:anyAttribute$namespace processContents=\"${a.processContents}\"/>"
            )
        }
    }

    /** `xs:any`, its occurrence bounds printed only when they are not XSD's default of 1. */
    private fun any(a: XsdAny, indent: String): String = buildString {
        append("$indent<xs:any")
        if (a.minOccurs != 1) append(" minOccurs=\"${a.minOccurs}\"")
        if (a.maxOccurs != 1) append(" maxOccurs=\"${a.maxOccurs ?: "unbounded"}\"")
        a.namespace?.let { append(" namespace=\"${escapeAttribute(it)}\"") }
        appendLine(" processContents=\"${a.processContents}\"/>")
    }

    private fun element(e: XsdElement, indent: String): String = buildString {
        val attrs = buildString {
            append(" name=\"${escapeAttribute(e.name)}\"")
            append(typeAttr(e.type))
            if (e.minOccurs != 1) append(" minOccurs=\"${e.minOccurs}\"")
            if (e.maxOccurs != 1) append(" maxOccurs=\"${e.maxOccurs ?: "unbounded"}\"")
            if (e.nillable) append(" nillable=\"true\"")
            e.default?.let { append(" default=\"${escapeAttribute(it)}\"") }
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
                append(sequence(t.sequence, t.attributes, null, "$indent    "))
                appendLine("$indent  </xs:complexType>")
            }
            is XsdTypeRef.Extension -> append(extension(t, "$indent  "))
            is XsdTypeRef.ListOf -> append(list(t, "$indent  "))
            is XsdTypeRef.Builtin,
            is XsdTypeRef.Named -> Unit
        }
        e.unique?.let {
            appendLine("$indent  <xs:unique name=\"${escapeAttribute(it)}\">")
            appendLine("$indent    <xs:selector xpath=\"tns:entry\"/>")
            appendLine("$indent    <xs:field xpath=\"@key\"/>")
            appendLine("$indent  </xs:unique>")
        }
        appendLine("$indent</xs:element>")
    }

    private fun attribute(a: XsdAttribute, indent: String): String = buildString {
        val attrs = buildString {
            append(" name=\"${escapeAttribute(a.name)}\"")
            append(typeAttr(a.type))
            if (a.required) append(" use=\"required\"")
            a.default?.let { append(" default=\"${escapeAttribute(it)}\"") }
        }
        val inline = a.type is XsdTypeRef.Restricted || a.type is XsdTypeRef.ListOf
        if (a.doc == null && !inline) {
            appendLine("$indent<xs:attribute$attrs/>")
            return@buildString
        }
        appendLine("$indent<xs:attribute$attrs>")
        doc(a.doc, "$indent  ")
        when (val t = a.type) {
            is XsdTypeRef.Restricted -> append(restriction(t, "$indent  "))
            is XsdTypeRef.ListOf -> append(list(t, "$indent  "))
            else -> Unit
        }
        appendLine("$indent</xs:attribute>")
    }

    /** ` type="…"` for a builtin or named type; empty for an inline one. */
    private fun typeAttr(ref: XsdTypeRef): String =
        qualifiedName(ref)?.let { " type=\"$it\"" } ?: ""

    private fun restriction(t: XsdTypeRef.Restricted, indent: String): String = buildString {
        appendLine("$indent<xs:simpleType>")
        appendLine("$indent  <xs:restriction base=\"${t.base}\">")
        t.facets.forEach {
            appendLine("$indent    <xs:${it.name} value=\"${escapeAttribute(it.value)}\"/>")
        }
        appendLine("$indent  </xs:restriction>")
        appendLine("$indent</xs:simpleType>")
    }

    /**
     * An anonymous list type: `itemType` names a builtin or named item, a restricted item is
     * declared inline, and a length bound wraps the list in a restriction of it.
     */
    private fun list(t: XsdTypeRef.ListOf, indent: String): String = buildString {
        val bounded = t.minLength != null || t.maxLength != null
        val inner = if (bounded) "$indent    " else indent
        appendLine("$indent<xs:simpleType>")
        if (bounded) {
            appendLine("$indent  <xs:restriction>")
            appendLine("$inner<xs:simpleType>")
        }
        when (val item = t.item) {
            is XsdTypeRef.Restricted -> {
                appendLine("$inner  <xs:list>")
                append(restriction(item, "$inner    "))
                appendLine("$inner  </xs:list>")
            }
            else -> appendLine("$inner  <xs:list itemType=\"${typeName(item)}\"/>")
        }
        if (bounded) {
            appendLine("$inner</xs:simpleType>")
            t.minLength?.let { appendLine("$indent    <xs:minLength value=\"$it\"/>") }
            t.maxLength?.let { appendLine("$indent    <xs:maxLength value=\"$it\"/>") }
            appendLine("$indent  </xs:restriction>")
        }
        appendLine("$indent</xs:simpleType>")
    }

    /** A builtin's or named type's qualified name; null for an inline type, which has none. */
    private fun qualifiedName(ref: XsdTypeRef): String? =
        when (ref) {
            is XsdTypeRef.Builtin -> ref.xsName
            is XsdTypeRef.Named -> "${ref.prefix}:${ref.name}"
            else -> null
        }

    private fun typeName(ref: XsdTypeRef): String =
        qualifiedName(ref) ?: error("only a builtin or a named type has a name")

    /** simpleContent when the base is a builtin or simple named type; complexContent otherwise. */
    private fun extension(t: XsdTypeRef.Extension, indent: String): String = buildString {
        val simple = t.base is XsdTypeRef.Builtin || (t.base is XsdTypeRef.Named && t.base.simple)
        val content = if (simple) "xs:simpleContent" else "xs:complexContent"
        val base = typeName(t.base)
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

    /** Text for element content, where line breaks and tabs are kept as they are. */
    internal fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Text for an attribute value. A parser normalises a literal tab, newline, or carriage return
     * in an attribute to a space, so each is written as a character reference to keep its meaning.
     */
    internal fun escapeAttribute(s: String): String =
        escape(s).replace("\t", "&#9;").replace("\n", "&#10;").replace("\r", "&#13;")
}
