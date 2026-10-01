package io.schemata.importer.xsd

import io.schemata.lang.Diagnostic
import io.schemata.lang.Span
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
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
            } catch (e: SAXParseException) {
                return ReadResult(
                    null,
                    listOf(error(path, e.lineNumber.coerceAtLeast(1), "$path: ${e.message}")),
                )
            }
        if (tree.ns != XS || tree.local != "schema") {
            return ReadResult(
                null,
                listOf(error(path, tree.line, "$path: the root element is not xs:schema")),
            )
        }
        return ReadResult(Builder(path, tree).doc(), emptyList())
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
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
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
                    if (stack.isEmpty()) root = node else stack.last().children += node
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
        fun doc(): XsdDoc =
            XsdDoc(
                path = path,
                targetNamespace = root.attr("targetNamespace"),
                doc = documentation(root),
                imports =
                    root.children("import").map {
                        XImport(it.attr("namespace"), it.attr("schemaLocation"), it.line)
                    },
                includes = root.children("include").mapNotNull { it.attr("schemaLocation") },
                complexTypes = root.children("complexType").map { complexType(it) },
                simpleTypes = root.children("simpleType").map { simpleType(it) },
                elements = root.children("element").map { element(it) },
                attributes = root.children("attribute").map { attribute(it) },
                groups =
                    root.children("group").map {
                        XGroup(it.attr("name")!!, modelGroup(it), it.line)
                    },
                attributeGroups =
                    root.children("attributeGroup").map {
                        XAttributeGroup(it.attr("name")!!, attributeUses(it), it.line)
                    },
            )

        /**
         * The text of every `xs:documentation` under this node's `xs:annotation`, trimmed and
         * joined by blank lines.
         */
        private fun documentation(n: Node): String? =
            n.child("annotation")
                ?.children("documentation")
                ?.map { it.text.toString().trim() }
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
                n.attr("mixed") == "true",
                n.attr("abstract") == "true",
                n.line,
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
            attrs += attributeUses(d)
            val group = modelGroup(d)
            val particles =
                when (group) {
                    is XContent.Sequence -> group.particles
                    XContent.Empty -> emptyList()
                    else -> listOf(XParticle.Nested(group, 1, 1, d.line))
                }
            return if (ext != null)
                XContent.Extension(d.qname(d.attr("base")!!), particles, simple, d.line)
            else XContent.Restriction(d.qname(d.attr("base")!!), particles, d.line)
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
                return XContent.Sequence(
                    listOf(
                        XParticle.GroupRef(
                            g.qname(g.attr("ref")!!),
                            occurs(g).first,
                            occurs(g).second,
                            g.line,
                        )
                    )
                )
            }
            return XContent.Empty
        }

        private fun particles(group: Node): List<XParticle> =
            group.children
                .filter { it.ns == XS }
                .mapNotNull { c ->
                    when (c.local) {
                        "element" -> XParticle.Element(element(c))
                        "any" -> XParticle.Any(c.line)
                        "group" ->
                            XParticle.GroupRef(
                                c.qname(c.attr("ref")!!),
                                occurs(c).first,
                                occurs(c).second,
                                c.line,
                            )
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

        private fun occurs(n: Node): Pair<Int, Int?> {
            val min = n.attr("minOccurs")?.toInt() ?: 1
            val maxAttr = n.attr("maxOccurs")
            val max =
                if (maxAttr == null) 1 else if (maxAttr == "unbounded") null else maxAttr.toInt()
            return min to max
        }

        private fun element(n: Node): XElement {
            val (min, max) = occurs(n)
            return XElement(
                name = n.attr("name"),
                ref = n.attr("ref")?.let(n::qname),
                type = n.attr("type")?.let(n::qname),
                inlineComplex = n.child("complexType")?.let { complexType(it) },
                inlineSimple = n.child("simpleType")?.let { simpleType(it) },
                minOccurs = min,
                maxOccurs = max,
                nillable = n.attr("nillable") == "true",
                default = n.attr("default"),
                fixed = n.attr("fixed"),
                substitutionGroup = n.attr("substitutionGroup")?.let(n::qname),
                abstract = n.attr("abstract") == "true",
                doc = documentation(n),
                uniques =
                    n.children("unique").map { u ->
                        XUnique(
                            u.attr("name")!!,
                            u.child("selector")?.attr("xpath") ?: "",
                            u.children("field").mapNotNull { it.attr("xpath") },
                            u.line,
                        )
                    },
                keys = n.children("key").size + n.children("keyref").size,
                line = n.line,
            )
        }

        private fun attributeUses(n: Node): List<XAttributeUse> =
            n.children
                .filter { it.ns == XS }
                .mapNotNull { c ->
                    when (c.local) {
                        "attribute" -> XAttributeUse.Attribute(attribute(c))
                        "attributeGroup" -> XAttributeUse.GroupRef(c.qname(c.attr("ref")!!), c.line)
                        "anyAttribute" -> XAttributeUse.AnyAttribute(c.line)
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
            )

        private fun simpleType(n: Node): XSimpleType {
            val variety: XVariety =
                n.child("restriction")?.let { r ->
                    XVariety.Restriction(
                        r.attr("base")?.let(r::qname),
                        r.child("simpleType")?.let { simpleType(it) },
                        r.children
                            .filter {
                                it.ns == XS && it.local != "simpleType" && it.local != "annotation"
                            }
                            .map { f ->
                                XFacet(f.local, f.attr("value") ?: "", documentation(f), f.line)
                            },
                    )
                }
                    ?: n.child("list")?.let { XVariety.ListOf(it.attr("itemType")?.let(it::qname)) }
                    ?: n.child("union")?.let { u ->
                        XVariety.Union(
                            (u.attr("memberTypes") ?: "")
                                .split(' ')
                                .filter { it.isNotEmpty() }
                                .map(u::qname)
                        )
                    }
                    ?: XVariety.Restriction(null, null, emptyList())
            return XSimpleType(n.attr("name"), documentation(n), variety, n.line)
        }
    }
}
