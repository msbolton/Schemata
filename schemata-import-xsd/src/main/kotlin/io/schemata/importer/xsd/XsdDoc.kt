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
    ) : XContent

    data class Restriction(val base: QName, val particles: List<XParticle>, val line: Int) :
        XContent

    data object Empty : XContent
}

sealed interface XParticle {
    data class Element(val element: XElement) : XParticle

    data class Any(val line: Int) : XParticle

    data class GroupRef(val ref: QName, val minOccurs: Int, val maxOccurs: Int?, val line: Int) :
        XParticle

    // a sequence/choice/all inside another
    data class Nested(
        val content: XContent,
        val minOccurs: Int,
        val maxOccurs: Int?,
        val line: Int,
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
    val keys: Int,
    val line: Int,
)

data class XUnique(val name: String, val selector: String, val fields: List<String>, val line: Int)

sealed interface XAttributeUse {
    data class Attribute(val attribute: XAttribute) : XAttributeUse

    data class GroupRef(val ref: QName, val line: Int) : XAttributeUse

    data class AnyAttribute(val line: Int) : XAttributeUse
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
)

data class XSimpleType(val name: String?, val doc: String?, val variety: XVariety, val line: Int)

sealed interface XVariety {
    data class Restriction(
        val base: QName?,
        val inlineBase: XSimpleType?,
        val facets: List<XFacet>,
    ) : XVariety

    data class ListOf(val itemType: QName?) : XVariety

    data class Union(val memberTypes: List<QName>) : XVariety
}

// doc for enumeration values
data class XFacet(val name: String, val value: String, val doc: String?, val line: Int)

data class XGroup(val name: String, val content: XContent, val line: Int)

data class XAttributeGroup(val name: String, val attributes: List<XAttributeUse>, val line: Int)
