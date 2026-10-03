package io.schemata.importer.xsd

import io.schemata.importer.ImportCodes
import io.schemata.lang.Diagnostic
import io.schemata.lang.DiagnosticCode
import io.schemata.lang.Span
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

data class ReadResult(val doc: XsdDoc?, val diagnostics: List<Diagnostic>)

/** Reads one `.xsd` into an [XsdDoc]; only the XML Schema vocabulary is kept, with line numbers. */
object XsdReader {
    const val XS = "http://www.w3.org/2001/XMLSchema"

    fun read(path: String, text: String): ReadResult {
        val tree =
            try {
                parse(path, text)
            } catch (e: SAXException) {
                val line = (e as? SAXParseException)?.lineNumber ?: 1
                return ReadResult(
                    null,
                    listOf(error(path, line.coerceAtLeast(1), "$path: ${e.message}")),
                )
            } catch (e: IOException) {
                return ReadResult(null, listOf(error(path, 1, "$path: ${e.message}")))
            }
        if (tree.ns != XS || tree.local != "schema") {
            return ReadResult(
                null,
                listOf(error(path, tree.line, "$path: the root element is not xs:schema")),
            )
        }
        val builder = Builder(path, tree)
        val doc = builder.doc()
        return ReadResult(doc, builder.diagnostics)
    }

    private fun error(path: String, line: Int, message: String) =
        Diagnostic(
            ImportCodes.UNRESOLVED,
            message,
            Span(path, line, 1, line, 1),
            help = "give the importer a well-formed XML Schema 1.0 document",
        )

    /** A parsed element: name, attributes, text, children, in-scope prefixes, and its line. */
    internal class Node(
        val ns: String?,
        val local: String,
        val attrs: Map<String, String>,
        val line: Int,
        val prefixes: Map<String, String>,
    ) {
        val children = mutableListOf<Node>()
        val text = StringBuilder()

        /** How much of its parent's [text] had been read when this node began. */
        var offset = 0

        /**
         * All the character data under this node in document order, its own and its descendants',
         * as an XPath string value: markup inside a documentation node keeps its words.
         */
        fun deepText(): String = buildString {
            var at = 0
            children.forEach { c ->
                append(text, at, c.offset)
                append(c.deepText())
                at = c.offset
            }
            append(text, at, text.length)
        }

        fun attr(name: String): String? = attrs[name]

        fun child(local: String): Node? = children.firstOrNull { it.ns == XS && it.local == local }

        fun children(local: String): List<Node> =
            children.filter { it.ns == XS && it.local == local }

        /**
         * `tns:Foo` → QName(uri of tns, Foo); an unprefixed name takes the default namespace, or
         * null when there is none or `xmlns=""` undeclared it.
         */
        fun qname(value: String): QName {
            val i = value.indexOf(':')
            val uri = if (i < 0) prefixes[""] else prefixes[value.substring(0, i)]
            return QName(uri?.ifEmpty { null }, if (i < 0) value else value.substring(i + 1))
        }
    }

    private fun parse(path: String, text: String): Node {
        // A schema never needs a document type declaration, and an external entity would read
        // whatever file or URL it names, so both are refused outright.
        val factory =
            SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        val handler =
            object : DefaultHandler() {
                lateinit var locator: Locator
                val stack = ArrayDeque<Node>()
                var root: Node? = null
                val scope =
                    ArrayDeque<MutableMap<String, String>>().apply { addLast(mutableMapOf()) }
                val pending = mutableMapOf<String, String>()

                override fun setDocumentLocator(l: Locator) {
                    locator = l
                }

                override fun startPrefixMapping(prefix: String, uri: String) {
                    pending[prefix] = uri
                }

                override fun startElement(
                    uri: String,
                    localName: String,
                    qName: String,
                    attributes: Attributes,
                ) {
                    val attrs =
                        (0 until attributes.length).associate {
                            attributes.getLocalName(it) to attributes.getValue(it)
                        }
                    val prefixes = scope.last() + pending
                    pending.clear()
                    val node =
                        Node(uri.ifEmpty { null }, localName, attrs, locator.lineNumber, prefixes)
                    if (stack.isEmpty()) root = node
                    else {
                        node.offset = stack.last().text.length
                        stack.last().children += node
                    }
                    stack.addLast(node)
                    scope.addLast(HashMap(prefixes))
                }

                override fun endElement(uri: String, localName: String, qName: String) {
                    stack.removeLast()
                    scope.removeLast()
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    stack.lastOrNull()?.text?.append(ch, start, length)
                }
            }
        factory.newSAXParser().parse(InputSource(StringReader(text)), handler)
        return handler.root ?: error("no root element in $path")
    }

    internal class Builder(private val path: String, private val root: Node) {
        val diagnostics = mutableListOf<Diagnostic>()

        // the children of a restriction that are not facets
        private val nonFacets = setOf("simpleType", "annotation")

        // the children of a simpleContent derivation that declare attributes rather than facets
        private val attributeChildren = setOf("attribute", "attributeGroup", "anyAttribute")

        fun doc(): XsdDoc =
            XsdDoc(
                path = path,
                targetNamespace = root.attr("targetNamespace"),
                doc = documentation(root),
                imports =
                    root.children("import").map {
                        XImport(it.attr("namespace"), it.attr("schemaLocation"), it.line)
                    },
                // A redefine or override also brings in the document it names; what it changes in
                // that document is dropped (and reported) by the importer.
                includes =
                    root.children
                        .filter {
                            it.ns == XS &&
                                (it.local == "include" ||
                                    it.local == "redefine" ||
                                    it.local == "override")
                        }
                        .mapNotNull { it.attr("schemaLocation") },
                complexTypes =
                    root.children("complexType").mapNotNull { n ->
                        required(n, "name")?.let { complexType(n) }
                    },
                simpleTypes =
                    root.children("simpleType").mapNotNull { n ->
                        required(n, "name")?.let { simpleType(n) }
                    },
                elements = root.children("element").mapNotNull { element(it) },
                attributes =
                    root.children("attribute").mapNotNull { n ->
                        required(n, "name")?.let { attribute(n) }
                    },
                groups =
                    root.children("group").mapNotNull { n ->
                        required(n, "name")?.let { XGroup(it, modelGroup(n), n.line, path) }
                    },
                attributeGroups =
                    root.children("attributeGroup").mapNotNull { n ->
                        required(n, "name")?.let {
                            XAttributeGroup(it, attributeUses(n), n.line, path)
                        }
                    },
                dropped =
                    root.children
                        .filter {
                            it.ns == XS &&
                                (it.local == "redefine" ||
                                    it.local == "override" ||
                                    it.local == "notation")
                        }
                        .map { "xs:${it.local}" to it.line },
                elementFormDefault = root.attr("elementFormDefault"),
                attributeFormDefault = root.attr("attributeFormDefault"),
                blockDefault = root.attr("blockDefault"),
                finalDefault = root.attr("finalDefault"),
            )

        /**
         * [n]'s [attribute], or `null`, reported, when it has none: the construct cannot be read
         * without it, so the caller skips it.
         */
        private fun required(n: Node, attribute: String): String? {
            val value = n.attr(attribute)
            if (value == null) {
                diagnostics +=
                    error(path, n.line, "$path: xs:${n.local} at line ${n.line} has no $attribute")
            }
            return value
        }

        private fun warning(code: DiagnosticCode, line: Int, message: String) {
            diagnostics +=
                Diagnostic(code, message, Span(path, line, 1, line, 1), ImportCodes.helpFor(code))
        }

        /**
         * The text of every `xs:documentation` under this node's `xs:annotation`, trimmed and
         * joined by blank lines; markup inside one (XHTML, say) gives up its text in place. Each
         * line loses its leading whitespace: a schema indents its documentation to suit its own
         * layout, which means nothing once it is a doc comment.
         */
        private fun documentation(n: Node): String? =
            n.child("annotation")
                ?.children("documentation")
                ?.map { d -> d.deepText().trim().lines().joinToString("\n") { it.trimStart() } }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString("\n\n")

        private fun complexType(n: Node): XComplexType {
            val attrs = mutableListOf<XAttributeUse>()
            val content: XContent =
                when {
                    n.child("complexContent") != null ->
                        derived(n.child("complexContent")!!, simple = false, attrs)
                    n.child("simpleContent") != null ->
                        derived(n.child("simpleContent")!!, simple = true, attrs)
                    else -> {
                        attrs += attributeUses(n)
                        modelGroup(n)
                    }
                }
            return XComplexType(
                n.attr("name"),
                documentation(n),
                content,
                attrs,
                // mixed content may be declared on the type or on its complexContent
                n.attr("mixed") == "true" || n.child("complexContent")?.attr("mixed") == "true",
                n.attr("abstract") == "true",
                n.line,
                path = path,
                block = n.attr("block"),
                final = n.attr("final"),
            )
        }

        private fun derived(
            content: Node,
            simple: Boolean,
            attrs: MutableList<XAttributeUse>,
        ): XContent {
            val ext = content.child("extension")
            val res = content.child("restriction")
            val d = ext ?: res ?: return XContent.Empty
            val base = required(d, "base") ?: return XContent.Empty
            attrs += attributeUses(d)
            val group = modelGroup(d)
            val particles =
                when (group) {
                    is XContent.Sequence -> group.particles
                    XContent.Empty -> emptyList()
                    else -> listOf(XParticle.Nested(group, 1, 1, d.line))
                }
            val facets = if (simple) facets(d, nonFacets + attributeChildren) else emptyList()
            return if (ext != null)
                XContent.Extension(d.qname(base), particles, simple, d.line, facets)
            else XContent.Restriction(d.qname(base), particles, simple, d.line, facets)
        }

        /**
         * The one model group under [n] (`sequence`, `choice`, `all`, or a `group` ref), or Empty.
         */
        private fun modelGroup(n: Node): XContent {
            n.child("sequence")?.let {
                return XContent.Sequence(particles(it))
            }
            n.child("choice")?.let {
                return XContent.Choice(particles(it), occurs(it).first, occurs(it).second)
            }
            n.child("all")?.let {
                return XContent.All(particles(it))
            }
            n.child("group")?.let { g ->
                val ref = groupRef(g) ?: return XContent.Empty
                return XContent.Sequence(listOf(ref))
            }
            return XContent.Empty
        }

        private fun particles(group: Node): List<XParticle> =
            group.children
                .filter { it.ns == XS }
                .mapNotNull { c ->
                    when (c.local) {
                        "element" -> element(c)?.let { XParticle.Element(it) }
                        "any" -> {
                            val (min, max) = occurs(c)
                            XParticle.Any(
                                c.line,
                                min,
                                max,
                                c.attr("namespace"),
                                c.attr("processContents"),
                            )
                        }
                        "group" -> groupRef(c)
                        "sequence",
                        "choice",
                        "all" ->
                            XParticle.Nested(
                                modelGroupOf(c),
                                occurs(c).first,
                                occurs(c).second,
                                c.line,
                            )
                        else -> null
                    }
                }

        private fun modelGroupOf(c: Node): XContent =
            when (c.local) {
                "sequence" -> XContent.Sequence(particles(c))
                "choice" -> XContent.Choice(particles(c), occurs(c).first, occurs(c).second)
                else -> XContent.All(particles(c))
            }

        private fun groupRef(g: Node): XParticle.GroupRef? {
            val ref = required(g, "ref") ?: return null
            val (min, max) = occurs(g)
            return XParticle.GroupRef(g.qname(ref), min, max, g.line)
        }

        /**
         * [n]'s `minOccurs` and `maxOccurs` (`null` for `unbounded`); a value that is not a
         * non-negative integer is reported and read as the default, `1`.
         */
        private fun occurs(n: Node): Pair<Int, Int?> {
            val min = n.attr("minOccurs")?.let { occurrence(n, "minOccurs", it) } ?: 1
            val maxAttr = n.attr("maxOccurs")
            val max =
                if (maxAttr == "unbounded") null
                else maxAttr?.let { occurrence(n, "maxOccurs", it) } ?: 1
            return min to max
        }

        private fun occurrence(n: Node, attribute: String, value: String): Int? {
            val parsed = value.trim().toIntOrNull()?.takeIf { it >= 0 }
            if (parsed == null) {
                warning(
                    ImportCodes.WIDENED,
                    n.line,
                    "$path: facet $attribute value '$value' dropped",
                )
            }
            return parsed
        }

        /**
         * An element declaration or reference; `null`, reported, when it has neither a name nor a
         * ref. One with both a `type` and an inline type keeps the `type`, as a validator would.
         */
        private fun element(n: Node): XElement? {
            val name = n.attr("name")
            val ref = n.attr("ref")?.let(n::qname)
            if (name == null && ref == null) {
                required(n, "name")
                return null
            }
            val type = n.attr("type")?.let(n::qname)
            val inline = n.child("complexType") ?: n.child("simpleType")
            if (type != null && inline != null) {
                warning(
                    ImportCodes.APPROXIMATED,
                    n.line,
                    "element '$name': inline type ignored in favour of type '${type.local}'",
                )
            }
            val (min, max) = occurs(n)
            return XElement(
                name = name,
                ref = ref,
                type = type,
                inlineComplex =
                    n.child("complexType")?.takeIf { type == null }?.let { complexType(it) },
                inlineSimple =
                    n.child("simpleType")?.takeIf { type == null }?.let { simpleType(it) },
                minOccurs = min,
                maxOccurs = max,
                nillable = n.attr("nillable") == "true",
                default = n.attr("default"),
                fixed = n.attr("fixed"),
                substitutionGroup = n.attr("substitutionGroup")?.let(n::qname),
                abstract = n.attr("abstract") == "true",
                doc = documentation(n),
                uniques =
                    n.children("unique").mapNotNull { u ->
                        required(u, "name")?.let { uniqueName ->
                            XUnique(
                                uniqueName,
                                u.child("selector")?.attr("xpath") ?: "",
                                u.children("field").mapNotNull { it.attr("xpath") },
                                u.line,
                            )
                        }
                    },
                keys =
                    n.children("key").map { XIdentityConstraint(it.attr("name") ?: "", it.line) } +
                        n.children("keyref").map {
                            XIdentityConstraint(it.attr("name") ?: "", it.line)
                        },
                line = n.line,
                path = path,
                form = n.attr("form"),
                block = n.attr("block"),
                final = n.attr("final"),
            )
        }

        private fun attributeUses(n: Node): List<XAttributeUse> =
            n.children
                .filter { it.ns == XS }
                .mapNotNull { c ->
                    when (c.local) {
                        "attribute" -> XAttributeUse.Attribute(attribute(c))
                        "attributeGroup" ->
                            required(c, "ref")?.let { XAttributeUse.GroupRef(c.qname(it), c.line) }
                        "anyAttribute" ->
                            XAttributeUse.AnyAttribute(
                                c.line,
                                c.attr("namespace"),
                                c.attr("processContents"),
                            )
                        else -> null
                    }
                }

        private fun attribute(n: Node): XAttribute =
            XAttribute(
                n.attr("name"),
                n.attr("ref")?.let(n::qname),
                n.attr("type")?.let(n::qname),
                n.child("simpleType")?.let { simpleType(it) },
                n.attr("use") ?: "optional",
                n.attr("default"),
                n.attr("fixed"),
                documentation(n),
                n.line,
                form = n.attr("form"),
            )

        /**
         * The facets under a restriction (or a simpleContent derivation) [d]: every XML Schema
         * child except those named in [skip].
         */
        private fun facets(d: Node, skip: Set<String>): List<XFacet> =
            d.children
                .filter { it.ns == XS && it.local !in skip }
                .map { f -> XFacet(f.local, f.attr("value") ?: "", documentation(f), f.line) }

        private fun simpleType(n: Node): XSimpleType {
            val variety: XVariety =
                n.child("restriction")?.let { r ->
                    XVariety.Restriction(
                        r.attr("base")?.let(r::qname),
                        r.child("simpleType")?.let { simpleType(it) },
                        facets(r, nonFacets),
                    )
                }
                    ?: n.child("list")?.let {
                        XVariety.ListOf(
                            it.attr("itemType")?.let(it::qname),
                            it.child("simpleType")?.let { s -> simpleType(s) },
                        )
                    }
                    ?: n.child("union")?.let { u ->
                        XVariety.Union(
                            (u.attr("memberTypes") ?: "")
                                .split(' ')
                                .filter { it.isNotEmpty() }
                                .map(u::qname),
                            u.children("simpleType").map { simpleType(it) },
                        )
                    }
                    ?: XVariety.Restriction(null, null, emptyList())
            return XSimpleType(n.attr("name"), documentation(n), variety, n.line, path)
        }
    }
}
