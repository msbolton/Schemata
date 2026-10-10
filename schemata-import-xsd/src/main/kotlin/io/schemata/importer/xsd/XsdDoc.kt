package io.schemata.importer.xsd

/** A resolved XML name: [namespace] is the resolved URI, or null for an unqualified name. */
data class QName(val namespace: String?, val local: String)

/**
 * One `.xsd` file's top-level declarations: its target namespace, its documentation, the other
 * schemas it imports or includes, and every named type, element, attribute, group, and attribute
 * group it declares.
 */
data class XsdDoc(
    val path: String,
    val targetNamespace: String?,
    val doc: String?,
    val imports: List<XImport>,
    val includes: List<String>,
    val complexTypes: List<XComplexType>,
    val simpleTypes: List<XSimpleType>,
    val elements: List<XElement>,
    val attributes: List<XAttribute>,
    val groups: List<XGroup>,
    val attributeGroups: List<XAttributeGroup>,
    // a schema-level xs:redefine, xs:override, or xs:notation: its construct name and line, kept so
    // the importer can report each as dropped
    val dropped: List<Pair<String, Int>> = emptyList(),
    // the schema-wide defaults for an element's and an attribute's form, and for a type's or an
    // element's block and final, as written
    val elementFormDefault: String? = null,
    val attributeFormDefault: String? = null,
    val blockDefault: String? = null,
    val finalDefault: String? = null,
    // the namespaces of imports no document read declares (or "(no namespace)"), kept so an
    // unresolved type can say which imports were missing
    val unresolvedImports: List<String> = emptyList(),
)

data class XImport(val namespace: String?, val schemaLocation: String?, val line: Int)

data class XComplexType(
    val name: String?,
    val doc: String?,
    val content: XContent,
    val attributes: List<XAttributeUse>,
    val mixed: Boolean,
    val abstract: Boolean,
    val line: Int,
    // the file that declares it, which an include can make differ from the merged document's
    val path: String = "",
    val block: String? = null,
    val final: String? = null,
)

sealed interface XContent {
    data class Sequence(val particles: List<XParticle>) : XContent

    data class Choice(val particles: List<XParticle>, val minOccurs: Int, val maxOccurs: Int?) :
        XContent

    data class All(val particles: List<XParticle>) : XContent

    // complexContent or simpleContent extension; attributes live on the type
    data class Extension(
        val base: QName,
        val particles: List<XParticle>,
        val simple: Boolean,
        val line: Int,
        val facets: List<XFacet> = emptyList(),
    ) : XContent

    // complexContent or simpleContent restriction; attributes live on the type; a simpleContent
    // restriction's facets narrow the base's value
    data class Restriction(
        val base: QName,
        val particles: List<XParticle>,
        val simple: Boolean,
        val line: Int,
        val facets: List<XFacet> = emptyList(),
    ) : XContent

    data object Empty : XContent
}

sealed interface XParticle {
    data class Element(val element: XElement) : XParticle

    // an element wildcard: [namespace] and [processContents] as written, or null when absent
    data class Any(
        val line: Int,
        val minOccurs: Int = 1,
        val maxOccurs: Int? = 1,
        val namespace: String? = null,
        val processContents: String? = null,
    ) : XParticle

    data class GroupRef(val ref: QName, val minOccurs: Int, val maxOccurs: Int?, val line: Int) :
        XParticle

    // a sequence/choice/all inside another; [name] is the group's when it came from a repeated
    // reference to a named group, and [path] the file of that group, whose lines its own
    // diagnostics point at (empty when it was written in place)
    data class Nested(
        val content: XContent,
        val minOccurs: Int,
        val maxOccurs: Int?,
        val line: Int,
        val name: String? = null,
        val path: String = "",
    ) : XParticle
}

data class XElement(
    val name: String?,
    val ref: QName?,
    val type: QName?,
    val inlineComplex: XComplexType?,
    val inlineSimple: XSimpleType?,
    val minOccurs: Int,
    val maxOccurs: Int?,
    val nillable: Boolean,
    val default: String?,
    val fixed: String?,
    val substitutionGroup: QName?,
    val abstract: Boolean,
    val doc: String?,
    val uniques: List<XUnique>,
    val keys: List<XIdentityConstraint>,
    val line: Int,
    // the file that declares it, which an include can make differ from the merged document's
    val path: String = "",
    val form: String? = null,
    val block: String? = null,
    val final: String? = null,
)

data class XUnique(val name: String, val selector: String, val fields: List<String>, val line: Int)

/** An `xs:key` or `xs:keyref`, kept only so the importer can report it as dropped. */
data class XIdentityConstraint(val name: String, val line: Int)

sealed interface XAttributeUse {
    data class Attribute(val attribute: XAttribute) : XAttributeUse

    data class GroupRef(val ref: QName, val line: Int) : XAttributeUse

    // an attribute wildcard: [namespace] and [processContents] as written, or null when absent
    data class AnyAttribute(
        val line: Int,
        val namespace: String? = null,
        val processContents: String? = null,
    ) : XAttributeUse
}

data class XAttribute(
    val name: String?,
    val ref: QName?,
    val type: QName?,
    val inlineSimple: XSimpleType?,
    val use: String,
    val default: String?,
    val fixed: String?,
    val doc: String?,
    val line: Int,
    val form: String? = null,
    // the file that declares it, which an include can make differ from the merged document's
    val path: String = "",
)

data class XSimpleType(
    val name: String?,
    val doc: String?,
    val variety: XVariety,
    val line: Int,
    // the file that declares it, which an include can make differ from the merged document's
    val path: String = "",
)

sealed interface XVariety {
    data class Restriction(
        val base: QName?,
        val inlineBase: XSimpleType?,
        val facets: List<XFacet>,
    ) : XVariety

    // the item type is named by [itemType] or declared inline as [inlineItem]
    data class ListOf(val itemType: QName?, val inlineItem: XSimpleType? = null) : XVariety

    // the members named in `memberTypes`, then those declared inline
    data class Union(
        val memberTypes: List<QName>,
        val inlineMembers: List<XSimpleType> = emptyList(),
    ) : XVariety
}

// doc for enumeration values
data class XFacet(val name: String, val value: String, val doc: String?, val line: Int)

data class XGroup(val name: String, val content: XContent, val line: Int, val path: String = "")

data class XAttributeGroup(
    val name: String,
    val attributes: List<XAttributeUse>,
    val line: Int,
    val path: String = "",
)

/**
 * The document with every unqualified reference bound to [namespace] and that as its target
 * namespace: a chameleon include (a document with no `targetNamespace`) takes on the namespace of
 * whatever includes it, references and all.
 */
fun XsdDoc.rebased(namespace: String): XsdDoc = Rebase(namespace).doc(this)

private class Rebase(private val namespace: String) {
    fun doc(d: XsdDoc): XsdDoc =
        d.copy(
            targetNamespace = namespace,
            complexTypes = d.complexTypes.map(::complex),
            simpleTypes = d.simpleTypes.map(::simple),
            elements = d.elements.map(::element),
            attributes = d.attributes.map(::attribute),
            groups = d.groups.map { it.copy(content = content(it.content)) },
            attributeGroups = d.attributeGroups.map { it.copy(attributes = uses(it.attributes)) },
        )

    private fun q(n: QName?): QName? = n?.let(::qq)

    private fun qq(n: QName): QName = if (n.namespace == null) QName(namespace, n.local) else n

    private fun simple(st: XSimpleType): XSimpleType =
        st.copy(
            variety =
                when (val v = st.variety) {
                    is XVariety.Restriction ->
                        v.copy(base = q(v.base), inlineBase = v.inlineBase?.let(::simple))
                    is XVariety.ListOf ->
                        v.copy(itemType = q(v.itemType), inlineItem = v.inlineItem?.let(::simple))
                    is XVariety.Union ->
                        v.copy(
                            memberTypes = v.memberTypes.map(::qq),
                            inlineMembers = v.inlineMembers.map(::simple),
                        )
                }
        )

    private fun attribute(a: XAttribute): XAttribute =
        a.copy(ref = q(a.ref), type = q(a.type), inlineSimple = a.inlineSimple?.let(::simple))

    private fun uses(us: List<XAttributeUse>): List<XAttributeUse> =
        us.map {
            when (it) {
                is XAttributeUse.Attribute -> it.copy(attribute = attribute(it.attribute))
                is XAttributeUse.GroupRef -> it.copy(ref = qq(it.ref))
                is XAttributeUse.AnyAttribute -> it
            }
        }

    private fun content(c: XContent): XContent =
        when (c) {
            is XContent.Sequence -> c.copy(particles = particles(c.particles))
            is XContent.Choice -> c.copy(particles = particles(c.particles))
            is XContent.All -> c.copy(particles = particles(c.particles))
            is XContent.Extension -> c.copy(base = qq(c.base), particles = particles(c.particles))
            is XContent.Restriction -> c.copy(base = qq(c.base), particles = particles(c.particles))
            XContent.Empty -> c
        }

    private fun element(e: XElement): XElement =
        e.copy(
            ref = q(e.ref),
            type = q(e.type),
            inlineComplex = e.inlineComplex?.let(::complex),
            inlineSimple = e.inlineSimple?.let(::simple),
            substitutionGroup = q(e.substitutionGroup),
        )

    private fun particles(ps: List<XParticle>): List<XParticle> =
        ps.map {
            when (it) {
                is XParticle.Element -> it.copy(element = element(it.element))
                is XParticle.GroupRef -> it.copy(ref = qq(it.ref))
                is XParticle.Nested -> it.copy(content = content(it.content))
                is XParticle.Any -> it
            }
        }

    private fun complex(ct: XComplexType): XComplexType =
        ct.copy(content = content(ct.content), attributes = uses(ct.attributes))
}
