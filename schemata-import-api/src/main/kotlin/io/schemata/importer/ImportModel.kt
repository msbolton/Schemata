package io.schemata.importer

import io.schemata.lang.Diagnostic

/** The result of lowering a set of source documents into Schemata units. */
data class Imported(val units: List<SchemataUnit>, val diagnostics: List<Diagnostic>)

/**
 * One `.schemata` file's worth of declarations, lowered from one source document. [annotations] are
 * printed above the `namespace` line; they carry what the source format said about its own
 * namespace when that cannot be derived from [namespace], such as `@xsd(namespace = "…")` or
 * `@proto(package = "…")`.
 */
data class SchemataUnit(
    val namespace: String,
    val annotations: List<UnitAnnotation> = emptyList(),
    val doc: String?,
    val imports: List<String>,
    val declarations: List<UnitDecl>,
    val services: List<UnitService> = emptyList(),
    val sourcePath: String,
)

/** A `service` block; [reserved] prints as one `reserved` statement after the operations. */
data class UnitService(
    val name: String,
    val operations: List<UnitOperation>,
    val doc: String?,
    val annotations: List<UnitAnnotation>,
    val reserved: List<UnitReserved> = emptyList(),
    val deprecated: Boolean = false,
)

/**
 * One operation. [binding] is the HTTP binding exactly as Schemata source spells it (`get
 * "/orders/{id}"`), or null. [ordinal] prints as `#n` before the name; a service gives every
 * operation one or none.
 */
data class UnitOperation(
    val name: String,
    val request: UnitPayload?,
    val response: UnitPayload?,
    val binding: String?,
    val doc: String?,
    val annotations: List<UnitAnnotation>,
    val ordinal: Int? = null,
    val deprecated: Boolean = false,
)

data class UnitPayload(val type: UnitType.Ref, val stream: Boolean)

/** A declaration; [deprecated] prints `@deprecated` ahead of its other [annotations]. */
sealed interface UnitDecl {
    val name: String
    val doc: String?
    val annotations: List<UnitAnnotation>
    val deprecated: Boolean
}

/** [reserved] prints as one `reserved` statement after the fields and nested declarations. */
data class UnitRecord(
    override val name: String,
    val fields: List<UnitField>,
    val nested: List<UnitDecl>,
    override val doc: String?,
    override val annotations: List<UnitAnnotation>,
    val reserved: List<UnitReserved> = emptyList(),
    override val deprecated: Boolean = false,
) : UnitDecl

/** [reserved] prints as one `reserved` statement after the values. */
data class UnitEnum(
    override val name: String,
    val values: List<UnitEnumValue>,
    override val doc: String?,
    override val annotations: List<UnitAnnotation>,
    val reserved: List<UnitReserved> = emptyList(),
    override val deprecated: Boolean = false,
) : UnitDecl

/** [ordinal] prints as `#n` before the name; a declaration gives every value one or none. */
data class UnitEnumValue(
    val name: String,
    val doc: String?,
    val annotations: List<UnitAnnotation>,
    val ordinal: Int? = null,
    val deprecated: Boolean = false,
)

data class UnitUnion(
    override val name: String,
    val members: List<UnionMember>,
    override val doc: String?,
    override val annotations: List<UnitAnnotation>,
    override val deprecated: Boolean = false,
) : UnitDecl

/**
 * One union member. A source format may document a member apart from the type it names (an XSD
 * choice documents its element, not the element's type), so [doc] travels with the member rather
 * than with [UnitType]. [ordinal] prints as `#n` before the type; a union gives every member one or
 * none.
 */
data class UnionMember(val type: UnitType, val doc: String? = null, val ordinal: Int? = null)

/**
 * [default] is the literal exactly as it is written in Schemata source, already escaped/quoted.
 * [ordinal] prints as `#n` before the name; a record gives every field one or none.
 */
data class UnitField(
    val name: String,
    val type: UnitType,
    val nullable: Boolean,
    val default: String?,
    val doc: String?,
    val annotations: List<UnitAnnotation>,
    val ordinal: Int? = null,
    val deprecated: Boolean = false,
)

/**
 * One item of a `reserved` statement: `reserved #3`, `reserved #5..#7`, `reserved "old_name"`. A
 * single ordinal is a range whose [Ordinals.from] equals its [Ordinals.to].
 */
sealed interface UnitReserved {
    data class Ordinals(val from: Int, val to: Int) : UnitReserved

    data class Name(val name: String) : UnitReserved
}

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
 * One target annotation argument. `@xsd(name = "…")` is `UnitAnnotation("xsd", "name", "\"…\"")`; a
 * flag such as `@xsd(attribute)` has a null [value]; [value] is literal text, already quoted when
 * it is a string.
 */
data class UnitAnnotation(val target: String, val key: String, val value: String?)
