package io.schemata.target.xsd

import io.schemata.target.TargetModel

/** Every `.xsd` the compilation produces, one per namespace, in namespace order. */
data class XsdModel(val files: List<XsdFile>) : TargetModel

/** One schema document, legal by construction: names final, prefixes bound, imports unique. */
data class XsdFile(
    val path: String,
    val targetNamespace: String,
    val imports: List<XsdImport>,
    val types: List<XsdType>,
    val elements: List<XsdElement>,
    val elementFormDefault: String = "qualified",
    val attributeFormDefault: String = "unqualified",
)

/** [prefix] is bound on the root element; the renderer prints `xs:import` per entry. */
data class XsdImport(val namespace: String, val schemaLocation: String, val prefix: String)

sealed interface XsdType {
    val name: String
    val doc: String?
}

/**
 * `xs:complexType` with an `xs:sequence` (an `xs:all` when [all]), then attributes, then
 * [anyAttribute]; [mixed] lets character data appear between the sequence's elements.
 */
data class XsdComplex(
    override val name: String,
    override val doc: String?,
    val sequence: List<XsdParticle>,
    val attributes: List<XsdAttribute> = emptyList(),
    val anyAttribute: XsdAnyAttribute? = null,
    val mixed: Boolean = false,
    val all: Boolean = false,
) : XsdType

/** `xs:complexType` holding an `xs:choice`. */
data class XsdChoice(
    override val name: String,
    override val doc: String?,
    val members: List<XsdElement>,
) : XsdType

/** `xs:simpleType` restricting `xs:string` to the listed values. */
data class XsdEnumeration(
    override val name: String,
    override val doc: String?,
    val values: List<XsdEnumValue>,
) : XsdType

data class XsdEnumValue(val value: String, val doc: String?)

/** One item of an `xs:sequence`: an element or an element wildcard. */
sealed interface XsdParticle

/** [maxOccurs] null means `unbounded`. [unique] names an `xs:unique` over `tns:entry/@key`. */
data class XsdElement(
    val name: String,
    val type: XsdTypeRef,
    val minOccurs: Int = 1,
    val maxOccurs: Int? = 1,
    val nillable: Boolean = false,
    val default: String? = null,
    val doc: String? = null,
    val unique: String? = null,
) : XsdParticle

/**
 * `xs:any`: elements the schema does not name. [namespace] is the wildcard's namespace constraint
 * (`##any` when null); [maxOccurs] null means `unbounded`.
 */
data class XsdAny(
    val minOccurs: Int,
    val maxOccurs: Int?,
    val namespace: String?,
    val processContents: String,
) : XsdParticle

/** `xs:anyAttribute`: attributes the schema does not name. */
data class XsdAnyAttribute(val namespace: String?, val processContents: String)

data class XsdAttribute(
    val name: String,
    val type: XsdTypeRef,
    val required: Boolean,
    val default: String? = null,
    val doc: String? = null,
)

sealed interface XsdTypeRef {
    /** `xs:string`, `xs:int`, … */
    data class Builtin(val xsName: String) : XsdTypeRef

    /**
     * A named type in this file (`tns`) or an imported one; [simple] says whether it is a
     * simpleType.
     */
    data class Named(val prefix: String, val name: String, val simple: Boolean) : XsdTypeRef

    /** An anonymous `xs:simpleType` restriction of a builtin. */
    data class Restricted(val base: String, val facets: List<XsdFacet>) : XsdTypeRef

    /**
     * An anonymous `xs:complexType` with a sequence and attributes (map wrappers, nested
     * collections).
     */
    data class Anonymous(
        val sequence: List<XsdParticle>,
        val attributes: List<XsdAttribute> = emptyList(),
    ) : XsdTypeRef

    /**
     * An anonymous complexType extending [base] with attributes: simpleContent for a simple base,
     * complexContent otherwise.
     */
    data class Extension(val base: XsdTypeRef, val attributes: List<XsdAttribute>) : XsdTypeRef

    /**
     * An anonymous `xs:simpleType` list of [item] (a builtin, a named simple type, or a restricted
     * builtin), its length bounded by [minLength] and [maxLength] when they are set.
     */
    data class ListOf(
        val item: XsdTypeRef,
        val minLength: Int? = null,
        val maxLength: Int? = null,
    ) : XsdTypeRef
}

/** A facet element `<xs:NAME value="VALUE"/>`, printed in list order. */
data class XsdFacet(val name: String, val value: String)
