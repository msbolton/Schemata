package io.schemata.importer.xsd

import io.schemata.lang.Diagnostic

/** The result of lowering a set of `.xsd` documents into Schemata units. */
data class Imported(val units: List<SchemataUnit>, val diagnostics: List<Diagnostic>)

/**
 * One `.schemata` file's worth of declarations, lowered from one `.xsd` document. [xsdNamespace] is
 * the XSD `targetNamespace` as read, kept so the emitter can decide whether `@xsd(namespace)` is
 * needed; it is null when the document declared none.
 */
data class SchemataUnit(
    val namespace: String,
    val xsdNamespace: String?,
    val doc: String?,
    val imports: List<String>,
    val declarations: List<UnitDecl>,
    val sourcePath: String,
)

sealed interface UnitDecl {
    val name: String
    val doc: String?
    val annotations: List<UnitAnnotation>
}

data class UnitRecord(
    override val name: String,
    val fields: List<UnitField>,
    val nested: List<UnitDecl>,
    override val doc: String?,
    override val annotations: List<UnitAnnotation>,
) : UnitDecl

data class UnitEnum(
    override val name: String,
    val values: List<UnitEnumValue>,
    override val doc: String?,
    override val annotations: List<UnitAnnotation>,
) : UnitDecl

data class UnitEnumValue(val name: String, val doc: String?, val annotations: List<UnitAnnotation>)

data class UnitUnion(
    override val name: String,
    val members: List<UnionMember>,
    override val doc: String?,
    override val annotations: List<UnitAnnotation>,
) : UnitDecl

/**
 * One union member: the XSD target writes a choice member's documentation on its element, not on
 * the type it names, so it travels with the member here rather than with [UnitType].
 */
data class UnionMember(val type: UnitType, val doc: String? = null)

/** [default] is the literal exactly as it is written in Schemata source, already escaped/quoted. */
data class UnitField(
    val name: String,
    val type: UnitType,
    val nullable: Boolean,
    val default: String?,
    val doc: String?,
    val annotations: List<UnitAnnotation>,
)

sealed interface UnitType {
    /**
     * [refinements] are (key, literal text) pairs; `decimal` carries `("p", …), ("s", …)` first.
     */
    data class Scalar(val builtin: String, val refinements: List<Pair<String, String>>) : UnitType

    /** `Order.Line`, or `shop.customers.Customer` for a type from another namespace. */
    data class Ref(val name: String) : UnitType

    data class ListOf(
        val element: UnitType,
        val nullableElement: Boolean,
        val refinements: List<Pair<String, String>>,
    ) : UnitType

    data class MapOf(
        val key: UnitType,
        val value: UnitType,
        val nullableValue: Boolean,
        val refinements: List<Pair<String, String>>,
    ) : UnitType
}

/**
 * One `@xsd(…)` annotation argument. `@xsd(name = "…")` is `UnitAnnotation("xsd", "name",
 * "\"…\"")`; a flag such as `@xsd(attribute)` has a null [value]; [value] is literal text, already
 * quoted when it is a string.
 */
data class UnitAnnotation(val target: String, val key: String, val value: String?)
