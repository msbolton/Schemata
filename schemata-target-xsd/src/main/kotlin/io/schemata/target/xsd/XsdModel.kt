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
)

/** [prefix] is bound on the root element; the renderer prints `xs:import` per entry. */
data class XsdImport(val namespace: String, val schemaLocation: String, val prefix: String)

sealed interface XsdType {
    val name: String
    val doc: String?
}

/** `xs:complexType` with an `xs:sequence` then attributes. */
data class XsdComplex(
    override val name: String,
    override val doc: String?,
    val sequence: List<XsdElement>,
    val attributes: List<XsdAttribute> = emptyList(),
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
)

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
        val sequence: List<XsdElement>,
        val attributes: List<XsdAttribute> = emptyList(),
    ) : XsdTypeRef

    /**
     * An anonymous complexType extending [base] with attributes: simpleContent for a simple base,
     * complexContent otherwise.
     */
    data class Extension(val base: XsdTypeRef, val attributes: List<XsdAttribute>) : XsdTypeRef
}

/** A facet element `<xs:NAME value="VALUE"/>`, printed in list order. */
data class XsdFacet(val name: String, val value: String)
